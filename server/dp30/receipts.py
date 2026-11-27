import hashlib
import json

from fastapi.encoders import jsonable_encoder
from psycopg.types.json import Jsonb

from .errors import fail, invalid


def fingerprint(data, scope):
    canonical = json.dumps([scope, data.model_dump(mode="json", exclude={"operation_id"})],
                           ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode()).hexdigest()


def replay(db, operation_id, actor_id, signature, *, admin=False):
    # One DB lock protects both receipt tables, including across API processes.
    db.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 30))", (str(operation_id),))
    table = "admin_receipts" if admin else "command_receipts"
    other = "command_receipts" if admin else "admin_receipts"
    row = db.execute(f"SELECT * FROM {table} WHERE operation_id=%s", (operation_id,)).fetchone()
    if row:
        if row["actor_id"] != actor_id or row["fingerprint"] != signature:
            fail(409, "operation_conflict", "Этот идентификатор операции уже использован.")
        return row["response"]
    if db.execute(f"SELECT 1 FROM {other} WHERE operation_id=%s", (operation_id,)).fetchone():
        fail(409, "operation_conflict", "Этот идентификатор операции уже использован.")
    return None


def save(db, operation_id, actor_id, signature, result, request_id=None):
    payload = Jsonb(jsonable_encoder(result))
    if request_id is None:
        db.execute("INSERT INTO admin_receipts VALUES (%s,%s,%s,%s)",
                   (operation_id, actor_id, signature, payload))
    else:
        db.execute("""INSERT INTO command_receipts(operation_id,actor_id,request_id,fingerprint,response)
                      VALUES (%s,%s,%s,%s,%s)""", (operation_id, actor_id, request_id, signature, payload))
    return result


def expect_creation(data):
    if data.expected_version != 0:
        invalid("expected_version", "Для новой записи ожидаемая версия равна 0.")
