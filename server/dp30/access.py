import hashlib
import secrets
from datetime import timedelta

from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from fastapi import HTTPException

from .errors import fail

PASSWORDS = PasswordHasher(time_cost=2, memory_cost=19456, parallelism=1)
DUMMY_HASH = PASSWORDS.hash(secrets.token_urlsafe(32))


def token_digest(token):
    return hashlib.sha256(token.encode()).hexdigest()


def public_user(row):
    return {key: row[key] for key in ("id", "login", "display_name", "role", "brigade_id", "active", "version")}


def principal(db, authorization, exclusive_session=False):
    if not authorization or not authorization.startswith("Bearer "):
        fail(401, "auth_required", "Войдите в приложение.")
    digest = token_digest(authorization[7:])
    query = """SELECT u.* FROM users u JOIN sessions s ON s.user_id=u.id
               WHERE s.digest=%s AND u.active AND s.revoked_at IS NULL
               AND s.expires_at > clock_timestamp()"""
    rows = db.execute(query + " FOR SHARE OF u", (digest,)).fetchone()
    if not rows:
        fail(401, "auth_required", "Сессия недействительна. Войдите снова.")
    # A concurrent logout may finish after the first snapshot.
    session_lock = "FOR UPDATE" if exclusive_session else "FOR SHARE"
    if not db.execute(f"""SELECT 1 FROM sessions WHERE digest=%s AND revoked_at IS NULL
                          AND expires_at > clock_timestamp() {session_lock}""", (digest,)).fetchone():
        fail(401, "auth_required", "Сессия недействительна. Войдите снова.")
    return rows


def require_role(user, *roles):
    if user["role"] not in roles:
        fail(403, "forbidden", "Для этого действия недостаточно прав.")


def log_access(db, user, login, kind):
    db.execute("INSERT INTO access_events(user_id,login,kind) VALUES (%s,%s,%s)",
               (user["id"] if user else None, login, kind))


def login(pool, data):
    name = data.login.lower()
    error = None
    result = None
    with pool.connection() as db:
        db.execute("INSERT INTO login_limits(login) VALUES (%s) ON CONFLICT DO NOTHING", (name,))
        limit = db.execute("SELECT * FROM login_limits WHERE login=%s FOR UPDATE", (name,)).fetchone()
        now = db.execute("SELECT clock_timestamp() AS now").fetchone()["now"]
        user = db.execute("SELECT * FROM users WHERE login=%s FOR SHARE", (name,)).fetchone()
        if limit["blocked_until"] and limit["blocked_until"] > now:
            log_access(db, user, name, "LOGIN_LIMITED")
            error = (429, "login_limited", "Слишком много попыток. Повторите вход после паузы.")
        else:
            try:
                valid = PASSWORDS.verify(user["password_hash"] if user else DUMMY_HASH, data.password)
            except VerifyMismatchError:
                valid = False
            if not (user and user["active"] and valid):
                failures = [t for t in limit["failures"] if t > now - timedelta(minutes=15)]
                failures.append(now)
                blocked_until = now + timedelta(minutes=15) if len(failures) >= 5 else None
                db.execute("UPDATE login_limits SET failures=%s,blocked_until=%s WHERE login=%s",
                           (failures, blocked_until, name))
                log_access(db, user, name, "LOGIN_BLOCKED" if blocked_until else "LOGIN_FAILED")
                error = (401, "bad_credentials", "Неверное имя пользователя или пароль.")
            else:
                db.execute("UPDATE login_limits SET failures='{}',blocked_until=NULL WHERE login=%s", (name,))
                token = secrets.token_urlsafe(32)
                expires = now + timedelta(hours=8)
                db.execute("INSERT INTO sessions(digest,user_id,expires_at) VALUES (%s,%s,%s)",
                           (token_digest(token), user["id"], expires))
                log_access(db, user, name, "LOGIN_SUCCEEDED")
                result = {"token": token, "expires_at": expires, "user": public_user(user)}
    if error:
        fail(*error)
    return result
