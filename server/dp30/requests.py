from .access import require_role
from .errors import fail, invalid
from .receipts import expect_creation, fingerprint, replay, save

TERMINAL = ("CLOSED", "CANCELLED")
OVERDUE = "r.due_at < %s AND r.status NOT IN ('CLOSED','CANCELLED')"
CARD = """SELECT r.*, r.id AS number, a.brigade_id, b.name AS brigade_name, statement_timestamp() AS server_time
          FROM requests r
          LEFT JOIN assignments a ON a.request_id=r.id AND a.ended_at IS NULL
          LEFT JOIN brigades b ON b.id=a.brigade_id"""


def request_reader(user):
    require_role(user, "dispatcher", "worker", "manager")


def scope(user):
    if user["role"] == "worker":
        return ("EXISTS (SELECT 1 FROM assignments aw WHERE aw.request_id=r.id "
                "AND aw.brigade_id=%s AND (aw.ended_at IS NULL OR "
                "(r.status IN ('CLOSED','CANCELLED') AND aw.id="
                "(SELECT max(id) FROM assignments WHERE request_id=r.id))))", [user["brigade_id"]])
    return "TRUE", []


def locate(db, user, request_id, lock=False):
    request_reader(user)
    if lock:
        # Serialize writes and then check visibility against the current assignment.
        db.execute("SELECT id FROM requests WHERE id=%s FOR UPDATE", (request_id,)).fetchone()
    where, args = scope(user)
    row = db.execute(CARD + f" WHERE r.id=%s AND {where}", [request_id, *args]).fetchone()
    if not row:
        fail(404, "not_found", "Заявка не найдена или недоступна.")
    return row


def resolve_uid(db, uid):
    row = db.execute("SELECT id FROM requests WHERE request_uid=%s", (uid,)).fetchone()
    if not row:
        fail(404, "not_found", "Заявка не найдена или недоступна.")
    return row["id"]


def criteria(user, now, number=None, status=None, brigade_id=None, due_before=None, overdue=False,
             due_after=None):
    where, args = scope(user)
    terms = [where]
    for col, operator, val in (("r.id", "=", number), ("r.status", "=", status),
                               ("a.brigade_id", "=", brigade_id), ("r.due_at", "<=", due_before),
                               ("r.due_at", ">=", due_after)):
        if val is not None:
            terms.append(f"{col} {operator} %s")
            args.append(val)
    if overdue:
        terms.append(f"({OVERDUE})")
        args.append(now)
    return " AND ".join(terms), args


def request_list(db, user, limit, offset, **filters):
    request_reader(user)
    now = db.execute("SELECT statement_timestamp() AS now").fetchone()["now"]
    where, args = criteria(user, now, **filters)
    rows = db.execute(CARD + f" WHERE {where} ORDER BY r.id DESC LIMIT %s OFFSET %s",
                      [*args, limit + 1, offset]).fetchall()
    return {"items": rows[:limit], "server_time": now, "next_offset": offset + limit if len(rows) > limit else None}


def history(db, user, request_id):
    request_reader(user)
    db.execute("SELECT id FROM requests WHERE id=%s FOR SHARE", (request_id,)).fetchone()
    locate(db, user, request_id)
    return db.execute("""SELECT e.*,u.display_name AS actor_name FROM request_events e
                         JOIN users u ON u.id=e.actor_id WHERE request_id=%s ORDER BY e.version""",
                      (request_id,)).fetchall()


def event(db, user, data, row, action, comment=None):
    db.execute("""INSERT INTO request_events(request_id,actor_id,kind,status,version,comment,operation_id)
                  VALUES (%s,%s,%s,%s,%s,%s,%s)""",
               (row["id"], user["id"], action, row["status"], row["version"], comment, data.operation_id))


def create(db, user, data):
    require_role(user, "dispatcher")
    expect_creation(data)
    sig = fingerprint(data, "request:create")
    if old := replay(db, data.operation_id, user["id"], sig):
        return old
    if data.parent_id is not None and locate(db, user, data.parent_id)["status"] not in TERMINAL:
        invalid("parent_id", "Связать можно только с закрытой или отменённой заявкой.")
    created = db.execute("""INSERT INTO requests(parent_id,address,description,priority,due_at)
                           VALUES (%s,%s,%s,%s,%s) RETURNING id""",
                         (data.parent_id, data.address, data.description, data.priority, data.due_at)).fetchone()
    row = locate(db, user, created["id"])
    event(db, user, data, row, "CREATE")
    return save(db, data.operation_id, user["id"], sig, row, row["id"])


TRANSITIONS = {
    "assign": ("dispatcher", ("NEW", "ASSIGNED"), "ASSIGNED"),
    "accept": ("worker", ("ASSIGNED",), "ACCEPTED"),
    "start": ("worker", ("ACCEPTED",), "IN_WORK"),
    "complete": ("worker", ("IN_WORK",), "COMPLETED"),
    "return": ("dispatcher", ("COMPLETED",), "IN_WORK"),
    "close": ("dispatcher", ("COMPLETED",), "CLOSED"),
    "cancel": ("dispatcher", ("NEW", "ASSIGNED", "ACCEPTED", "IN_WORK", "COMPLETED"), "CANCELLED"),
}


def command(db, user, request_id, data):
    role, states, after = TRANSITIONS[data.action]
    require_role(user, role)
    row = locate(db, user, request_id, lock=True)
    sig = fingerprint(data, f"request:{request_id}")
    if old := replay(db, data.operation_id, user["id"], sig):
        return old
    if row["version"] != data.expected_version or row["status"] not in states:
        fail(409, "state_conflict", "Версия или состояние заявки изменились.", current=row)
    if data.action == "assign":
        brigade = db.execute("SELECT id FROM brigades WHERE id=%s AND active FOR SHARE",
                             (data.brigade_id,)).fetchone()
        if not brigade:
            invalid("brigade_id", "Выберите действующую бригаду.")
    if data.action in ("return", "cancel") and not data.reason:
        invalid("reason", "Укажите причину.")
    if data.action == "complete" and not data.result:
        invalid("result", "Введите результат работ.")
    result = data.result if data.action == "complete" else row["result"]
    if data.action == "return":
        result = None
    db.execute("UPDATE requests SET status=%s,version=version+1,result=%s WHERE id=%s",
               (after, result, request_id))
    if data.action == "assign" or after in TERMINAL:
        db.execute("UPDATE assignments SET ended_at=clock_timestamp() WHERE request_id=%s AND ended_at IS NULL",
                   (request_id,))
    if data.action == "assign":
        db.execute("INSERT INTO assignments(request_id,brigade_id,assigned_by) VALUES (%s,%s,%s)",
                   (request_id, data.brigade_id, user["id"]))
    updated = locate(db, user, request_id)
    action = "REASSIGN" if data.action == "assign" and row["status"] == "ASSIGNED" else data.action.upper()
    event(db, user, data, updated, action, data.result if data.action == "complete" else data.reason)
    return save(db, data.operation_id, user["id"], sig, updated, request_id)


def summary(db, user, **filters):
    require_role(user, "manager")
    now = db.execute("SELECT statement_timestamp() AS now").fetchone()["now"]
    where, args = criteria(user, now, **filters)
    totals = db.execute(f"""WITH selected AS (
                                SELECT r.status, a.brigade_id, ({OVERDUE}) AS overdue
                                FROM requests r LEFT JOIN assignments a
                                  ON a.request_id=r.id AND a.ended_at IS NULL WHERE {where}
                            )
                            SELECT status,brigade_id,count(*) AS count,
                                   count(*) FILTER (WHERE overdue) AS overdue
                            FROM selected GROUP BY status,brigade_id""", [now, *args]).fetchall()
    states, brigades, overdue, count = {}, {}, 0, 0
    for row in totals:
        n = row["count"]
        states[row["status"]] = states.get(row["status"], 0) + n
        brigade_id = row["brigade_id"]
        if brigade_id is not None:
            brigades[brigade_id] = brigades.get(brigade_id, 0) + n
        overdue += row["overdue"]
        count += n
    return {"total": count, "overdue": overdue, "by_status": states, "by_brigade": brigades, "server_time": now}
