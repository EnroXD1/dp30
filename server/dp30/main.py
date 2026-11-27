from contextlib import asynccontextmanager
from typing import Annotated
from uuid import UUID

from fastapi import Depends, FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool
from pydantic import AwareDatetime

from . import access, admin, requests
from .contracts import (
    AdminAuditView, BrigadeInput, BrigadeView, CardView, Command, CreateRequest, CreateUser,
    Done, ErrorView, Login, LoginView, PageView, ProfileView, ReferenceInput, ReferenceView,
    RequestEventView, Status, SummaryView, UserInput, UserView,
)
from .db import database_url
from .errors import fail


@asynccontextmanager
async def lifespan(app):
    with ConnectionPool(database_url(), min_size=1, max_size=12, kwargs={"row_factory": dict_row}) as pool:
        pool.wait()
        app.state.pool = pool
        yield


app = FastAPI(
    title="ДП-30 • API диспетчерского пункта", version="1.0.0", lifespan=lifespan,
    responses={code: {"model": ErrorView} for code in (401, 403, 404, 409, 422, 429)},
)
prefix = "/api/v1"
bearer = HTTPBearer(auto_error=False)
Credentials = Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]


@app.exception_handler(RequestValidationError)
async def validation_error(_, error):
    field = str(error.errors()[0]["loc"][-1])
    translated = {"address": "Введите адрес (1–250 символов).",
                  "description": "Введите описание (1–2000 символов).",
                  "due_at": "Укажите дату и время с часовым поясом.",
                  "result": "Введите результат (1–4000 символов)."}
    return JSONResponse(status_code=422, content={"detail": {
        "code": "validation", "message": translated.get(field, "Проверьте значение поля."), "field": field}})


def transaction(request: Request, credentials: Credentials):
    return request.app.state.pool.connection(), ("Bearer " + credentials.credentials if credentials else None)


Tx = Annotated[tuple, Depends(transaction)]


@app.get("/health")
def health(request: Request):
    with request.app.state.pool.connection() as db:
        db.execute("SELECT 1 FROM schema_migrations LIMIT 1")
    return {"status": "ok"}


@app.post(prefix + "/auth/login", response_model=LoginView)
def login(data: Login, request: Request):
    return access.login(request.app.state.pool, data)


@app.get(prefix + "/auth/me", response_model=ProfileView)
def profile(tx: Tx):
    conn, token = tx
    with conn as db:
        user = access.principal(db, token)
        result = access.public_user(user)
        brigade = db.execute("SELECT name FROM brigades WHERE id=%s", (user["brigade_id"],)).fetchone()
        result["brigade_name"] = brigade["name"] if brigade else None
        return result


@app.post(prefix + "/auth/logout", response_model=Done)
def logout(tx: Tx):
    conn, token = tx
    with conn as db:
        user = access.principal(db, token, exclusive_session=True)
        db.execute("UPDATE sessions SET revoked_at=clock_timestamp() WHERE digest=%s",
                   (access.token_digest(token[7:]),))
        access.log_access(db, user, user["login"], "LOGOUT")
        return {"ok": True}


def filters(number: Annotated[int | None, Query(gt=0)] = None, status: Status | None = None,
            brigade_id: Annotated[int | None, Query(gt=0)] = None, due_before: AwareDatetime | None = None,
            due_after: AwareDatetime | None = None, overdue: bool = False):
    return dict(number=number, status=status, brigade_id=brigade_id, due_before=due_before,
                due_after=due_after, overdue=overdue)


Filters = Annotated[dict, Depends(filters)]


@app.get(prefix + "/requests", response_model=PageView)
def list_requests(tx: Tx, params: Filters, limit: Annotated[int, Query(ge=1, le=100)] = 30,
                  offset: Annotated[int, Query(ge=0)] = 0):
    conn, token = tx
    with conn as db:
        return requests.request_list(db, access.principal(db, token), limit, offset, **params)


@app.post(prefix + "/requests", status_code=201, response_model=CardView)
def create_request(data: CreateRequest, tx: Tx):
    conn, token = tx
    with conn as db:
        return requests.create(db, access.principal(db, token), data)


@app.get(prefix + "/requests/{request_id}", response_model=CardView)
def request_card(request_id: int, tx: Tx):
    conn, token = tx
    with conn as db:
        return requests.locate(db, access.principal(db, token), request_id)


@app.get(prefix + "/request-identity/{request_uid}", response_model=CardView)
def request_by_identity(request_uid: UUID, tx: Tx):
    conn, token = tx
    with conn as db:
        user = access.principal(db, token)
        requests.request_reader(user)
        return requests.locate(db, user, requests.resolve_uid(db, request_uid))


@app.get(prefix + "/requests/{request_id}/events", response_model=list[RequestEventView])
def request_events(request_id: int, tx: Tx):
    conn, token = tx
    with conn as db:
        return requests.history(db, access.principal(db, token), request_id)


@app.post(prefix + "/requests/{request_id}/commands", response_model=CardView)
def execute_command(request_id: int, data: Command, tx: Tx):
    conn, token = tx
    with conn as db:
        return requests.command(db, access.principal(db, token), request_id, data)


@app.get(prefix + "/summary", response_model=SummaryView)
def summary(tx: Tx, params: Filters):
    conn, token = tx
    with conn as db:
        return requests.summary(db, access.principal(db, token), **params)


@app.get(prefix + "/brigades", response_model=list[BrigadeView])
def brigades(tx: Tx):
    conn, token = tx
    with conn as db:
        user = access.principal(db, token)
        if user["role"] == "worker":
            return db.execute("SELECT * FROM brigades WHERE id=%s", (user["brigade_id"],)).fetchall()
        return db.execute("SELECT * FROM brigades ORDER BY id").fetchall()


@app.get(prefix + "/users", response_model=list[UserView])
def users(tx: Tx):
    conn, token = tx
    with conn as db:
        access.require_role(access.principal(db, token), "admin")
        return [access.public_user(u) for u in db.execute("SELECT * FROM users ORDER BY id").fetchall()]


@app.get(prefix + "/admin/audit", response_model=list[AdminAuditView])
def admin_audit(tx: Tx):
    conn, token = tx
    with conn as db:
        access.require_role(access.principal(db, token), "admin")
        return db.execute("SELECT * FROM admin_audit_events ORDER BY id DESC LIMIT 100").fetchall()


def write_admin(tx, entity, target, data):
    conn, token = tx
    with conn as db:
        # Serialize administrative writes before locking acting and target users.
        db.execute("SELECT pg_advisory_xact_lock(303031)")
        return admin.upsert(db, access.principal(db, token), entity, target, data)


@app.post(prefix + "/users", status_code=201, response_model=UserView)
def create_user(data: CreateUser, tx: Tx):
    return write_admin(tx, "users", None, data)


@app.patch(prefix + "/users/{target}", response_model=UserView)
def update_user(target: int, data: UserInput, tx: Tx):
    return write_admin(tx, "users", target, data)


@app.post(prefix + "/brigades", status_code=201, response_model=BrigadeView)
def create_brigade(data: BrigadeInput, tx: Tx):
    return write_admin(tx, "brigades", None, data)


@app.patch(prefix + "/brigades/{target}", response_model=BrigadeView)
def update_brigade(target: int, data: BrigadeInput, tx: Tx):
    return write_admin(tx, "brigades", target, data)


@app.get(prefix + "/references", response_model=list[ReferenceView])
def references(tx: Tx):
    conn, token = tx
    with conn as db:
        access.require_role(access.principal(db, token), "admin")
        return db.execute("SELECT * FROM reference_entries ORDER BY kind,code").fetchall()


@app.post(prefix + "/references", status_code=201, response_model=ReferenceView)
def create_reference(data: ReferenceInput, tx: Tx):
    return write_admin(tx, "reference_entries", None, data)


@app.patch(prefix + "/references/{target}", response_model=ReferenceView)
def update_reference(target: int, data: ReferenceInput, tx: Tx):
    return write_admin(tx, "reference_entries", target, data)
