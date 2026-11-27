from psycopg.errors import UniqueViolation

from .access import PASSWORDS, public_user, require_role
from .errors import fail, invalid
from .receipts import expect_creation, fingerprint, replay, save


def upsert(db, user, entity, target, data):
    require_role(user, "admin")
    signature = fingerprint(data, f"admin:{entity}:{target}")
    if old := replay(db, data.operation_id, user["id"], signature, admin=True):
        return old
    if target is None:
        expect_creation(data)
    else:
        current = db.execute(f"SELECT * FROM {entity} WHERE id=%s FOR UPDATE", (target,)).fetchone()
        if not current:
            fail(404, "not_found", "Запись не найдена.")
        if data.expected_version != current["version"]:
            body = public_user(current) if entity == "users" else current
            fail(409, "state_conflict", "Запись уже изменена. Обновите форму.", current=body)
    try:
        if entity == "users":
            row = update_user(db, user, target, data)
        elif entity == "brigades":
            row = update_brigade(db, target, data)
        else:
            row = update_reference(db, target, data)
    except UniqueViolation:
        field = {"users": "login", "brigades": "name", "reference_entries": "code"}[entity]
        invalid(field, "Это значение уже используется.")
    result = public_user(row) if entity == "users" else row
    kind = "CREATE" if target is None else "UPDATE"
    if entity == "users" and target is not None and current["active"] and not row["active"]:
        kind = "BLOCK"
    db.execute("""INSERT INTO admin_audit_events(actor_id,entity,entity_id,kind,operation_id)
                  VALUES (%s,%s,%s,%s,%s)""",
               (user["id"], entity, row["id"], kind, data.operation_id))
    return save(db, data.operation_id, user["id"], signature, result)


def update_user(db, actor, target, data):
    if (data.role == "worker") != (data.brigade_id is not None):
        invalid("brigade_id", "Бригада указывается для исполнителя.")
    if data.brigade_id and not db.execute("SELECT id FROM brigades WHERE id=%s", (data.brigade_id,)).fetchone():
        invalid("brigade_id", "Бригада не найдена.")
    if target == actor["id"] and (not data.active or data.role != "admin"):
        invalid("active", "Изменить ваш административный доступ должен другой администратор.")
    fields = (data.display_name, data.role, data.brigade_id, data.active)
    if target is None:
        return db.execute("""INSERT INTO users(display_name,role,brigade_id,active,login,password_hash)
                             VALUES (%s,%s,%s,%s,%s,%s) RETURNING *""",
                          (*fields, data.login.lower(), PASSWORDS.hash(data.password))).fetchone()
    row = db.execute("""UPDATE users SET display_name=%s,role=%s,brigade_id=%s,active=%s,
                        version=version+1 WHERE id=%s RETURNING *""", (*fields, target)).fetchone()
    if not data.active:
        db.execute("UPDATE sessions SET revoked_at=clock_timestamp() WHERE user_id=%s AND revoked_at IS NULL",
                   (target,))
    return row


def update_brigade(db, target, data):
    if target is None:
        return db.execute("INSERT INTO brigades(name,active) VALUES (%s,%s) RETURNING *",
                          (data.name, data.active)).fetchone()
    return db.execute("UPDATE brigades SET name=%s,active=%s,version=version+1 WHERE id=%s RETURNING *",
                      (data.name, data.active, target)).fetchone()


def update_reference(db, target, data):
    fields = (data.kind, data.code.lower(), data.title, data.active)
    if target is None:
        return db.execute("""INSERT INTO reference_entries(kind,code,title,active)
                             VALUES (%s,%s,%s,%s) RETURNING *""", fields).fetchone()
    return db.execute("""UPDATE reference_entries SET kind=%s,code=%s,title=%s,active=%s,
                         version=version+1 WHERE id=%s RETURNING *""", (*fields, target)).fetchone()
