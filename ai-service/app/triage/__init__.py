"""Complaint Triage — classifies complaint text using LLM (primary) or keyword rules (fallback),
writes a triage_suggestion row, and publishes ai.classification.suggested.
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
from pydantic import BaseModel

from app.platform.db import Database
from app.platform.events import DomainEvent, publish
from app.platform.ids import uuid7
from app.platform.tenant import Tenant
from app.platform import tenant as tenant_ctx
from app.config import Settings
from app.llm import LLMGateway, AIDisabledError, TokenCapExceededError

log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Default category taxonomy
# ---------------------------------------------------------------------------

_DEFAULT_CATEGORIES: list[dict] = [
    {
        'name': 'Plumbing',
        'department': 'Maintenance',
        'priority': 'P2',
        'keywords': ['pipe', 'leak', 'water', 'drain', 'tap', 'toilet', 'plumbing'],
    },
    {
        'name': 'Electrical',
        'department': 'Maintenance',
        'priority': 'P2',
        'keywords': ['power', 'light', 'electricity', 'switch', 'fan', 'mcb', 'electric', 'fuse', 'wiring'],
    },
    {
        'name': 'Lift/Elevator',
        'department': 'Maintenance',
        'priority': 'P1',
        'keywords': ['lift', 'elevator', 'stuck', 'floor'],
    },
    {
        'name': 'Security',
        'department': 'Security',
        'priority': 'P1',
        'keywords': ['theft', 'break', 'intruder', 'security', 'guard', 'cctv', 'trespass', 'suspicious'],
    },
    {
        'name': 'Housekeeping',
        'department': 'Operations',
        'priority': 'P3',
        'keywords': ['clean', 'dirty', 'garbage', 'sweep', 'mop', 'trash', 'waste'],
    },
    {
        'name': 'Common Area',
        'department': 'Operations',
        'priority': 'P3',
        'keywords': ['corridor', 'lobby', 'parking', 'terrace', 'gym'],
    },
    {
        'name': 'Water Supply',
        'department': 'Maintenance',
        'priority': 'P2',
        'keywords': ['water', 'supply', 'tank', 'pump', 'shortage'],
    },
    {
        'name': 'Billing',
        'department': 'Finance',
        'priority': 'P3',
        'keywords': ['bill', 'payment', 'charge', 'invoice', 'maintenance fee'],
    },
    {
        'name': 'Noise Complaint',
        'department': 'Community',
        'priority': 'P4',
        'keywords': ['noise', 'loud', 'music', 'party', 'disturb'],
    },
    {
        'name': 'Internet/Intercom',
        'department': 'IT',
        'priority': 'P3',
        'keywords': ['internet', 'wifi', 'intercom', 'broadband'],
    },
    {
        'name': 'General',
        'department': 'Admin',
        'priority': 'P3',
        'keywords': [],
    },
]


# ---------------------------------------------------------------------------
# Dataclass
# ---------------------------------------------------------------------------


@dataclass
class TriageResult:
    suggestion_id: uuid.UUID
    category_name: str
    department: str
    priority: str       # P1 | P2 | P3 | P4
    confidence: float
    method: str         # LLM | RULES


# ---------------------------------------------------------------------------
# Triage Service
# ---------------------------------------------------------------------------


class TriageService:
    def __init__(self, settings: Settings, db: Database, llm: LLMGateway) -> None:
        self._settings = settings
        self._db = db
        self._llm = llm
        self._stop_event = threading.Event()
        self._consumer_task: Any = None

    # ------------------------------------------------------------------
    # Core triage
    # ------------------------------------------------------------------

    async def triage_complaint(
        self,
        *,
        society_id: uuid.UUID,
        complaint_id: uuid.UUID,
        text: str,
    ) -> TriageResult:
        """Classify complaint text → write triage_suggestion → publish event."""
        # 1. Load society categories
        categories = self._load_categories(society_id)
        if not categories:
            categories = list(_DEFAULT_CATEGORIES)

        # 2. Try LLM triage first
        method = 'RULES'
        triage: dict | None = None
        try:
            triage = await self._llm_triage(society_id, text, categories)
            if triage and triage.get('confidence', 0) >= 0.4:
                method = 'LLM'
            else:
                triage = None  # fall through to rules
        except (AIDisabledError, TokenCapExceededError, HTTPException, ValueError, Exception):
            triage = None  # fall through to rules

        if triage is None:
            triage = self._rules_triage(text, categories)
            method = 'RULES'

        category_name = triage['category']
        department = triage['department']
        priority = triage['priority']
        confidence = float(triage.get('confidence', 1.0 if method == 'RULES' else 0.85))

        # 3. Upsert triage_suggestion
        suggestion_id = uuid7()
        with self._db.tx(Tenant.system(society_id)) as conn:
            conn.execute(
                """INSERT INTO triage_suggestion
                   (id, society_id, complaint_id, category_name, department, priority, confidence, method)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                   ON CONFLICT (society_id, complaint_id) DO UPDATE
                   SET category_name = EXCLUDED.category_name,
                       department    = EXCLUDED.department,
                       priority      = EXCLUDED.priority,
                       confidence    = EXCLUDED.confidence,
                       method        = EXCLUDED.method,
                       updated_at    = now()
                   RETURNING id""",
                (suggestion_id, society_id, complaint_id,
                 category_name, department, priority, confidence, method),
            )

            # 4. Publish if confidence above threshold
            if confidence >= self._settings.triage_auto_apply_threshold:
                publish(
                    conn,
                    DomainEvent(
                        type='ai.classification.suggested',
                        aggregate_id=complaint_id,
                        data={
                            'complaintId': str(complaint_id),
                            'categoryName': category_name,
                            'department': department,
                            'priority': priority,
                            'confidence': confidence,
                            'method': method,
                        },
                    ),
                )

        return TriageResult(
            suggestion_id=suggestion_id,
            category_name=category_name,
            department=department,
            priority=priority,
            confidence=confidence,
            method=method,
        )

    def get_suggestion(
        self,
        *,
        society_id: uuid.UUID,
        complaint_id: uuid.UUID,
    ) -> TriageResult | None:
        with self._db.tx(Tenant.system(society_id)) as conn:
            row = conn.execute(
                "SELECT * FROM triage_suggestion WHERE society_id = %s AND complaint_id = %s",
                (society_id, complaint_id),
            ).fetchone()
        if row is None:
            return None
        return TriageResult(
            suggestion_id=row['id'],
            category_name=row['category_name'],
            department=row['department'],
            priority=row['priority'],
            confidence=float(row['confidence']),
            method=row['method'],
        )

    def get_categories(self, *, society_id: uuid.UUID) -> list[dict]:
        cats = self._load_categories(society_id)
        return cats if cats else list(_DEFAULT_CATEGORIES)

    def create_category(self, *, society_id: uuid.UUID, data: dict) -> uuid.UUID:
        cat_id = uuid7()
        with self._db.tx(Tenant.system(society_id)) as conn:
            conn.execute(
                """INSERT INTO triage_category
                   (id, society_id, name, department, default_priority, keywords)
                   VALUES (%s, %s, %s, %s, %s, %s)""",
                (
                    cat_id, society_id,
                    data['name'], data['department'],
                    data.get('priority', 'P3'),
                    data.get('keywords', []),
                ),
            )
        return cat_id

    # ------------------------------------------------------------------
    # LLM triage
    # ------------------------------------------------------------------

    async def _llm_triage(
        self,
        society_id: uuid.UUID,
        text: str,
        categories: list[dict],
    ) -> dict:
        cat_list = '\n'.join(
            f"- {c['name']} ({c['department']}, {c.get('priority', 'P3')})"
            for c in categories
        )
        prompt = (
            f'Classify the following resident complaint into one of these categories:\n'
            f'{cat_list}\n\n'
            f'Complaint: {text}\n\n'
            f'Return ONLY valid JSON in this exact format:\n'
            f'{{"category": "<name>", "department": "<department>", "priority": "<P1|P2|P3|P4>", "confidence": <0.0-1.0>}}'
        )
        resp = await self._llm.complete(
            purpose='TRIAGE',
            society_id=society_id,
            prompt=prompt,
        )
        # Extract JSON from response (may be wrapped in markdown code blocks)
        raw = resp.text.strip()
        if raw.startswith('```'):
            lines = raw.split('\n')
            raw = '\n'.join(lines[1:-1] if lines[-1].strip() == '```' else lines[1:])

        data = json.loads(raw)
        # Validate fields
        if not all(k in data for k in ('category', 'department', 'priority', 'confidence')):
            raise ValueError(f'LLM response missing required fields: {raw}')
        if data['priority'] not in ('P1', 'P2', 'P3', 'P4'):
            raise ValueError(f'Invalid priority: {data["priority"]}')
        return data

    # ------------------------------------------------------------------
    # Rules-based fallback
    # ------------------------------------------------------------------

    def _rules_triage(self, text: str, categories: list[dict]) -> dict:
        lower_text = text.lower()
        best_score = -1
        best_cat: dict = categories[-1]  # default to last (typically General)

        for cat in categories:
            keywords = cat.get('keywords') or []
            score = sum(1 for kw in keywords if kw.lower() in lower_text)
            if score > best_score:
                best_score = score
                best_cat = cat

        return {
            'category': best_cat['name'],
            'department': best_cat['department'],
            'priority': best_cat.get('priority', best_cat.get('default_priority', 'P3')),
            'confidence': min(0.5 + best_score * 0.1, 0.95) if best_score > 0 else 0.3,
        }

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _load_categories(self, society_id: uuid.UUID) -> list[dict]:
        with self._db.tx(Tenant.system(society_id)) as conn:
            rows = conn.execute(
                "SELECT name, department, default_priority AS priority, keywords FROM triage_category WHERE society_id = %s",
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
            log.warning('confluent-kafka not available; triage consumer not started')
            return

        conf = {
            'bootstrap.servers': self._settings.kafka_bootstrap,
            'group.id': 'ai-triage',
            'auto.offset.reset': 'earliest',
            'enable.auto.commit': False,
        }
        consumer = Consumer(conf)
        consumer.subscribe(['sos.ticket.events.v1'])
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
        import asyncio

        headers = {k: v.decode() if isinstance(v, bytes) else v for k, v in (msg.headers() or [])}
        ce_type = headers.get('ce_type', '')
        if ce_type != 'ticket.complaint.created':
            return

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
        complaint_id_str = data.get('complaintId') or data.get('id')
        complaint_text = data.get('description') or data.get('text', '')
        if not complaint_id_str:
            return

        try:
            complaint_id = uuid.UUID(complaint_id_str)
        except ValueError:
            return

        # Run triage in the thread's event loop context.
        # The LLM call (network I/O) must happen OUTSIDE the DB transaction, so we
        # perform it first.  The idempotency INSERT (inbox_event) and the
        # triage_suggestion upsert then land in the SAME transaction — matching the
        # pattern used by knowledge and estate consumers — so a crash between them
        # cannot permanently mark an event as processed while skipping the suggestion.
        try:
            loop = asyncio.new_event_loop()
            loop.run_until_complete(
                self._triage_with_idempotency(
                    society_id=society_id,
                    complaint_id=complaint_id,
                    text=complaint_text,
                    event_id=event_id,
                )
            )
        except Exception:
            log.exception('Triage failed for complaint %s', complaint_id_str)
        finally:
            loop.close()

    async def _triage_with_idempotency(
        self,
        *,
        society_id: uuid.UUID,
        complaint_id: uuid.UUID,
        text: str,
        event_id: str,
    ) -> None:
        """Run LLM triage (outside DB tx), then atomically write inbox_event +
        triage_suggestion in a single transaction.  If the inbox INSERT is a duplicate
        (conflict → rowcount 0) the whole transaction rolls back and we skip the event.
        """
        # 1. Load categories and run LLM (outside any transaction)
        categories = self._load_categories(society_id)
        if not categories:
            categories = list(_DEFAULT_CATEGORIES)

        method = 'RULES'
        triage: dict | None = None
        try:
            triage = await self._llm_triage(society_id, text, categories)
            if triage and triage.get('confidence', 0) >= 0.4:
                method = 'LLM'
            else:
                triage = None
        except Exception:
            triage = None

        if triage is None:
            triage = self._rules_triage(text, categories)
            method = 'RULES'

        category_name = triage['category']
        department = triage['department']
        priority = triage['priority']
        confidence = float(triage.get('confidence', 1.0 if method == 'RULES' else 0.85))
        suggestion_id = uuid7()

        # 2. Single transaction: idempotency check + upsert + event publish
        with self._db.tx(Tenant.system(society_id)) as conn:
            result = conn.execute(
                "INSERT INTO inbox_event (consumer, event_id) VALUES (%s, %s) ON CONFLICT DO NOTHING",
                ('ai-triage', event_id),
            )
            if result.rowcount == 0:
                return  # already processed — skip everything

            conn.execute(
                """INSERT INTO triage_suggestion
                   (id, society_id, complaint_id, category_name, department, priority, confidence, method)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                   ON CONFLICT (society_id, complaint_id) DO UPDATE
                   SET category_name = EXCLUDED.category_name,
                       department    = EXCLUDED.department,
                       priority      = EXCLUDED.priority,
                       confidence    = EXCLUDED.confidence,
                       method        = EXCLUDED.method,
                       updated_at    = now()""",
                (suggestion_id, society_id, complaint_id,
                 category_name, department, priority, confidence, method),
            )

            if confidence >= self._settings.triage_auto_apply_threshold:
                publish(
                    conn,
                    DomainEvent(
                        type='ai.classification.suggested',
                        aggregate_id=complaint_id,
                        data={
                            'complaintId': str(complaint_id),
                            'categoryName': category_name,
                            'department': department,
                            'priority': priority,
                            'confidence': confidence,
                            'method': method,
                        },
                    ),
                )


# ---------------------------------------------------------------------------
# Router
# ---------------------------------------------------------------------------

router = APIRouter(prefix='/v1/triage', tags=['triage'])


class _TriageIn(BaseModel):
    text: str


class _CategoryIn(BaseModel):
    name: str
    department: str
    priority: str = 'P3'
    keywords: list[str] = []


def _triage_svc(request: Request) -> TriageService:
    return request.app.state.triage


@router.post('/complaints/{complaint_id}', summary='Manually trigger triage for a complaint')
async def post_triage(complaint_id: uuid.UUID, body: _TriageIn, request: Request):
    svc: TriageService = _triage_svc(request)
    t = tenant_ctx.current()
    society_id = t.require_active_society()
    result = await svc.triage_complaint(
        society_id=society_id,
        complaint_id=complaint_id,
        text=body.text,
    )
    return {
        'suggestion_id': str(result.suggestion_id),
        'category_name': result.category_name,
        'department': result.department,
        'priority': result.priority,
        'confidence': result.confidence,
        'method': result.method,
    }


@router.get('/complaints/{complaint_id}', summary='Get triage suggestion for a complaint')
def get_triage(complaint_id: uuid.UUID, request: Request):
    svc: TriageService = _triage_svc(request)
    t = tenant_ctx.current()
    society_id = t.require_active_society()
    result = svc.get_suggestion(society_id=society_id, complaint_id=complaint_id)
    if result is None:
        raise HTTPException(status_code=404, detail='No triage suggestion found')
    return {
        'suggestion_id': str(result.suggestion_id),
        'category_name': result.category_name,
        'department': result.department,
        'priority': result.priority,
        'confidence': result.confidence,
        'method': result.method,
    }


@router.get('/categories', summary='List triage categories for the active society')
def get_categories(request: Request):
    svc: TriageService = _triage_svc(request)
    t = tenant_ctx.current()
    society_id = t.require_active_society()
    return svc.get_categories(society_id=society_id)


@router.post('/categories', summary='Create a custom triage category')
def post_category(body: _CategoryIn, request: Request):
    svc: TriageService = _triage_svc(request)
    t = tenant_ctx.current()
    if not (t.has_role('ADMIN') or t.has_role('MANAGER') or t.has_role('SUPER_ADMIN')):
        raise HTTPException(status_code=403, detail={'type': 'about:blank', 'title': 'Forbidden', 'status': 403, 'detail': 'ADMIN or MANAGER role required'})
    society_id = t.require_active_society()
    cat_id = svc.create_category(
        society_id=society_id,
        data={'name': body.name, 'department': body.department, 'priority': body.priority, 'keywords': body.keywords},
    )
    return {'id': str(cat_id), 'name': body.name, 'department': body.department}
