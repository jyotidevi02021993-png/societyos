"""Service settings, read from the environment (and `.env` in local dev).

Names mirror the Java services' `application.yml` variables where the concept is the same
(DB_URL, KAFKA_BOOTSTRAP, SOS_ISSUER, SOS_JWKS_URI, SOS_OUTBOX_RELAY).
"""

from __future__ import annotations

from functools import lru_cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    service_name: str = "ai-service"
    port: int = Field(8097, alias="PORT")

    # PostgreSQL. Runtime role is `ai_app` (DML only, not table owner) so RLS applies;
    # migrations run as `ai_owner`.
    db_url: str = Field("postgresql://ai_app:ai_app@localhost:5432/ai_db", alias="DB_URL")
    db_owner_url: str = Field("postgresql://ai_owner:ai_owner@localhost:5432/ai_db", alias="DB_OWNER_URL")
    db_pool_max: int = Field(10, alias="DB_POOL_MAX")
    run_migrations: bool = Field(True, alias="SOS_RUN_MIGRATIONS")

    # Kafka
    kafka_bootstrap: str = Field("localhost:9092", alias="KAFKA_BOOTSTRAP")
    kafka_enabled: bool = Field(True, alias="SOS_KAFKA_ENABLED")
    # debezium (Kafka Connect reads the WAL) or polling (built-in relay)
    outbox_relay: str = Field("debezium", alias="SOS_OUTBOX_RELAY")
    outbox_polling_interval_ms: int = Field(200, alias="SOS_OUTBOX_POLLING_INTERVAL")

    # Security: tokens issued by identity-service, keys from its JWKS
    issuer: str = Field("https://auth.societyos.in", alias="SOS_ISSUER")
    jwks_uri: str = Field("http://localhost:8081/.well-known/jwks.json", alias="SOS_JWKS_URI")
    identity_url: str = Field("http://localhost:8081", alias="IDENTITY_URL")
    permission_cache_seconds: int = Field(300, alias="SOS_PERMISSION_CACHE_SECONDS")
    # Expected JWT audience.  Set to the service name (e.g. "ai-service") to require that
    # tokens carry a matching 'aud' claim — recommended in multi-service deployments sharing
    # one JWKS endpoint.  Leave unset (None) to skip audience validation — acceptable in
    # single-service or dev environments where the JWKS endpoint is private.
    jwt_audience: str | None = Field(None, alias="SOS_JWT_AUDIENCE")

    # LLM gateway
    llm_provider: str = Field("anthropic", alias="LLM_PROVIDER")  # anthropic | fake
    anthropic_api_key: SecretStr | None = Field(None, alias="ANTHROPIC_API_KEY")
    anthropic_model: str = Field("claude-sonnet-5", alias="ANTHROPIC_MODEL")
    llm_max_tokens: int = Field(2048, alias="LLM_MAX_TOKENS")
    llm_timeout_seconds: float = Field(60.0, alias="LLM_TIMEOUT_SECONDS")
    # Default per-society daily cap (input + output tokens); overridable per society.
    default_daily_token_cap: int = Field(2_000_000, alias="AI_DEFAULT_DAILY_TOKEN_CAP")

    # Embeddings: hashing (local, deterministic, no model download) or sentence-transformers
    embeddings_provider: str = Field("hashing", alias="EMBEDDINGS_PROVIDER")
    embeddings_model: str = Field("sentence-transformers/all-MiniLM-L6-v2", alias="EMBEDDINGS_MODEL")

    # RAG
    rag_top_k: int = Field(5, alias="RAG_TOP_K")
    rag_min_score: float = Field(0.15, alias="RAG_MIN_SCORE")
    chunk_chars: int = Field(900, alias="RAG_CHUNK_CHARS")

    # Triage: suggestions below this confidence go to manual triage in ticket-service
    triage_auto_apply_threshold: float = 0.8


EMBEDDING_DIM = 384  # fixed by the ai_db schema (vector(384))


@lru_cache
def get_settings() -> Settings:
    return Settings()
