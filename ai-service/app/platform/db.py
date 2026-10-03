"""PostgreSQL access with row-level security.

Every transaction starts with
`select set_config('app.society_ids', ?, true), set_config('app.write_society_id', ?, true)`,
exactly like the Java `TenantAwareJpaTransactionManager`. The settings are transaction-local,
so a pooled connection never carries a tenant into the next transaction; with no tenant bound
both are empty and every tenant table returns zero rows.
"""

from __future__ import annotations

from contextlib import contextmanager
from typing import Iterator

import psycopg
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from app.platform import tenant as tenant_ctx
from app.platform.tenant import Tenant

_SET_TENANT = "select set_config('app.society_ids', %s, true), set_config('app.write_society_id', %s, true)"


class Database:
    def __init__(self, url: str, max_size: int = 10) -> None:
        self._pool = ConnectionPool(url, min_size=1, max_size=max_size, kwargs={"row_factory": dict_row}, open=False)

    def open(self) -> None:
        self._pool.open(wait=True, timeout=30)

    def close(self) -> None:
        self._pool.close()

    @contextmanager
    def tx(self, tenant: Tenant | None = None) -> Iterator[psycopg.Connection]:
        """One transaction bound to `tenant` (default: the current request/consumer tenant)."""
        t = tenant or tenant_ctx.current()
        readable = "{" + ",".join(str(s) for s in t.readable_society_ids) + "}"
        write = str(t.active_society_id) if t.active_society_id else ""
        with self._pool.connection() as conn:
            with conn.transaction():
                conn.execute(_SET_TENANT, (readable, write))
                yield conn


def vector_literal(values: list[float]) -> str:
    """pgvector text form, cast with `%s::vector` (no client-side adapter needed)."""
    return "[" + ",".join(f"{v:.6f}" for v in values) + "]"
