"""FastAPI application factory for the SocietyOS AI Service.

Call `create_app()` to get a configured FastAPI instance.  Lifespan opens the DB pool,
optionally runs migrations, starts Kafka consumers, and tears everything down on shutdown.
JWT middleware validates RS256 tokens and binds a Tenant to the request context.
"""

from __future__ import annotations

import logging
import time
import uuid
from contextlib import asynccontextmanager
from typing import Any

import jwt as pyjwt
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.responses import Response

from app.config import Settings, get_settings
from app.platform.db import Database
from app.platform.migrations import migrate
from app.platform import tenant as tenant_ctx
from app.platform.tenant import ActorType, Tenant
from app.llm import LLMGateway, router as llm_router, AIDisabledError, TokenCapExceededError
from app.knowledge import KnowledgeService, router as knowledge_router
from app.helpdesk import HelpdeskService, router as helpdesk_router
from app.triage import TriageService, router as triage_router
from app.estate import EstateService, router as estate_router

log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# App factory
# ---------------------------------------------------------------------------


def create_app(settings: Settings | None = None) -> FastAPI:
    if settings is None:
        settings = get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        # 1. Migrations (owner role)
        if settings.run_migrations:
            try:
                migrate(settings.db_owner_url)
            except Exception:
                log.warning('Migration skipped (no DB available at startup)', exc_info=True)

        # 2. DB pool (app role, RLS applies)
        db = Database(settings.db_url, settings.db_pool_max)
        try:
            db.open()
        except Exception:
            log.warning('DB pool open failed; running without DB', exc_info=True)

        # 3. Services
        llm = LLMGateway(settings, db)
        knowledge = KnowledgeService(settings, db)
        helpdesk = HelpdeskService(settings, db, llm, knowledge)
        triage = TriageService(settings, db, llm)
        estate = EstateService(settings, db, llm)

        app.state.settings = settings
        app.state.db = db
        app.state.llm = llm
        app.state.knowledge = knowledge
        app.state.helpdesk = helpdesk
        app.state.triage = triage
        app.state.estate = estate

        # 4. Kafka consumers
        if settings.kafka_enabled:
            await knowledge.start_consumer()
            await helpdesk.start_consumer()
            await triage.start_consumer()
            await estate.start_consumer()

        yield  # application runs

        # 5. Graceful shutdown
        if settings.kafka_enabled:
            await knowledge.stop_consumer()
            await helpdesk.stop_consumer()
            await triage.stop_consumer()
            await estate.stop_consumer()
        db.close()

    app = FastAPI(
        title='SocietyOS AI Service',
        version='1.0.0',
        lifespan=lifespan,
    )

    # JWT middleware
    _register_jwt_middleware(app, settings)

    # Error handlers
    @app.exception_handler(AIDisabledError)
    async def ai_disabled_handler(req: Request, exc: AIDisabledError):
        return JSONResponse(
            status_code=503,
            content={'type': 'AI_DISABLED', 'title': 'AI is disabled'},
        )

    @app.exception_handler(TokenCapExceededError)
    async def token_cap_handler(req: Request, exc: TokenCapExceededError):
        return JSONResponse(
            status_code=503,
            content={'type': 'TOKEN_CAP_EXCEEDED', 'title': 'Daily token cap reached'},
        )

    @app.exception_handler(RequestValidationError)
    async def validation_handler(req: Request, exc: RequestValidationError):
        return JSONResponse(
            status_code=422,
            content={
                'type': 'about:blank',
                'title': 'Validation Error',
                'status': 422,
                'detail': str(exc),
            },
        )

    @app.exception_handler(Exception)
    async def generic_handler(req: Request, exc: Exception):
        log.exception('Unhandled exception', exc_info=exc)
        return JSONResponse(
            status_code=500,
            content={
                'type': 'about:blank',
                'title': 'Internal Server Error',
                'status': 500,
                'detail': 'An unexpected error occurred',
            },
        )

    # Health endpoint (unauthenticated)
    @app.get('/actuator/health', tags=['ops'])
    def health():
        return {'status': 'UP'}

    # Routers
    app.include_router(llm_router)
    app.include_router(knowledge_router)
    app.include_router(helpdesk_router)
    app.include_router(triage_router)
    app.include_router(estate_router)

    return app


# ---------------------------------------------------------------------------
# JWT middleware
# ---------------------------------------------------------------------------

# Simple in-process JWKS cache
_jwks_cache: dict[str, Any] = {}
_jwks_cache_time: float = 0.0


def _fetch_jwks(settings: Settings) -> list[dict]:
    global _jwks_cache, _jwks_cache_time
    now = time.monotonic()
    if _jwks_cache and (now - _jwks_cache_time) < settings.permission_cache_seconds:
        return _jwks_cache.get('keys', [])

    import httpx
    try:
        resp = httpx.get(settings.jwks_uri, timeout=5.0)
        resp.raise_for_status()
        data = resp.json()
        _jwks_cache = data
        _jwks_cache_time = now
        return data.get('keys', [])
    except Exception:
        log.warning('Could not fetch JWKS from %s; using cached or empty set', settings.jwks_uri)
        return _jwks_cache.get('keys', [])


def _decode_token(token: str, settings: Settings) -> dict:
    """Decode and verify an RS256 JWT.  Returns the claims dict."""
    keys = _fetch_jwks(settings)

    # Try each key in the JWKS
    last_exc: Exception | None = None
    for key_data in keys:
        try:
            public_key = pyjwt.algorithms.RSAAlgorithm.from_jwk(key_data)
            claims = pyjwt.decode(
                token,
                public_key,
                algorithms=['RS256'],
                options={'verify_aud': False},
                issuer=settings.issuer,
            )
            return claims
        except pyjwt.exceptions.InvalidSignatureError:
            continue
        except Exception as exc:
            last_exc = exc
            continue

    # If no key succeeded, raise the last error (or a generic one)
    raise pyjwt.exceptions.InvalidTokenError(
        str(last_exc) if last_exc else 'No valid JWKS key found'
    )


def _register_jwt_middleware(app: FastAPI, settings: Settings) -> None:
    _SKIP_PATHS = {'/actuator/health', '/health'}

    class JWTMiddleware(BaseHTTPMiddleware):
        async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
            if request.url.path in _SKIP_PATHS:
                return await call_next(request)

            auth = request.headers.get('Authorization', '')
            if not auth.startswith('Bearer '):
                # No token — bind platform (unauthenticated) tenant; RLS returns empty rows
                with tenant_ctx.run_as(Tenant.platform()):
                    return await call_next(request)

            token = auth[len('Bearer '):]
            try:
                claims = _decode_token(token, settings)
            except Exception:
                return JSONResponse(
                    status_code=401,
                    content={
                        'type': 'about:blank',
                        'title': 'Unauthorized',
                        'status': 401,
                        'detail': 'Invalid or expired token',
                    },
                )

            # Extract claims
            user_id_str = claims.get('sub')
            user_id = uuid.UUID(user_id_str) if user_id_str else None

            # Society from token: 'societies' list or 'sid' scalar
            societies_claim = claims.get('societies') or claims.get('sids') or []
            if isinstance(societies_claim, str):
                societies_claim = [societies_claim]
            sid_claim = claims.get('sid')
            if sid_claim and sid_claim not in societies_claim:
                societies_claim = [sid_claim] + list(societies_claim)

            readable_ids: tuple[uuid.UUID, ...] = tuple(
                uuid.UUID(s) for s in societies_claim if _is_uuid(s)
            )

            # Active society from header or single society in token
            active_society_id: uuid.UUID | None = None
            x_society = request.headers.get('X-Society-Id', '')
            if x_society and _is_uuid(x_society):
                candidate = uuid.UUID(x_society)
                if candidate in readable_ids:
                    active_society_id = candidate
            elif len(readable_ids) == 1:
                active_society_id = readable_ids[0]

            roles: list[str] = claims.get('roles', [])
            if isinstance(roles, str):
                roles = [roles]

            tenant = Tenant(
                user_id=user_id,
                actor_type=ActorType.USER,
                active_society_id=active_society_id,
                readable_society_ids=readable_ids,
                roles=frozenset(roles),
                bearer_token=token,
            )
            with tenant_ctx.run_as(tenant):
                return await call_next(request)

    app.add_middleware(JWTMiddleware)


def _is_uuid(value: str) -> bool:
    try:
        uuid.UUID(value)
        return True
    except ValueError:
        return False
