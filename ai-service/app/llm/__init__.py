"""LLM Gateway — enforces per-society AI on/off and daily token cap, routes to Anthropic or fake
provider, redacts PII before logging.

Usage:
    gateway = LLMGateway(settings, db)
    response = await gateway.complete(purpose='HELPDESK', society_id=sid, prompt='...', system='...')
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

import psycopg
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from app.platform.db import Database
from app.platform.ids import uuid7
from app.platform import tenant as tenant_ctx
from app.platform.tenant import Tenant
from app.config import Settings, get_settings

log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# PII redaction (re-used by helpdesk and triage modules)
# ---------------------------------------------------------------------------
_PHONE_RE = re.compile(r'(\+91[\s-]?)?[6-9]\d{9}')
_EMAIL_RE = re.compile(r'[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}')


def redact_pii(text: str) -> str:
    """Strip Indian mobile numbers and email addresses. Returns a redacted copy."""
    if not text:
        return text
    text = _PHONE_RE.sub('[PHONE]', text)
    text = _EMAIL_RE.sub('[EMAIL]', text)
    return text


# ---------------------------------------------------------------------------
# Exceptions
# ---------------------------------------------------------------------------

class AIDisabledError(Exception):
    """Raised when the society has AI switched off."""


class TokenCapExceededError(Exception):
    """Raised when the society's daily token cap is exhausted."""


# ---------------------------------------------------------------------------
# Response dataclass
# ---------------------------------------------------------------------------

@dataclass
class LLMResponse:
    text: str
    input_tokens: int
    output_tokens: int
    model: str


# ---------------------------------------------------------------------------
# Fake responses (used when LLM_PROVIDER=fake)
# ---------------------------------------------------------------------------
_FAKE_RESPONSES: dict[str, tuple[str, int, int]] = {
    'HELPDESK': (
        'Based on the society rules, residents must follow the guidelines provided.',
        20,
        16,
    ),
    'TRIAGE': (
        '{"category": "General", "department": "Operations", "priority": "P3", "confidence": 0.85}',
        25,
        20,
    ),
    'ESTATE_HEALTH': (
        '{"headline": "Estate is operating normally.", "summary": "No critical issues detected.", "highlights": []}',
        30,
        22,
    ),
}

_FAKE_MODEL = 'fake-v1'


# ---------------------------------------------------------------------------
# LLM Gateway
# ---------------------------------------------------------------------------

class LLMGateway:
    def __init__(self, settings: Settings, db: Database) -> None:
        self._settings = settings
        self._db = db

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def complete(
        self,
        *,
        purpose: str,
        society_id: uuid.UUID,
        prompt: str,
        system: str | None = None,
    ) -> LLMResponse:
        """Check cap, call provider, write llm_call_log + llm_usage.

        Raises:
            AIDisabledError: if ai_enabled=False for the society.
            TokenCapExceededError: if the daily token cap is already hit.
            HTTPException 503: on provider error.
        """
        # 1. Verify AI enabled + cap headroom (read-only, no transaction needed)
        self._check_enabled_and_cap(society_id)

        # 2. Call provider
        outcome = 'OK'
        response: LLMResponse | None = None
        try:
            if self._settings.llm_provider == 'fake':
                response = await self._fake_complete(purpose, prompt)
            else:
                response = await self._anthropic_complete(prompt, system)
        except (AIDisabledError, TokenCapExceededError):
            raise
        except Exception as exc:
            log.exception("LLM provider error for society %s purpose %s", society_id, purpose)
            outcome = 'ERROR'
            # Still log the failed call
            prompt_redacted = redact_pii(prompt)
            with self._db.tx(Tenant.system(society_id)) as conn:
                self._log_call(
                    conn, society_id, purpose,
                    self._settings.anthropic_model, prompt_redacted, None,
                    0, 0, 'ERROR',
                )
            raise HTTPException(
                status_code=503,
                detail={
                    'type': 'about:blank',
                    'title': 'LLM provider error',
                    'status': 503,
                    'detail': str(exc),
                    'code': 'LLM_ERROR',
                },
            )

        # 3. Persist usage + log inside a transaction
        prompt_redacted = redact_pii(prompt)
        response_redacted = redact_pii(response.text)
        with self._db.tx(Tenant.system(society_id)) as conn:
            self._log_call(
                conn, society_id, purpose,
                response.model, prompt_redacted, response_redacted,
                response.input_tokens, response.output_tokens, outcome,
            )
            self._upsert_usage(conn, society_id, response.input_tokens, response.output_tokens)

        return response

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _check_enabled_and_cap(self, society_id: uuid.UUID) -> None:
        """Read ai_settings + today's llm_usage; raise if AI off or cap hit."""
        with self._db.tx(Tenant.system(society_id)) as conn:
            row = conn.execute(
                "SELECT ai_enabled, daily_token_cap FROM ai_settings WHERE society_id = %s",
                (society_id,),
            ).fetchone()
            if row is not None:
                if not row['ai_enabled']:
                    raise AIDisabledError(f"AI is disabled for society {society_id}")
                cap = row['daily_token_cap'] if row['daily_token_cap'] is not None else self._settings.default_daily_token_cap
            else:
                cap = self._settings.default_daily_token_cap

            usage_row = conn.execute(
                "SELECT COALESCE(input_tokens,0) + COALESCE(output_tokens,0) AS used "
                "FROM llm_usage WHERE society_id = %s AND day = CURRENT_DATE",
                (society_id,),
            ).fetchone()
            used = usage_row['used'] if usage_row else 0

            if used >= cap:
                # Log CAPPED outcome
                self._log_call(
                    conn, society_id, 'CAP_CHECK',
                    'N/A', '', None, 0, 0, 'CAPPED',
                )
                raise TokenCapExceededError(f"Daily token cap of {cap} reached for society {society_id}")

    def _upsert_usage(
        self,
        conn: psycopg.Connection,
        society_id: uuid.UUID,
        input_tokens: int,
        output_tokens: int,
    ) -> None:
        conn.execute(
            """INSERT INTO llm_usage (id, society_id, day, input_tokens, output_tokens, calls)
               VALUES (%s, %s, CURRENT_DATE, %s, %s, 1)
               ON CONFLICT (society_id, day) DO UPDATE
               SET input_tokens  = llm_usage.input_tokens  + EXCLUDED.input_tokens,
                   output_tokens = llm_usage.output_tokens + EXCLUDED.output_tokens,
                   calls         = llm_usage.calls + 1,
                   updated_at    = now()""",
            (uuid7(), society_id, input_tokens, output_tokens),
        )

    def _log_call(
        self,
        conn: psycopg.Connection,
        society_id: uuid.UUID,
        purpose: str,
        model: str,
        prompt_redacted: str,
        response_redacted: str | None,
        input_tokens: int,
        output_tokens: int,
        outcome: str,
    ) -> None:
        conn.execute(
            """INSERT INTO llm_call_log
               (id, society_id, purpose, model, prompt_redacted, response_redacted,
                input_tokens, output_tokens, outcome)
               VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)""",
            (uuid7(), society_id, purpose, model,
             prompt_redacted, response_redacted,
             input_tokens, output_tokens, outcome),
        )

    async def _fake_complete(self, purpose: str, prompt: str) -> LLMResponse:
        text, input_tokens, output_tokens = _FAKE_RESPONSES.get(
            purpose,
            ('I am here to help with your query.', 10, 10),
        )
        return LLMResponse(
            text=text,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            model=_FAKE_MODEL,
        )

    async def _anthropic_complete(self, prompt: str, system: str | None) -> LLMResponse:
        import anthropic  # local import so tests can mock

        api_key = (
            self._settings.anthropic_api_key.get_secret_value()
            if self._settings.anthropic_api_key
            else None
        )
        client = anthropic.Anthropic(api_key=api_key)
        messages_arg = [{'role': 'user', 'content': prompt}]
        kwargs: dict[str, Any] = {
            'model': self._settings.anthropic_model,
            'max_tokens': self._settings.llm_max_tokens,
            'messages': messages_arg,
        }
        if system:
            kwargs['system'] = system

        msg = await asyncio.wait_for(
            asyncio.to_thread(client.messages.create, **kwargs),
            timeout=self._settings.llm_timeout_seconds,
        )
        text = msg.content[0].text if msg.content else ''
        return LLMResponse(
            text=text,
            input_tokens=msg.usage.input_tokens,
            output_tokens=msg.usage.output_tokens,
            model=self._settings.anthropic_model,
        )


# ---------------------------------------------------------------------------
# Factory
# ---------------------------------------------------------------------------

def get_llm_gateway(db: Database, settings: Settings) -> LLMGateway:
    return LLMGateway(settings, db)


# ---------------------------------------------------------------------------
# Router (managed by app factory)
# ---------------------------------------------------------------------------

router = APIRouter(prefix='/v1/llm', tags=['llm'])


class _AISettingsIn(BaseModel):
    ai_enabled: bool = True
    daily_token_cap: int | None = None


def _llm_gateway(request: Request) -> LLMGateway:
    return request.app.state.llm


@router.get('/settings/{society_id}', summary='Get AI settings for a society')
def get_ai_settings(society_id: uuid.UUID, request: Request):
    db: Database = request.app.state.db
    with db.tx(Tenant.system(society_id)) as conn:
        row = conn.execute(
            "SELECT ai_enabled, daily_token_cap FROM ai_settings WHERE society_id = %s",
            (society_id,),
        ).fetchone()
    if row is None:
        return {'society_id': str(society_id), 'ai_enabled': True, 'daily_token_cap': None}
    return dict(row) | {'society_id': str(society_id)}


@router.put('/settings/{society_id}', summary='Update AI settings for a society')
def put_ai_settings(society_id: uuid.UUID, body: _AISettingsIn, request: Request):
    db: Database = request.app.state.db
    with db.tx(Tenant.system(society_id)) as conn:
        conn.execute(
            """INSERT INTO ai_settings (id, society_id, ai_enabled, daily_token_cap)
               VALUES (%s, %s, %s, %s)
               ON CONFLICT (society_id) DO UPDATE
               SET ai_enabled = EXCLUDED.ai_enabled,
                   daily_token_cap = EXCLUDED.daily_token_cap,
                   updated_at = now()""",
            (uuid7(), society_id, body.ai_enabled, body.daily_token_cap),
        )
    return {'society_id': str(society_id), 'ai_enabled': body.ai_enabled, 'daily_token_cap': body.daily_token_cap}


@router.get('/usage/{society_id}', summary='Get LLM usage for the last 30 days')
def get_llm_usage(society_id: uuid.UUID, request: Request):
    db: Database = request.app.state.db
    with db.tx(Tenant.system(society_id)) as conn:
        rows = conn.execute(
            """SELECT day, input_tokens, output_tokens, calls
               FROM llm_usage
               WHERE society_id = %s AND day >= CURRENT_DATE - INTERVAL '30 days'
               ORDER BY day DESC""",
            (society_id,),
        ).fetchall()
    return [dict(r) for r in rows]
