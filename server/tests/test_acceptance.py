from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4
import os
import ssl
import time

import httpx

import psycopg
import pytest
from fastapi.testclient import TestClient

from dp30.db import database_url, migrate
from dp30.demo import seed
from dp30.main import app
from dp30.access import token_digest


def packet(version, **fields):
    return {"operation_id": str(uuid4()), "expected_version": version, **fields}


@pytest.fixture(scope="module")
def api(monkeymodule):
    # Each run is isolated by a fresh, caller-selected PostgreSQL database.
    with psycopg.connect(database_url()) as db:
        assert db.execute("SELECT count(*) FROM pg_tables WHERE schemaname='public'").fetchone()[0] == 0
    migrate()
    monkeymodule.setenv("DP30_DEMO_PASSWORD", "Demo-test-30")
    seed()
    endpoints = os.environ.get("DP30_TEST_ENDPOINTS")
    if endpoints:
        first, second = endpoints.split(",")
        tls = ssl.create_default_context(cafile=os.environ["DP30_TEST_CA"])
        with httpx.Client(base_url=first, verify=tls) as one, httpx.Client(base_url=second, verify=tls) as two:
            for client in (one, two):
                until = time.monotonic() + 15
                while True:
                    ready = client.get("/health")
                    if ready.status_code == 200:
                        break
                    assert time.monotonic() < until, ready.text
                    time.sleep(.3)
            yield one, two
    else:
        with TestClient(app) as one:
            yield one, one


@pytest.fixture(scope="module")
def monkeymodule():
    with pytest.MonkeyPatch.context() as patch:
        yield patch


def signin(client, name, password="Demo-test-30"):
    r = client.post("/api/v1/auth/login", json={"login": name, "password": password})
    assert r.status_code == 200, r.text
    return {"Authorization": "Bearer " + r.json()["token"]}


def get(client, headers, path):
    r = client.get("/api/v1" + path, headers=headers)
    assert r.status_code == 200, r.json()
    return r.json()


def create(client, headers, **fields):
    return client.post("/api/v1/requests", headers=headers, json=packet(0, **{
        "address": "Учебный адрес 1", "description": "Осмотр", "priority": "high",
        "due_at": "2020-01-01T00:00:00Z", **fields}))


def command(client, headers, rid, version, action, **fields):
    return client.post(f"/api/v1/requests/{rid}/commands", headers=headers,
                       json=packet(version, action=action, **fields))


def new_request(client, dispatcher):
    r = create(client, dispatcher)
    assert r.status_code == 201, r.json()
    return r.json()["id"]


def to_work(client, dispatcher, worker):
    rid = new_request(client, dispatcher)
    assert command(client, dispatcher, rid, 1, "assign", brigade_id=1).status_code == 200
    assert command(client, worker, rid, 2, "accept").status_code == 200
    assert command(client, worker, rid, 3, "start").status_code == 200
    return rid


def test_t01_access_expiry_and_lockout(api):
    client, _ = api
    for name in ("dispatcher", "worker", "manager", "admin"):
        headers = signin(client, name)
        assert get(client, headers, "/auth/me")["role"] == name
    worker = signin(client, "worker")
    token = worker["Authorization"].split()[1]
    with psycopg.connect(database_url()) as db:
        db.execute("UPDATE sessions SET expires_at=now()-interval '1 second' WHERE digest=%s", (token_digest(token),))
    assert client.get("/api/v1/auth/me", headers=worker).status_code == 401
    session = signin(client, "worker")
    assert client.post("/api/v1/auth/logout", headers=session).status_code == 200
    assert client.get("/api/v1/auth/me", headers=session).status_code == 401
    for _ in range(5):
        assert client.post("/api/v1/auth/login", json={"login": "dispatcher2", "password": "wrong"}).status_code == 401
    assert client.post("/api/v1/auth/login", json={"login": "dispatcher2", "password": "Demo-test-30"}).status_code == 429
    with psycopg.connect(database_url()) as db:
        upto = db.execute("SELECT blocked_until > now() FROM login_limits WHERE login='dispatcher2'").fetchone()[0]
        assert upto
        db.execute("UPDATE login_limits SET failures='{}',blocked_until=NULL WHERE login='dispatcher2'")


def test_t02_create_validation_and_exact_replay(api):
    client, second = api
    headers = signin(client, "dispatcher")
    response = create(client, headers, address="")
    assert response.status_code == 422 and response.json()["detail"]["field"] == "address"
    fields = packet(0, address="Учебная, 7", description="Осмотр", priority="normal", due_at="2030-12-30T12:00:00Z")
    first = client.post("/api/v1/requests", json=fields, headers=headers)
    repeat = second.post("/api/v1/requests", json=fields, headers=headers)
    assert first.status_code == repeat.status_code == 201
    assert first.json() == repeat.json()
    assert first.json()["version"] == 1
    assert first.json()["number"] == first.json()["id"]
    same = get(second, headers, f"/request-identity/{first.json()['request_uid']}")
    assert same["number"] == first.json()["number"]
    assert len(get(client, headers, f"/requests/{first.json()['id']}/events")) == 1


def test_t03_concurrent_assignment(api):
    one, two = api
    dispatcher = signin(one, "dispatcher")
    dispatcher2 = signin(two, "dispatcher2")
    rid = new_request(one, dispatcher)
    with ThreadPoolExecutor(2) as pool:
        results = [pool.submit(command, client, headers, rid, 1, "assign", brigade_id=i+1)
                   for i, (client, headers) in enumerate(((one, dispatcher), (two, dispatcher2)))]
        statuses = sorted(r.result().status_code for r in results)
    assert statuses == [200,409]
    with psycopg.connect(database_url()) as db:
        assert db.execute("SELECT count(*) FROM assignments WHERE request_id=%s AND ended_at IS NULL", (rid,)).fetchone()[0] == 1
    assert len(get(one, dispatcher, f"/requests/{rid}/events")) == 2


def test_t05_lifecycle_permissions_and_filters(api):
    one, _ = api
    dispatcher = signin(one, "dispatcher")
    worker = signin(one, "worker")
    other = signin(one, "worker2")
    manager = signin(one, "manager")
    admin = signin(one, "admin")
    rid = new_request(one, dispatcher)
    assert command(one, dispatcher, rid, 1, "assign", brigade_id=1).status_code == 200
    assert command(one, worker, rid, 2, "start").status_code == 409
    assert command(one, other, rid, 2, "accept").status_code == 404
    assert one.get(f"/api/v1/requests/{rid}/events", headers=other).status_code == 404
    assert get(one, other, f"/requests?number={rid}")["items"] == []
    assert len(get(one, worker, f"/requests?number={rid}&brigade_id=1&status=ASSIGNED&overdue=true")["items"]) == 1
    assert command(one, worker, rid, 2, "accept").status_code == 200
    assert command(one, dispatcher, rid, 3, "assign", brigade_id=2).status_code == 409
    assert command(one, worker, rid, 3, "start").status_code == 200
    assert command(one, worker, rid, 4, "complete", result="Осмотр выполнен").json()["result"] == "Осмотр выполнен"
    for headers in (manager, admin):
        assert command(one, headers, rid, 5, "close").status_code == 403


def test_t06_return_close_cancel(api):
    one, _ = api
    dispatcher, worker, manager = (signin(one, name) for name in ("dispatcher", "worker", "manager"))
    rid = to_work(one, dispatcher, worker)
    assert command(one, worker, rid, 4, "complete", result="Результат").status_code == 200
    assert command(one, dispatcher, rid, 5, "return").status_code == 422
    returned = command(one, dispatcher, rid, 5, "return", reason="Нужен замер").json()
    assert returned["status"] == "IN_WORK" and returned["brigade_id"] == 1
    assert command(one, worker, rid, 6, "complete", result="Повторный замер").status_code == 200
    assert command(one, dispatcher, rid, 7, "close").json()["status"] == "CLOSED"
    assert command(one, dispatcher, rid, 8, "cancel", reason="Недопустимо").status_code == 409
    assert get(one, manager, f"/summary?number={rid}")["overdue"] == 0
    related = create(one, dispatcher, parent_id=rid).json()["id"]
    assert command(one, dispatcher, related, 1, "cancel", reason="Проверка не нужна").json()["status"] == "CANCELLED"
    assert get(one, manager, f"/summary?number={related}")["overdue"] == 0


def test_t07_repeated_delivery_conflict_and_access(api):
    one, two = api
    dispatcher, worker = (signin(one, name) for name in ("dispatcher", "worker"))
    rid = to_work(one, dispatcher, worker)
    path = f"/api/v1/requests/{rid}/commands"
    command_body = packet(4, action="complete", result="Учебный результат")
    with ThreadPoolExecutor(8) as pool:
        results = list(pool.map(lambda i: (one if i%2 else two).post(path, headers=worker, json=command_body), range(20)))
    assert all(r.status_code == 200 and r.json() == results[0].json() for r in results)
    assert [e["kind"] for e in get(two, worker, f"/requests/{rid}/events")].count("COMPLETE") == 1
    changed = two.post(path, headers=worker, json={**command_body, "result": "Изменённый текст"})
    assert changed.status_code == 409 and changed.json()["detail"]["code"] == "operation_conflict"
    stale = command(two, worker, rid, 4, "complete", result="Другой результат")
    assert stale.status_code == 409 and stale.json()["detail"]["current"]["version"] == 5
    assert one.post("/api/v1/auth/logout", headers=worker).status_code == 200
    assert two.post(path, headers=worker, json=command_body).status_code == 401
    same_user = signin(two, "worker")
    assert two.post(path, headers=same_user, json=command_body).json() == results[0].json()


def test_t08_summary_administration_and_audit(api):
    one, two = api
    admin = signin(one, "admin")
    manager = signin(one, "manager")
    body = packet(0, login="trainee", password="Training-30", display_name="Учебный сотрудник",
                  role="worker", brigade_id=1)
    created = one.post("/api/v1/users", json=body, headers=admin)
    assert created.status_code == 201, created.json()
    assert two.post("/api/v1/users", json=body, headers=admin).json() == created.json()
    worker = signin(one, "trainee", "Training-30")
    target = created.json()["id"]
    changed = one.patch(f"/api/v1/users/{target}", headers=admin,
                        json=packet(1, display_name="Учебный сотрудник", role="worker", brigade_id=1, active=False))
    assert changed.status_code == 200, changed.json()
    assert two.get("/api/v1/auth/me", headers=worker).status_code == 401
    brigade = one.post("/api/v1/brigades", headers=admin, json=packet(0,name="Учебный резерв"))
    assert brigade.status_code == 201
    change = one.patch(f"/api/v1/brigades/{brigade.json()['id']}", headers=admin,
                       json=packet(1, name="Резервная бригада", active=False))
    assert change.status_code == 200
    reference = one.post("/api/v1/references", headers=admin,
                         json=packet(0, kind="equipment", code="lab-switch", title="Учебный стенд"))
    assert reference.status_code == 201
    ref_id = reference.json()["id"]
    reference_update = packet(1, kind="equipment", code="lab-switch", title="Стенд Б", active=False)
    update = one.patch(f"/api/v1/references/{ref_id}", headers=admin, json=reference_update)
    assert update.status_code == 200 and update.json()["version"] == 2
    assert two.patch(f"/api/v1/references/{ref_id}", headers=admin, json=reference_update).json() == update.json()
    assert two.get("/api/v1/references", headers=manager).status_code == 403
    audit = get(two, admin, "/admin/audit")
    assert sum(row["operation_id"] == body["operation_id"] for row in audit) == 1
    assert any(row["entity_id"] == target and row["kind"] == "BLOCK" for row in audit)
    totals = get(one, manager, "/summary")
    with psycopg.connect(database_url()) as db:
        expected = db.execute("""SELECT count(*),count(*) FILTER (WHERE due_at < now()
                        AND status NOT IN ('CLOSED','CANCELLED')) FROM requests""").fetchone()
    assert (totals["total"],totals["overdue"]) == expected
    assert sum(totals["by_status"].values()) == totals["total"]
