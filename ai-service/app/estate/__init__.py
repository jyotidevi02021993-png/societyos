"""Estate Health — builds a live read-model of estate signals from Kafka events and generates
AI-narrated (or rules-based) health summaries on demand.
"""

from __future__ import annotations

import json
import logging
import threading
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from fastapi import APIRouter, HTTPException, Request

from app.platform.db import Database
from app.platform.events import DomainEvent, publish
from app.platform.ids import uuid7
from app.platform.tenant import Tenant
from app.platform import tenant as tenant_ctx
from app.config import Settings
from app.llm import LLMGateway, AIDisabledError, TokenCapExceededError

log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Penalty table for health score
# ---------------------------------------------------------------------------

# Maps (kind, priority | None) → penalty points subtracted from 100
_PENALTIES: dict[tuple[str, str | None], int] = {
    ('CERT_EXPIRED', None):   15,
    ('CERT_EXPIRING', None):   5,
    ('COMPLAINT', 'P1'):      10,
    ('COMPLAINT', 'P2'):       5,
    ('COMPLAINT', 'P3'):       2,
    ('SLA_BREACH', None):      8,
    ('BREAKDOWN', None):      10,
    ('INCIDENT', None):       12,
    ('READING_ANOMALY', None): 4,
    ('CHECKLIST_FAILED', None):3,
    ('STOCK_LOW', None):       2,
}


# ---------------------------------------------------------------------------
# Dataclass
# ---------------------------------------------------------------------------


@dataclass
class HealthSummary:
    summary_id: uuid.UUID
    score: int
    status: str         # GOOD | WATCH | CRITICAL
    headline: str
    summary: str
    highlights: list[dict]
    metrics: dict
    method: str         # LLM | RULES


# ---------------------------------------------------------------------------
# Score computation (module-level for testability)
# ---------------------------------------------------------------------------


def compute_score(signals: list[dict]) -> tuple[int, str]:
    """Given a list of open-signal rows (kind, priority, cnt), return (score, status)."""
    total_penalty = 0
    for sig in signals:
        kind = sig.get('kind', '')
        priority = sig.get('priority')
        cnt = int(sig.get('cnt', sig.get('count', 1)))
        # Look up penalty with priority, then without
        penalty = _PENALTIES.get((kind, priority), _PENALTIES.get((kind, None), 0))
        total_penalty += penalty * cnt

    score = max(0, 100 - total_penalty)
    if score >= 70:
        status = 'GOOD'
    elif score >= 40:
        status = 'WATCH'
    else:
        status = 'CRITICAL'
    return score, status


# ---------------------------------------------------------------------------
# Estate Service
# ---------------------------------------------------------------------------


class EstateService:
    def __init__(self, settings: Settings, db: Database, llm: LLMGateway) -> None:
        self._settings = settings
        self._db = db
        self._llm = llm
        self._stop_event = threading.Event()
        self._consumer_task: Any = None
        self._signal_counter: int = 0

    # ------------------------------------------------------------------
    # Signal management
    # ------------------------------------------------------------------

    def upsert_signal(
        self,
        *,
        society_id: uuid.UUID,
        kind: str,
        ref_id: uuid.UUID,
        priority: str | None,
        label: str | None,
        occurred_at: datetime,
        resolved_at: datetime | None = None,
    ) -> None:
        with self._db.tx(Tenant.system(society_id)) as conn:
            conn.execute(
                """INSERT INTO estate_signal
                   (id, society_id, kind, ref_id, priority, label, occurred_at, resolved_at)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                   ON CONFLICT (society_id, kind, ref_id) DO UPDATE
                   SET priority    = EXCLUDED.priority,
                       label       = EXCLUDED.label,
                       resolved_at = EXCLUDED.resolved_at,
                       updated_at  = now()""",
                (uuid7(), society_id, kind, ref_id, priority, label, occurred_at, resolved_at),
            )

    def resolve_signal(self, *, society_id: uuid.UUID, kind: str, ref_id: uuid.UUID) -> None:
        with self._db.tx(Tenant.system(society_id)) as conn:
            conn.execute(
                """UPDATE estate_signal
                   SET resolved_at = now(), updated_at = now()
                   WHERE society_id = %s AND kind = %s AND ref_id = %s AND resolved_at IS NULL""",
                (society_id, kind, ref_id),
            )

    # ------------------------------------------------------------------
    # Summary generation
    # ------------------------------------------------------------------

    async def generate_summary(self, *, society_id: uuid.UUID) -> HealthSummary:
        # 1. Count open signals by kind and priority
        with self._db.tx(Tenant.system(society_id)) as conn:
            rows = conn.execute(
                """SELECT kind, priority, COUNT(*) AS cnt
                   FROM estate_signal
                   WHERE society_id = %s AND resolved_at IS NULL
                   GROUP BY kind, priority""",
                (society_id,),
            ).fetchall()

        signal_list = [dict(r) for r in rows]

        # 2. Compute score
        score, status = compute_score(signal_list)

        # 3. Build metrics dict
        metrics: dict[str, Any] = {}
        for sig in signal_list:
            key = f"{sig['kind']}.{sig['priority'] or 'all'}"
            metrics[key] = int(sig['cnt'])
        total_open = sum(int(s['cnt']) for s in signal_list)

        # 4. Build highlights (top 5 signal groups by penalty contribution)
        def _penalty_for(sig: dict) -> int:
            kind = sig.get('kind', '')
            priority = sig.get('priority')
            cnt = int(sig.get('cnt', 1))
            return _PENALTIES.get((kind, priority), _PENALTIES.get((kind, None), 0)) * cnt

        top_signals = sorted(signal_list, key=_penalty_for, reverse=True)[:5]
        highlights = [
            {
                'kind': s['kind'],
                'priority': s.get('priority'),
                'count': int(s['cnt']),
            }
            for s in top_signals
            if _penalty_for(s) > 0
        ]

        # 5. Try LLM narration
        method = 'RULES'
        headline = f'Estate score: {score}/100 ({status})'
        summary_text = f'{total_open} open issue{"s" if total_open != 1 else ""} detected.'

        try:
            prompt = (
                f'Estate health score: {score}/100 ({status}).\n'
                f'Open signals: {json.dumps(signal_list)}.\n'
                f'Total open: {total_open}.\n\n'
                f'Write a 2-sentence headline and summary for the estate health report.\n'
                f'Return ONLY valid JSON: {{"headline": "...", "summary": "...", "highlights": []}}'
            )
            llm_resp = await self._llm.complete(
                purpose='ESTATE_HEALTH',
                society_id=society_id,
                prompt=prompt,
            )
            raw = llm_resp.text.strip()
            if raw.startswith('```'):
                lines = raw.split('\n')
                raw = '\n'.join(lines[1:-1] if lines[-1].strip() == '```' else lines[1:])
            parsed = json.loads(raw)
            headline = parsed.get('headline', headline)
            summary_text = parsed.get('summary', summary_text)
            if parsed.get('highlights'):
                highlights = parsed['highlights']
            method = 'LLM'
        except (AIDisabledError, TokenCapExceededError, HTTPException):
            pass  # use rules fallback
        except Exception:
            log.warning('LLM estate health generation failed; using rules fallback', exc_info=True)

        # 6. Persist summary
        summary_id = uuid7()
        now = datetime.now(timezone.utc)
        with self._db.tx(Tenant.system(society_id)) as conn:
            conn.execute(
                """INSERT INTO estate_health_summary
                   (id, society_id, as_of, score, status, headline, summary, highlights, metrics, method)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s::jsonb, %s::jsonb, %s)""",
                (
                    summary_id, society_id, now,
                    score, status, headline, summary_text,
                    json.dumps(highlights), json.dumps(metrics), method,
                ),
            )
            publish(
                conn,
                DomainEvent(
                    type='ai.estate.health.summarised',
                    aggregate_id=society_id,
                    data={
                        'summaryId': str(summary_id),
                        'score': score,
                        'status': status,
                        'method': method,
                    },
                ),
            )

        return HealthSummary(
            summary_id=summary_id,
            score=score,
            status=status,
            headline=headline,
            summary=summary_text,
            highlights=highlights,
            metrics=metrics,
            method=method,
        )

    def get_latest_summary(self, *, society_id: uuid.UUID) -> HealthSummary | None:
        with self._db.tx(Tenant.system(society_id)) as conn:
            row = conn.execute(
                """SELECT * FROM estate_health_summary
                   WHERE society_id = %s
                   ORDER BY as_of DESC
                   LIMIT 1""",
                (society_id,),
            ).fetchone()
        if row is None:
            return None
        return HealthSummary(
            summary_id=row['id'],
            score=row['score'],
            status=row['status'],
            headline=row['headline'],
            summary=row['summary'],
            highlights=row.get('highlights') or [],
            metrics=row.get('metrics') or {},
            method=row['method'],
        )

    def list_signals(self, *, society_id: uuid.UUID, open_only: bool = True) -> list[dict]:
        where = 'WHERE society_id = %s' + (' AND resolved_at IS NULL' if open_only else '')
        with self._db.tx(Tenant.system(society_id)) as conn:
            rows = conn.execute(
                f'SELECT * FROM estate_signal {where} ORDER BY occurred_at DESC LIMIT 100',
                (society_id,),
            ).fetchall()
        return [dict(r) for r in rows]

    # ------------------------------------------------------------------
    # Kafka consumer
    # ------------------------------------------------------------------

    async def start_consumer(self) -> None:
        import asyncio
        self._stop_event.clear()
        loop = asyncio.get_event_loop()
        self._consumer_task = loop.run_in_executor(None, self._poll_loop)

    async def stop_consumer(self) -> None:
        self._stop_event.set()
        if self._consumer_task is not None:
            await self._consumer_task
            self._consumer_task = None

    def _poll_loop(self) -> None:
        try:
            from confluent_kafka import Consumer
        except ImportError:
            log.warning('confluent-kafka not available; estate consumer not started')
            return

        conf = {
            'bootstrap.servers': self._settings.kafka_bootstrap,
            'group.id': 'ai-estate',
            'auto.offset.reset': 'earliest',
            'enable.auto.commit': False,
        }
        consumer = Consumer(conf)
        topics = [
            'sos.ticket.events.v1',
            'sos.asset.events.v1',
            'sos.utility.events.v1',
            'sos.compliance.events.v1',
            'sos.workflow.events.v1',
        ]
        consumer.subscribe(topics)
        try:
            while not self._stop_event.is_set():
                msg = consumer.poll(1.0)
                if msg is None:
                    continue
                if msg.error():
                    log.error('Kafka error: %s', msg.error())
                    continue
                self._handle_message(msg)
                consumer.commit(message=msg)
        finally:
            consumer.close()

    def _handle_message(self, msg: Any) -> None:
        headers = {k: v.decode() if isinstance(v, bytes) else v for k, v in (msg.headers() or [])}
        ce_type = headers.get('ce_type', '')
        event_id = headers.get('ce_id', '')
        ce_society = headers.get('ce_societyid', '')
        if not ce_society:
            return

        try:
            society_id = uuid.UUID(ce_society)
        except ValueError:
            return

        try:
            payload = json.loads(msg.value())
        except Exception:
            return

        data = payload.get('data', payload)

        # Idempotency check
        with self._db.tx(Tenant.system(society_id)) as conn:
            result = conn.execute(
                "INSERT INTO inbox_event (consumer, event_id) VALUES (%s, %s) ON CONFLICT DO NOTHING",
                ('ai-estate', event_id),
            )
            if result.rowcount == 0:
                return

        now = datetime.now(timezone.utc)

        # Route by event type
        if ce_type == 'ticket.complaint.created':
            ref_id_str = data.get('complaintId') or data.get('id')
            if ref_id_str:
                self.upsert_signal(
                    society_id=society_id,
                    kind='COMPLAINT',
                    ref_id=uuid.UUID(ref_id_str),
                    priority=data.get('priority'),
                    label=data.get('category'),
                    occurred_at=now,
                )
                self._signal_counter += 1

        elif ce_type == 'ticket.complaint.resolved':
            ref_id_str = data.get('complaintId') or data.get('id')
            if ref_id_str:
                self.resolve_signal(
                    society_id=society_id,
                    kind='COMPLAINT',
                    ref_id=uuid.UUID(ref_id_str),
                )

        elif ce_type == 'ticket.breakdown.reported':
            ref_id_str = data.get('breakdownId') or data.get('id')
            if ref_id_str:
                self.upsert_signal(
                    society_id=society_id,
                    kind='BREAKDOWN',
                    ref_id=uuid.UUID(ref_id_str),
                    priority=data.get('priority'),
                    label=data.get('label') or data.get('description'),
                    occurred_at=now,
                )
                self._signal_counter += 1

        elif ce_type == 'workflow.sla.breached':
            ref_id_str = data.get('subjectId') or data.get('id')
            if ref_id_str:
                occurred_raw = data.get('occurredAt') or data.get('breachedAt')
                occurred = _parse_dt(occurred_raw) or now
                self.upsert_signal(
                    society_id=society_id,
                    kind='SLA_BREACH',
                    ref_id=uuid.UUID(ref_id_str),
                    priority=None,
                    label='SLA Breach',
                    occurred_at=occurred,
                )
                self._signal_counter += 1

        elif ce_type == 'compliance.certificate.expiring':
            ref_id_str = data.get('itemId') or data.get('id')
            if ref_id_str:
                self.upsert_signal(
                    society_id=society_id,
                    kind='CERT_EXPIRING',
                    ref_id=uuid.UUID(ref_id_str),
                    priority=None,
                    label=data.get('name'),
                    occurred_at=now,
                )
                self._signal_counter += 1

        elif ce_type == 'compliance.certificate.expired':
            ref_id_str = data.get('itemId') or data.get('id')
            if ref_id_str:
                self.upsert_signal(
                    society_id=society_id,
                    kind='CERT_EXPIRED',
                    ref_id=uuid.UUID(ref_id_str),
                    priority=None,
                    label=data.get('name'),
                    occurred_at=now,
                )
                self._signal_counter += 1


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _parse_dt(value: Any) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace('Z', '+00:00'))
    except ValueError:
        return None


# ---------------------------------------------------------------------------
# Router
# ---------------------------------------------------------------------------

router = APIRouter(prefix='/v1/estate', tags=['estate'])


def _estate_svc(request: Request) -> EstateService:
    return request.app.state.estate


@router.post('/{society_id}/health', summary='Generate an estate health summary')
async def generate_health(society_id: uuid.UUID, request: Request):
    svc: EstateService = _estate_svc(request)
    t = tenant_ctx.current()
    if not (t.has_role('ADMIN') or t.has_role('MANAGER') or t.has_role('SUPER_ADMIN')):
        raise HTTPException(status_code=403, detail={'type': 'about:blank', 'title': 'Forbidden', 'status': 403, 'detail': 'ADMIN or MANAGER role required'})
    result = await svc.generate_summary(society_id=society_id)
    return {
        'summary_id': str(result.summary_id),
        'score': result.score,
        'status': result.status,
        'headline': result.headline,
        'summary': result.summary,
        'highlights': result.highlights,
        'metrics': result.metrics,
        'method': result.method,
    }


@router.get('/{society_id}/health/latest', summary='Get the latest estate health summary')
def get_latest_health(society_id: uuid.UUID, request: Request):
    svc: EstateService = _estate_svc(request)
    result = svc.get_latest_summary(society_id=society_id)
    if result is None:
        raise HTTPException(status_code=404, detail='No health summary found')
    return {
        'summary_id': str(result.summary_id),
        'score': result.score,
        'status': result.status,
        'headline': result.headline,
        'summary': result.summary,
        'highlights': result.highlights,
        'metrics': result.metrics,
        'method': result.method,
    }


@router.get('/{society_id}/signals', summary='List open estate signals')
def list_signals(society_id: uuid.UUID, request: Request, open_only: bool = True):
    svc: EstateService = _estate_svc(request)
    signals = svc.list_signals(society_id=society_id, open_only=open_only)
    return signals
