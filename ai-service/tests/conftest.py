"""Shared test fixtures."""

import pytest
from app.config import Settings


@pytest.fixture
def settings():
    return Settings(
        llm_provider='fake',
        kafka_enabled=False,
        run_migrations=False,
        embeddings_provider='hashing',
        # Override DB to avoid real connection attempts in unit tests
        db_url='postgresql://ai_app:ai_app@localhost:5432/ai_db',
        db_owner_url='postgresql://ai_owner:ai_owner@localhost:5432/ai_db',
    )
