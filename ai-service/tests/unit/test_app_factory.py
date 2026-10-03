"""Tests for the FastAPI app factory."""

import pytest
from fastapi import FastAPI
from starlette.testclient import TestClient

from app import create_app
from app.config import Settings


@pytest.fixture
def test_settings():
    return Settings(
        llm_provider='fake',
        kafka_enabled=False,
        run_migrations=False,
        embeddings_provider='hashing',
        db_url='postgresql://ai_app:ai_app@localhost:5432/ai_db',
        db_owner_url='postgresql://ai_owner:ai_owner@localhost:5432/ai_db',
    )


@pytest.fixture
def app(test_settings):
    return create_app(settings=test_settings)


class TestCreateApp:
    def test_returns_fastapi_instance(self, app):
        assert isinstance(app, FastAPI)

    def test_has_title(self, app):
        assert 'AI Service' in app.title

    def test_health_endpoint_exists(self, app):
        """Health check must respond 200 without triggering lifespan (DB/Kafka)."""
        # TestClient without context manager does not run lifespan
        client = TestClient(app, raise_server_exceptions=False)
        resp = client.get('/actuator/health')
        assert resp.status_code == 200

    def test_health_endpoint_body(self, app):
        client = TestClient(app, raise_server_exceptions=False)
        resp = client.get('/actuator/health')
        data = resp.json()
        assert data.get('status') == 'UP'

    def test_routers_registered(self, app):
        """Check that expected route prefixes are present in the app routes."""
        # Use OpenAPI schema to enumerate all registered paths (most reliable across versions)
        openapi = app.openapi()
        path_str = ' '.join(openapi.get('paths', {}).keys())
        assert '/v1/llm' in path_str, f'LLM router missing. Paths: {path_str}'
        assert '/v1/knowledge' in path_str, f'Knowledge router missing.'
        assert '/v1/helpdesk' in path_str, f'Helpdesk router missing.'
        assert '/v1/triage' in path_str, f'Triage router missing.'
        assert '/v1/estate' in path_str, f'Estate router missing.'
