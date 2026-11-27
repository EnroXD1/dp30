import os
from pathlib import Path

import psycopg
from psycopg.rows import dict_row

ROOT = Path(__file__).resolve().parents[1]


def database_url():
    return os.environ["DP30_DATABASE_URL"]


def migrate():
    with psycopg.connect(database_url(), row_factory=dict_row) as db:
        db.execute("SELECT pg_advisory_xact_lock(303030)")
        db.execute("CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY)")
        for path in sorted((ROOT / "migrations").glob("*.sql")):
            if db.execute("SELECT 1 FROM schema_migrations WHERE name=%s", (path.name,)).fetchone():
                continue
            db.execute(path.read_text(encoding="utf-8"))
            db.execute("INSERT INTO schema_migrations VALUES (%s)", (path.name,))
