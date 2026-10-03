"""Knowledge Base — document ingestion (chunking + embedding), vector search, and a Kafka
consumer for community.notice.published events.

Embedding providers:
  hashing   — deterministic 384-dim vector (no model download, good for tests/dev)
  sentence-transformers — uses sentence_transformers package if available
"""

from __future__ import annotations

import hashlib
import logging
import math
import threading
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from app.platform.db import Database, vector_literal
from app.platform.ids import uuid7
from app.platform import tenant as tenant_ctx
from app.platform.tenant import Tenant
from app.config import Settings, EMBEDDING_DIM

log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Dataclasses
# ---------------------------------------------------------------------------


@dataclass
class KBDocument:
    id: uuid.UUID
    society_id: uuid.UUID
    source_type: str
    source_id: uuid.UUID | None
    title: str
    audience_roles: list[str]
    audience_tower_ids: list[uuid.UUID]
    status: str
    created_at: Any


@dataclass
class ChunkResult:
    chunk_id: uuid.UUID
    document_id: uuid.UUID
    title: str
    content: str
    score: float


# ---------------------------------------------------------------------------
# Embedding providers
# ---------------------------------------------------------------------------


class EmbeddingProvider(ABC):
    @abstractmethod
    def embed(self, text: str) -> list[float]:
        """Return EMBEDDING_DIM floats representing the text."""


class HashingEmbeddings(EmbeddingProvider):
    """Deterministic 384-dim embedding using SHA-256 — no model download needed."""

    def embed(self, text: str) -> list[float]:
        raw = text.encode('utf-8')
        # Produce enough bytes by hashing the text repeatedly with a counter
        needed = EMBEDDING_DIM * 4  # 4 bytes per float
        source_bytes = b''
        counter = 0
        while len(source_bytes) < needed:
            h = hashlib.sha256(raw + counter.to_bytes(4, 'big')).digest()
            source_bytes += h
            counter += 1
        # Interpret each group of 4 bytes as a signed int32, normalise
        floats: list[float] = []
        for i in range(EMBEDDING_DIM):
            chunk_bytes = source_bytes[i * 4: i * 4 + 4]
            val = int.from_bytes(chunk_bytes, 'big', signed=True)
            floats.append(val / (2 ** 31 - 1))
        # Normalise to unit vector
        norm = math.sqrt(sum(v * v for v in floats)) or 1.0
        return [v / norm for v in floats]


class SentenceTransformerEmbeddings(EmbeddingProvider):
    def __init__(self, model_name: str) -> None:
        self._model_name = model_name
        self._model: Any = None

    def _get_model(self) -> Any:
        if self._model is None:
            from sentence_transformers import SentenceTransformer  # type: ignore
            self._model = SentenceTransformer(self._model_name)
        return self._model

    def embed(self, text: str) -> list[float]:
        model = self._get_model()
        vec = model.encode(text, normalize_embeddings=True)
        return vec.tolist()


def get_embedding_provider(settings: Settings) -> EmbeddingProvider:
    if settings.embeddings_provider == 'sentence-transformers':
        try:
            return SentenceTransformerEmbeddings(settings.embeddings_model)
        except ImportError:
            log.warning('sentence-transformers not installed; falling back to hashing embeddings')
    return HashingEmbeddings()


# ---------------------------------------------------------------------------
# Module-level helpers (used by tests and KnowledgeService)
# ---------------------------------------------------------------------------


def embed(text: str, settings: Settings) -> list[float]:
    provider = get_embedding_provider(settings)
    return provider.embed(text)


def chunk_text(text: str, chunk_chars: int) -> list[str]:
    """Split *text* into overlapping chunks of at most *chunk_chars* characters.

    Splits prefer sentence boundaries ('. ' or newlines).  Overlap is ~100 chars so
    context is preserved across chunk boundaries.
    """
    if not text:
        return []
    overlap = min(100, chunk_chars // 10)
    chunks: list[str] = []
    start = 0
    while start < len(text):
        end = min(start + chunk_chars, len(text))
        if end < len(text):
            # Try to split on a sentence boundary near the end
            boundary = -1
            for sep in ('. ', '\n\n', '\n'):
                idx = text.rfind(sep, start, end)
                if idx > start + overlap:
                    boundary = idx + len(sep)
                    break
            if boundary > start + overlap:
                end = boundary
        chunk = text[start:end].strip()
        if chunk:
            chunks.append(chunk)
        start = end - overlap if end < len(text) else len(text)
    return chunks


# ---------------------------------------------------------------------------
# Knowledge Service
# ---------------------------------------------------------------------------


class KnowledgeService:
    def __init__(self, settings: Settings, db: Database) -> None:
        self._settings = settings
        self._db = db
        self._embeddings = get_embedding_provider(settings)
        self._stop_event = threading.Event()
        self._consumer_task: Any = None

    # ------------------------------------------------------------------
    # Document management
    # ------------------------------------------------------------------

    def ingest_document(
        self,
        *,
        society_id: uuid.UUID,
        source_type: str,
        source_id: uuid.UUID | None,
        title: str,
        body: str,
        audience_roles: list[str] | None = None,
        audience_tower_ids: list[uuid.UUID] | None = None,
    ) -> uuid.UUID:
        """Upsert kb_document and re-insert all kb_chunk rows. Returns document_id."""
        audience_roles = audience_roles or []
        audience_tower_ids = audience_tower_ids or []

        with self._db.tx(Tenant.system(society_id)) as conn:
            # Upsert document
            if source_id is not None:
                row = conn.execute(
                    """INSERT INTO kb_document
                       (id, society_id, source_type, source_id, title, audience_roles, audience_tower_ids, status)
                       VALUES (%s, %s, %s, %s, %s, %s, %s, 'ACTIVE')
                       ON CONFLICT (society_id, source_type, source_id)
                       DO UPDATE SET title = EXCLUDED.title, status = 'ACTIVE', updated_at = now()
                       RETURNING id""",
                    (uuid7(), society_id, source_type, source_id, title,
                     audience_roles, audience_tower_ids),
                ).fetchone()
            else:
                row = conn.execute(
                    """INSERT INTO kb_document
                       (id, society_id, source_type, source_id, title, audience_roles, audience_tower_ids, status)
                       VALUES (%s, %s, %s, NULL, %s, %s, %s, 'ACTIVE')
                       RETURNING id""",
                    (uuid7(), society_id, source_type, title,
                     audience_roles, audience_tower_ids),
                ).fetchone()

            doc_id: uuid.UUID = row['id']

            # Delete old chunks (re-ingest)
            conn.execute("DELETE FROM kb_chunk WHERE document_id = %s", (doc_id,))

            # Create new chunks
            chunks = chunk_text(body, self._settings.chunk_chars)
            for ordinal, chunk_content in enumerate(chunks):
                vec = self._embeddings.embed(chunk_content)
                conn.execute(
                    """INSERT INTO kb_chunk (id, society_id, document_id, ordinal, content, embedding)
                       VALUES (%s, %s, %s, %s, %s, %s::vector)""",
                    (uuid7(), society_id, doc_id, ordinal, chunk_content, vector_literal(vec)),
                )

        return doc_id

    def search(
        self,
        *,
        query: str,
        society_id: uuid.UUID,
        user_roles: list[str] | None = None,
        tower_id: uuid.UUID | None = None,
        top_k: int | None = None,
    ) -> list[ChunkResult]:
        """Vector similarity search in kb_chunk for a society."""
        user_roles = user_roles or []
        top_k = top_k or self._settings.rag_top_k
        min_score = self._settings.rag_min_score

        query_vec = self._embeddings.embed(query)
        vec_str = vector_literal(query_vec)

        with self._db.tx(Tenant.system(society_id)) as conn:
            rows = conn.execute(
                """SELECT c.id AS chunk_id, c.document_id, d.title, c.content,
                          1 - (c.embedding <=> %s::vector) AS score
                   FROM kb_chunk c
                   JOIN kb_document d ON d.id = c.document_id
                   WHERE d.status = 'ACTIVE'
                   ORDER BY c.embedding <=> %s::vector
                   LIMIT %s""",
                (vec_str, vec_str, top_k),
            ).fetchall()

        return [
            ChunkResult(
                chunk_id=r['chunk_id'],
                document_id=r['document_id'],
                title=r['title'],
                content=r['content'],
                score=float(r['score']),
            )
            for r in rows
            if float(r['score']) >= min_score
        ]

    def archive_document(self, *, society_id: uuid.UUID, document_id: uuid.UUID) -> None:
        with self._db.tx(Tenant.system(society_id)) as conn:
            conn.execute(
                "UPDATE kb_document SET status = 'ARCHIVED', updated_at = now() WHERE id = %s",
                (document_id,),
            )

    def get_document(self, *, society_id: uuid.UUID, document_id: uuid.UUID) -> KBDocument | None:
        with self._db.tx(Tenant.system(society_id)) as conn:
            row = conn.execute(
                "SELECT * FROM kb_document WHERE id = %s",
                (document_id,),
            ).fetchone()
        if row is None:
            return None
        return _row_to_kb_doc(row)

    def list_documents(
        self,
        *,
        society_id: uuid.UUID,
        status: str | None = None,
        source_type: str | None = None,
    ) -> list[KBDocument]:
        params: list[Any] = []
        clauses: list[str] = []
        if status:
            clauses.append("status = %s")
            params.append(status)
        if source_type:
            clauses.append("source_type = %s")
            params.append(source_type)
        where = ('WHERE ' + ' AND '.join(clauses)) if clauses else ''
        sql = f"SELECT * FROM kb_document {where} ORDER BY created_at DESC"

        with self._db.tx(Tenant.system(society_id)) as conn:
            rows = conn.execute(sql, params).fetchall()
        return [_row_to_kb_doc(r) for r in rows]

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
            from confluent_kafka import Consumer, KafkaException
        except ImportError:
            log.warning('confluent-kafka not available; knowledge consumer not started')
            return

        conf = {
            'bootstrap.servers': self._settings.kafka_bootstrap,
            'group.id': 'ai-knowledge',
            'auto.offset.reset': 'earliest',
            'enable.auto.commit': False,
        }
        consumer = Consumer(conf)
        consumer.subscribe(['sos.community.events.v1'])
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
        if ce_type != 'community.notice.published':
            return

        event_id = headers.get('ce_id', '')
        ce_society = headers.get('ce_societyid', '')
        if not ce_society:
            return

        try:
            society_id = uuid.UUID(ce_society)
        except ValueError:
            log.warning('Invalid society_id in Kafka header: %s', ce_society)
            return

        import json
        try:
            payload = json.loads(msg.value())
        except Exception:
            log.warning('Could not parse Kafka message value')
            return

        data = payload.get('data', payload)

        notice_id_str = data.get('noticeId') or data.get('id')
        title = data.get('title', 'Untitled Notice')
        content = data.get('content') or data.get('body', '')
        if not content:
            return

        notice_id = uuid.UUID(notice_id_str) if notice_id_str else None

        # Idempotency check and document ingest are in the SAME transaction so a crash
        # between them cannot leave a permanently-lost event.  If the inbox INSERT lands
        # the document upsert is also committed; if either fails both roll back and the
        # event is retried on the next poll.
        audience_roles: list[str] = []
        audience_tower_ids: list[uuid.UUID] = []

        with self._db.tx(Tenant.system(society_id)) as conn:
            result = conn.execute(
                "INSERT INTO inbox_event (consumer, event_id) VALUES (%s, %s) ON CONFLICT DO NOTHING",
                ('ai-knowledge', event_id),
            )
            if result.rowcount == 0:
                return  # already processed

            # Upsert document
            if notice_id is not None:
                row = conn.execute(
                    """INSERT INTO kb_document
                       (id, society_id, source_type, source_id, title, audience_roles, audience_tower_ids, status)
                       VALUES (%s, %s, %s, %s, %s, %s, %s, 'ACTIVE')
                       ON CONFLICT (society_id, source_type, source_id)
                       DO UPDATE SET title = EXCLUDED.title, status = 'ACTIVE', updated_at = now()
                       RETURNING id""",
                    (uuid7(), society_id, 'NOTICE', notice_id, title,
                     audience_roles, audience_tower_ids),
                ).fetchone()
            else:
                row = conn.execute(
                    """INSERT INTO kb_document
                       (id, society_id, source_type, source_id, title, audience_roles, audience_tower_ids, status)
                       VALUES (%s, %s, %s, NULL, %s, %s, %s, 'ACTIVE')
                       RETURNING id""",
                    (uuid7(), society_id, 'NOTICE', title,
                     audience_roles, audience_tower_ids),
                ).fetchone()

            doc_id: uuid.UUID = row['id']

            # Delete old chunks (re-ingest)
            conn.execute("DELETE FROM kb_chunk WHERE document_id = %s", (doc_id,))

            # Create new chunks
            chunks = chunk_text(content, self._settings.chunk_chars)
            for ordinal, chunk_content in enumerate(chunks):
                vec = self._embeddings.embed(chunk_content)
                conn.execute(
                    """INSERT INTO kb_chunk (id, society_id, document_id, ordinal, content, embedding)
                       VALUES (%s, %s, %s, %s, %s, %s::vector)""",
                    (uuid7(), society_id, doc_id, ordinal, chunk_content, vector_literal(vec)),
                )

        log.info('Ingested notice %s for society %s', notice_id, society_id)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _row_to_kb_doc(row: dict) -> KBDocument:
    return KBDocument(
        id=row['id'],
        society_id=row['society_id'],
        source_type=row['source_type'],
        source_id=row.get('source_id'),
        title=row['title'],
        audience_roles=list(row.get('audience_roles') or []),
        audience_tower_ids=list(row.get('audience_tower_ids') or []),
        status=row['status'],
        created_at=row.get('created_at'),
    )


# ---------------------------------------------------------------------------
# Router
# ---------------------------------------------------------------------------

router = APIRouter(prefix='/v1/knowledge', tags=['knowledge'])


class _IngestIn(BaseModel):
    source_type: str = 'DOCUMENT'
    source_id: uuid.UUID | None = None
    title: str
    body: str
    audience_roles: list[str] = []
    audience_tower_ids: list[uuid.UUID] = []


class _SearchIn(BaseModel):
    query: str
    top_k: int = 5


def _knowledge_svc(request: Request) -> KnowledgeService:
    return request.app.state.knowledge


@router.post('/documents', summary='Ingest a document into the knowledge base')
def ingest_document(body: _IngestIn, request: Request):
    svc: KnowledgeService = _knowledge_svc(request)
    t = tenant_ctx.current()
    society_id = t.require_active_society()
    doc_id = svc.ingest_document(
        society_id=society_id,
        source_type=body.source_type,
        source_id=body.source_id,
        title=body.title,
        body=body.body,
        audience_roles=body.audience_roles,
        audience_tower_ids=body.audience_tower_ids,
    )
    return {'document_id': str(doc_id)}


@router.delete('/documents/{document_id}', summary='Archive a knowledge base document')
def archive_document(document_id: uuid.UUID, request: Request):
    svc: KnowledgeService = _knowledge_svc(request)
    t = tenant_ctx.current()
    society_id = t.require_active_society()
    svc.archive_document(society_id=society_id, document_id=document_id)
    return {'document_id': str(document_id), 'status': 'ARCHIVED'}


@router.post('/search', summary='Semantic search across knowledge base')
def search_knowledge(body: _SearchIn, request: Request):
    svc: KnowledgeService = _knowledge_svc(request)
    t = tenant_ctx.current()
    society_id = t.require_active_society()
    results = svc.search(
        query=body.query,
        society_id=society_id,
        user_roles=list(t.roles),
        top_k=body.top_k,
    )
    return [
        {
            'chunk_id': str(r.chunk_id),
            'document_id': str(r.document_id),
            'title': r.title,
            'content': r.content,
            'score': r.score,
        }
        for r in results
    ]


@router.get('/documents', summary='List knowledge base documents')
def list_documents(request: Request, status: str | None = None, source_type: str | None = None):
    svc: KnowledgeService = _knowledge_svc(request)
    t = tenant_ctx.current()
    society_id = t.require_active_society()
    docs = svc.list_documents(society_id=society_id, status=status, source_type=source_type)
    return [
        {
            'id': str(d.id),
            'title': d.title,
            'source_type': d.source_type,
            'status': d.status,
        }
        for d in docs
    ]
