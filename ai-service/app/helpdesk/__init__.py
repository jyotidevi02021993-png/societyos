"""Helpdesk RAG chatbot — receives a resident message, searches the knowledge base for context,
calls the LLM, stores the redacted conversation, and returns a ChatResponse with citations.
"""

from __future__ import annotations

import json
import logging
import threading
import uuid
from dataclasses import dataclass, field
from typing import Any

from fastapi import APIRouter, Request
from pydantic import BaseModel

from app.platform.db import Database
from app.platform.events import DomainEvent, publish
from app.platform.ids import uuid7
from app.platform import tenant as tenant_ctx
from app.platform.tenant import Tenant
from app.config import Settings
from app.llm import LLMGateway, redact_pii
from app.knowledge import KnowledgeService, ChunkResult

log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Dataclasses
# ---------------------------------------------------------------------------


@dataclass
class ChatResponse:
    conversation_id: uuid.UUID
    message_id: uuid.UUID
    content: str
    citations: list[dict]
    proposal: dict | None


@dataclass
class ConversationMessage:
    id: uuid.UUID
    society_id: uuid.UUID
    conversation_id: uuid.UUID
    role: str
    content_redacted: str
    citations: list[dict]
    proposal: dict | None
    created_at: Any


@dataclass
class ConversationWithMessages:
    id: uuid.UUID
    society_id: uuid.UUID
    user_id: uuid.UUID
    messages: list[ConversationMessage]
    created_at: Any


# ---------------------------------------------------------------------------
# Helpdesk Service
# ---------------------------------------------------------------------------


class HelpdeskService:
    def __init__(
        self,
        settings: Settings,
        db: Database,
        llm: LLMGateway,
        knowledge: KnowledgeService,
    ) -> None:
        self._settings = settings
        self._db = db
        self._llm = llm
        self._knowledge = knowledge
        self._stop_event = threading.Event()
        self._consumer_task: Any = None

    # ------------------------------------------------------------------
    # Core operations
    # ------------------------------------------------------------------

    async def chat(
        self,
        *,
        society_id: uuid.UUID,
        user_id: uuid.UUID,
        user_roles: list[str],
        tower_id: uuid.UUID | None,
        conversation_id: uuid.UUID | None,
        message: str,
    ) -> ChatResponse:
        """Process one helpdesk message.  Creates a conversation if none is given."""
        redacted_message = redact_pii(message)

        # Ensure conversation exists
        if conversation_id is None:
            conversation_id = self._create_conversation(society_id, user_id)

        # Retrieve last 6 messages for context
        history = self._get_recent_history(conversation_id, society_id)

        # Search KB for relevant chunks
        chunks: list[ChunkResult] = []
        try:
            chunks = self._knowledge.search(
                query=message,
                society_id=society_id,
                user_roles=user_roles,
                tower_id=tower_id,
                top_k=self._settings.rag_top_k,
            )
        except Exception:
            log.warning('KB search failed; proceeding without context', exc_info=True)

        # Build prompts
        system_prompt = _build_system_prompt(chunks)
        user_prompt = _build_user_prompt(message, history)

        # Call LLM
        llm_resp = await self._llm.complete(
            purpose='HELPDESK',
            society_id=society_id,
            prompt=user_prompt,
            system=system_prompt,
        )

        redacted_response = redact_pii(llm_resp.text)

        # Parse citations and proposal from response
        citations = [
            {
                'chunk_id': str(c.chunk_id),
                'document_id': str(c.document_id),
                'title': c.title,
                'score': c.score,
            }
            for c in chunks[:3]
        ]
        proposal = _extract_proposal(llm_resp.text)

        # Persist messages
        user_msg_id = uuid7()
        assistant_msg_id = uuid7()

        with self._db.tx(Tenant.system(society_id)) as conn:
            # User message
            conn.execute(
                """INSERT INTO conversation_message
                   (id, society_id, conversation_id, role, content_redacted, citations, proposal)
                   VALUES (%s, %s, %s, 'USER', %s, %s::jsonb, NULL)""",
                (user_msg_id, society_id, conversation_id, redacted_message, json.dumps([])),
            )
            # Assistant message
            conn.execute(
                """INSERT INTO conversation_message
                   (id, society_id, conversation_id, role, content_redacted, citations, proposal)
                   VALUES (%s, %s, %s, 'ASSISTANT', %s, %s::jsonb, %s::jsonb)""",
                (
                    assistant_msg_id, society_id, conversation_id,
                    redacted_response,
                    json.dumps(citations),
                    json.dumps(proposal) if proposal else None,
                ),
            )
            # Publish domain event
            publish(
                conn,
                DomainEvent(
                    type='ai.conversation.message.created',
                    aggregate_id=conversation_id,
                    data={
                        'conversationId': str(conversation_id),
                        'messageId': str(assistant_msg_id),
                        'role': 'ASSISTANT',
                    },
                ),
            )

        return ChatResponse(
            conversation_id=conversation_id,
            message_id=assistant_msg_id,
            content=redacted_response,
            citations=citations,
            proposal=proposal,
        )

    def get_history(
        self,
        *,
        society_id: uuid.UUID,
        conversation_id: uuid.UUID,
        limit: int = 20,
    ) -> list[dict]:
        with self._db.tx(Tenant.system(society_id)) as conn:
            rows = conn.execute(
                """SELECT id, role, content_redacted, citations, proposal, created_at
                   FROM conversation_message
                   WHERE conversation_id = %s
                   ORDER BY created_at
                   LIMIT %s""",
                (conversation_id, limit),
            ).fetchall()
        return [dict(r) for r in rows]

    def get_conversation(
        self,
        *,
        society_id: uuid.UUID,
        conversation_id: uuid.UUID,
    ) -> ConversationWithMessages | None:
        with self._db.tx(Tenant.system(society_id)) as conn:
            conv_row = conn.execute(
                "SELECT * FROM conversation WHERE id = %s",
                (conversation_id,),
            ).fetchone()
            if conv_row is None:
                return None
            msg_rows = conn.execute(
                """SELECT * FROM conversation_message
                   WHERE conversation_id = %s
                   ORDER BY created_at""",
                (conversation_id,),
            ).fetchall()

        messages = [
            ConversationMessage(
                id=r['id'],
                society_id=r['society_id'],
                conversation_id=r['conversation_id'],
                role=r['role'],
                content_redacted=r['content_redacted'],
                citations=r.get('citations') or [],
                proposal=r.get('proposal'),
                created_at=r.get('created_at'),
            )
            for r in msg_rows
        ]
        return ConversationWithMessages(
            id=conv_row['id'],
            society_id=conv_row['society_id'],
            user_id=conv_row['user_id'],
            messages=messages,
            created_at=conv_row.get('created_at'),
        )

    def list_conversations(self, *, society_id: uuid.UUID, user_id: uuid.UUID) -> list[dict]:
        with self._db.tx(Tenant.system(society_id)) as conn:
            rows = conn.execute(
                """SELECT id, user_id, created_at
                   FROM conversation
                   WHERE user_id = %s
                   ORDER BY created_at DESC
                   LIMIT 50""",
                (user_id,),
            ).fetchall()
        return [dict(r) for r in rows]

    # ------------------------------------------------------------------
    # Kafka consumer (idempotency only for now)
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
            log.warning('confluent-kafka not available; helpdesk consumer not started')
            return

        conf = {
            'bootstrap.servers': self._settings.kafka_bootstrap,
            'group.id': 'ai-helpdesk',
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

        # Idempotency only (no further action for now)
        with self._db.tx(Tenant.system(society_id)) as conn:
            conn.execute(
                "INSERT INTO inbox_event (consumer, event_id) VALUES (%s, %s) ON CONFLICT DO NOTHING",
                ('ai-helpdesk', event_id),
            )

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _create_conversation(self, society_id: uuid.UUID, user_id: uuid.UUID) -> uuid.UUID:
        conv_id = uuid7()
        with self._db.tx(Tenant.system(society_id)) as conn:
            conn.execute(
                "INSERT INTO conversation (id, society_id, user_id) VALUES (%s, %s, %s)",
                (conv_id, society_id, user_id),
            )
        return conv_id

    def _get_recent_history(self, conversation_id: uuid.UUID, society_id: uuid.UUID) -> list[dict]:
        """Fetch last 6 messages for context building.

        Uses Tenant.system(society_id) so RLS on conversation_message resolves correctly.
        Tenant.platform() would set app.society_ids='{}' and silently return zero rows,
        causing every response to be generated without any prior history (finding: history-rls-gap).
        """
        try:
            with self._db.tx(Tenant.system(society_id)) as conn:
                rows = conn.execute(
                    """SELECT role, content_redacted FROM conversation_message
                       WHERE conversation_id = %s
                       ORDER BY created_at DESC
                       LIMIT 6""",
                    (conversation_id,),
                ).fetchall()
            return list(reversed([dict(r) for r in rows]))
        except Exception:
            return []


# ---------------------------------------------------------------------------
# Prompt builders
# ---------------------------------------------------------------------------


def _build_system_prompt(chunks: list[ChunkResult]) -> str:
    lines = [
        'You are a helpful assistant for a residential society management system.',
        'Answer questions about society rules, notices, and procedures.',
        'Be concise, accurate, and professional.',
        '',
    ]
    if chunks:
        lines.append('Relevant knowledge base context:')
        for i, c in enumerate(chunks[:5], 1):
            lines.append(f'{i}. [{c.title}]: {c.content[:300]}')
    return '\n'.join(lines)


def _build_user_prompt(message: str, history: list[dict]) -> str:
    parts: list[str] = []
    for h in history[-4:]:
        role = h.get('role', 'USER').capitalize()
        parts.append(f'{role}: {h.get("content_redacted", "")}')
    parts.append(f'User: {message}')
    return '\n'.join(parts)


def _extract_proposal(text: str) -> dict | None:
    """If the LLM response includes a JSON {"action":...} block, extract it as a proposal."""
    try:
        data = json.loads(text)
        if isinstance(data, dict) and 'action' in data:
            return data
    except (json.JSONDecodeError, ValueError):
        pass
    return None


# ---------------------------------------------------------------------------
# Router
# ---------------------------------------------------------------------------

router = APIRouter(prefix='/v1/helpdesk', tags=['helpdesk'])


class _MessageIn(BaseModel):
    message: str
    conversation_id: uuid.UUID | None = None


def _helpdesk_svc(request: Request) -> HelpdeskService:
    return request.app.state.helpdesk


@router.post('/conversations', summary='Start a new helpdesk conversation or continue one')
async def post_conversation(body: _MessageIn, request: Request):
    svc: HelpdeskService = _helpdesk_svc(request)
    t = tenant_ctx.current()
    society_id = t.require_active_society()
    user_id = t.user_id or uuid.UUID(int=0)
    result = await svc.chat(
        society_id=society_id,
        user_id=user_id,
        user_roles=list(t.roles),
        tower_id=None,
        conversation_id=body.conversation_id,
        message=body.message,
    )
    return {
        'conversation_id': str(result.conversation_id),
        'message_id': str(result.message_id),
        'content': result.content,
        'citations': result.citations,
        'proposal': result.proposal,
    }


@router.post('/conversations/{conv_id}/messages', summary='Send a message to an existing conversation')
async def post_message(conv_id: uuid.UUID, body: _MessageIn, request: Request):
    svc: HelpdeskService = _helpdesk_svc(request)
    t = tenant_ctx.current()
    society_id = t.require_active_society()
    user_id = t.user_id or uuid.UUID(int=0)
    result = await svc.chat(
        society_id=society_id,
        user_id=user_id,
        user_roles=list(t.roles),
        tower_id=None,
        conversation_id=conv_id,
        message=body.message,
    )
    return {
        'conversation_id': str(result.conversation_id),
        'message_id': str(result.message_id),
        'content': result.content,
        'citations': result.citations,
        'proposal': result.proposal,
    }


@router.get('/conversations/{conv_id}/messages', summary='Get message history for a conversation')
def get_messages(conv_id: uuid.UUID, request: Request):
    svc: HelpdeskService = _helpdesk_svc(request)
    t = tenant_ctx.current()
    society_id = t.require_active_society()
    history = svc.get_history(society_id=society_id, conversation_id=conv_id)
    return history
