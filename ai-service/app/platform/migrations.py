"""Minimal Flyway-style migration runner: applies `migrations/V*.sql` in version order, once.

Runs as the owner role (`DB_OWNER_URL`), in production as a Kubernetes Job before rollout
(`python -m app.platform.migrations`); locally the app runs it at startup.
"""

from __future__ import annotations

import hashlib
import logging
import re
from pathlib import Path

import psycopg

log = logging.getLogger(__name__)

MIGRATIONS_DIR = Path(__file__).resolve().parents[2] / "migrations"
_NAME = re.compile(r"^V(\d+(?:_\d+)*)__(.+)\.sql$")


def _version_key(name: str) -> tuple[int, ...]:
    m = _NAME.match(name)
    if not m:
        raise ValueError(f"Bad migration file name: {name}")
    return tuple(int(p) for p in m.group(1).split("_"))


def pending(applied: set[str]) -> list[Path]:
    files = [p for p in MIGRATIONS_DIR.glob("V*.sql")]
    files.sort(key=lambda p: _version_key(p.name))
    return [p for p in files if p.name not in applied]


def migrate(owner_url: str) -> list[str]:
    done: list[str] = []
    with psycopg.connect(owner_url, autocommit=True) as conn:
        conn.execute(
            """create table if not exists schema_migrations (
                 name text primary key, checksum text not null, applied_at timestamptz not null default now())"""
        )
        # one migrator at a time across replicas
        conn.execute("select pg_advisory_lock(8097)")
        try:
            applied = {r[0] for r in conn.execute("select name from schema_migrations")}
            for path in pending(applied):
                sql = path.read_text(encoding="utf-8")
                with conn.transaction():
                    conn.execute(sql)
                    conn.execute(
                        "insert into schema_migrations (name, checksum) values (%s, %s)",
                        (path.name, hashlib.sha256(sql.encode()).hexdigest()),
                    )
                log.info("Applied migration %s", path.name)
                done.append(path.name)
        finally:
            conn.execute("select pg_advisory_unlock(8097)")
    return done


if __name__ == "__main__":  # pragma: no cover
    from app.config import get_settings

    logging.basicConfig(level=logging.INFO)
    print(migrate(get_settings().db_owner_url))
