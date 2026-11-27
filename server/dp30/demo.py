"""Populate an isolated database with fictional accounts and optional load data."""
import argparse
import os

import psycopg

from .access import PASSWORDS
from .db import database_url, migrate

ACCOUNTS = [
    ("dispatcher", "Диспетчер Анна", "dispatcher", None),
    ("dispatcher2", "Диспетчер Олег", "dispatcher", None),
    ("worker", "Исполнитель Максим", "worker", "Бригада Север"),
    ("worker2", "Исполнитель Мария", "worker", "Бригада Юг"),
    ("manager", "Руководитель Елена", "manager", None),
    ("admin", "Администратор Алексей", "admin", None),
]


def seed(load=False):
    password = os.environ["DP30_DEMO_PASSWORD"]
    if len(password) < 8:
        raise ValueError("DP30_DEMO_PASSWORD must contain at least 8 characters.")
    with psycopg.connect(database_url()) as db:
        db.execute("""INSERT INTO reference_entries(kind,code,title)
                      VALUES ('equipment','demo-pump','Насосное оборудование'),
                             ('equipment','demo-fan','Вентиляционное оборудование')
                      ON CONFLICT DO NOTHING""")
        by_name = {}
        for name in ("Бригада Север", "Бригада Юг", *(f"Бригада {i:02}" for i in range(3, 11))):
            db.execute("INSERT INTO brigades(name) VALUES (%s) ON CONFLICT DO NOTHING", (name,))
            by_name[name] = db.execute("SELECT id FROM brigades WHERE name=%s", (name,)).fetchone()[0]
        for login, label, role, brigade in ACCOUNTS:
            if not db.execute("SELECT 1 FROM users WHERE login=%s", (login,)).fetchone():
                db.execute("""INSERT INTO users(login,display_name,role,brigade_id,password_hash)
                              VALUES (%s,%s,%s,%s,%s)""",
                           (login, label, role, by_name.get(brigade), PASSWORDS.hash(password)))
        if load:
            if db.execute("SELECT count(*) FROM requests").fetchone()[0] != 0:
                raise ValueError("Для нагрузочного набора используйте отдельную пустую базу.")
            a = db.execute("SELECT id FROM users WHERE login='dispatcher'").fetchone()[0]
            db.execute("""INSERT INTO requests(address,description,priority,due_at)
                          SELECT 'Учебный адрес ' || i, 'Проверка оборудования', 'normal',
                                 now() + ((i % 30) - 15) * interval '1 day'
                          FROM generate_series(1,10000) AS i""")
            db.execute("""UPDATE requests SET status='ASSIGNED',version=2 WHERE id % 2=0""")
            db.execute("""INSERT INTO assignments(request_id,brigade_id,assigned_by)
                          SELECT id,%s,%s FROM requests WHERE status='ASSIGNED'""", (by_name["Бригада Север"], a))
            db.execute("""INSERT INTO request_events(request_id,actor_id,kind,status,version,operation_id)
                          SELECT id,%s,'CREATE','NEW',1,gen_random_uuid() FROM requests
                          UNION ALL SELECT id,%s,'ASSIGN','ASSIGNED',2,gen_random_uuid()
                          FROM requests WHERE status='ASSIGNED'""", (a, a))
        elif not db.execute("SELECT 1 FROM requests LIMIT 1").fetchone():
            actor = db.execute("SELECT id FROM users WHERE login='dispatcher'").fetchone()[0]
            for n, state in enumerate(("NEW", "ASSIGNED", "IN_WORK", "COMPLETED"), 1):
                result = "Вымышленное оборудование проверено. Неисправность устранена." if state == "COMPLETED" else None
                route = [("CREATE", "NEW"), ("ASSIGN", "ASSIGNED"), ("ACCEPT", "ACCEPTED"),
                         ("START", "IN_WORK"), ("COMPLETE", "COMPLETED")]
                version = 1 + [status for _, status in route].index(state)
                request = db.execute("""INSERT INTO requests(address,description,priority,due_at,status,version,result)
                                         VALUES (%s,%s,%s,now()+%s*interval '1 day',%s,%s,%s) RETURNING id""",
                                     (f"Учебная улица, {n * 10}", "Осмотр оборудования на учебной площадке",
                                      "high" if n == 3 else "normal", n - 2, state, version, result)).fetchone()[0]
                if version > 1:
                    db.execute("INSERT INTO assignments(request_id,brigade_id,assigned_by) VALUES (%s,%s,%s)",
                               (request, by_name["Бригада Север"], actor))
                worker = db.execute("SELECT id FROM users WHERE login='worker'").fetchone()[0]
                for i, (kind, status) in enumerate(route[:version], 1):
                    db.execute("""INSERT INTO request_events(request_id,actor_id,kind,status,version,operation_id,comment)
                                  VALUES (%s,%s,%s,%s,%s,gen_random_uuid(),%s)""",
                               (request, actor if i < 3 else worker, kind, status, i, result if i == 5 else None))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--load", action="store_true")
    args = parser.parse_args()
    migrate()
    seed(args.load)
