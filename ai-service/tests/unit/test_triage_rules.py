"""Tests for TriageService rules-based classification."""

import uuid
import pytest

from app.config import Settings
from app.triage import TriageService, _DEFAULT_CATEGORIES


class _FakeDB:
    """Minimal DB stub — returns empty category list to force default taxonomy."""
    def tx(self, tenant=None):
        return self

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def execute(self, sql, params=None):
        return _FakeRows([])


class _FakeRows:
    def __init__(self, rows):
        self._rows = rows

    def fetchall(self):
        return self._rows

    def fetchone(self):
        return self._rows[0] if self._rows else None


@pytest.fixture
def settings():
    return Settings(
        llm_provider='fake',
        kafka_enabled=False,
        run_migrations=False,
        embeddings_provider='hashing',
    )


@pytest.fixture
def triage_service(settings):
    # llm is not used in rules triage; pass None
    return TriageService(settings=settings, db=_FakeDB(), llm=None)


class TestRulesTriage:
    def test_plumbing(self, triage_service):
        result = triage_service._rules_triage(
            "water pipe leaking in my bathroom", _DEFAULT_CATEGORIES
        )
        assert result['category'] == 'Plumbing'

    def test_electrical(self, triage_service):
        result = triage_service._rules_triage(
            "light not working in lobby", _DEFAULT_CATEGORIES
        )
        assert result['category'] == 'Electrical'

    def test_security(self, triage_service):
        result = triage_service._rules_triage(
            "suspicious person at gate last night", _DEFAULT_CATEGORIES
        )
        assert result['category'] == 'Security'

    def test_housekeeping(self, triage_service):
        result = triage_service._rules_triage(
            "garbage not cleaned in corridor", _DEFAULT_CATEGORIES
        )
        assert result['category'] == 'Housekeeping'

    def test_fallback_to_general_on_empty_categories(self, triage_service):
        result = triage_service._rules_triage(
            "some random complaint", [_DEFAULT_CATEGORIES[-1]]
        )
        # Should return the only category available (General)
        assert result['category'] == 'General'

    def test_confidence_increases_with_keywords(self, triage_service):
        few_kw = triage_service._rules_triage("water issue", _DEFAULT_CATEGORIES)
        many_kw = triage_service._rules_triage("water pipe leak tap drain toilet plumbing", _DEFAULT_CATEGORIES)
        assert many_kw['confidence'] >= few_kw['confidence']

    def test_output_has_required_fields(self, triage_service):
        result = triage_service._rules_triage("any text", _DEFAULT_CATEGORIES)
        assert 'category' in result
        assert 'department' in result
        assert 'priority' in result
        assert 'confidence' in result

    def test_priority_valid_values(self, triage_service):
        result = triage_service._rules_triage("lift stuck at 3rd floor", _DEFAULT_CATEGORIES)
        assert result['priority'] in ('P1', 'P2', 'P3', 'P4')
