"""Domain events through the transactional outbox.

`publish(conn, event)` writes one `outbox_event` row in the caller's transaction, with the same
CloudEvents 1.0 envelope the Java `DomainEvents.publish` writes (specversion, id, source, type,
dataschema, time, subject, societyid, actorid, actortype, data). Debezium's Outbox Event Router,
or the polling relay (`SOS_OUTBOX_RELAY=polling`), moves it to `sos.<context>.events.v1` with
key = aggregate id and headers `ce_id`, `ce_type`, `ce_societyid`.

Event data is camelCase JSON and carries ids, never PII.
"""

from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

import psycopg

from app.platform import tenant as tenant_ctx
from app.platform.ids import uuid7

SCHEMA_BASE = "https://schemas.societyos.in/"
CONTEXT = "ai"
SOURCE = "ai-service"

_INSERT = """
insert into outbox_event (id, aggregate_type, aggregate_id, type, society_id, payload, created_at)
values (%s, %s, %s, %s, %s, %s::jsonb, %s)
"""


@dataclass(frozen=True)
class DomainEvent:
    """`type` is `<context>.<entity>.<past-tense-verb>`; `data` is the catalogue payload."""

    type: str
    aggregate_id: uuid.UUID
    data: dict[str, Any]
    context: str = CONTEXT
    schema_version: int = 1
    subject: str | None = field(default=None)

    def resolved_subject(self) -> str:
        if self.subject:
            return self.subject
        parts = self.type.split(".")
        entity = parts[1] if len(parts) > 1 else parts[0]
        return f"{entity}/{self.aggregate_id}"


def _iso(ts: datetime) -> str:
    return ts.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def envelope(event: DomainEvent, event_id: uuid.UUID, society_id: uuid.UUID | None, now: datetime) -> dict:
    t = tenant_ctx.current()
    return {
        "specversion": "1.0",
        "id": str(event_id),
        "source": SOURCE,
        "type": event.type,
        "dataschema": f"{SCHEMA_BASE}{event.context}/{event.type}/{event.schema_version}.json",
        "time": _iso(now),
        "subject": event.resolved_subject(),
        "societyid": str(society_id) if society_id else None,
        "actorid": str(t.user_id) if t.user_id else None,
        "actortype": t.actor_type.value,
        "data": event.data,
    }


def publish(conn: psycopg.Connection, event: DomainEvent) -> uuid.UUID:
    """Writes the event to the outbox of the active society. `conn` must be inside a transaction."""
    if conn.info.transaction_status == psycopg.pq.TransactionStatus.IDLE:
        raise RuntimeError(f"events.publish must run inside a transaction: {event.type}")
    society_id = tenant_ctx.current().active_society_id
    event_id = uuid7()
    now = datetime.now(timezone.utc)
    payload = envelope(event, event_id, society_id, now)
    conn.execute(
        _INSERT,
        (event_id, event.context, event.aggregate_id, event.type, society_id, json.dumps(payload), now),
    )
    return event_id


def topic_for(context: str) -> str:
    return f"sos.{context}.events.v1"
