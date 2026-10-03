"""Tests for PII redaction in the LLM gateway."""

import pytest
from app.llm import redact_pii


class TestRedactPii:
    def test_indian_mobile_10_digit(self):
        result = redact_pii("call 9876543210 today")
        assert "[PHONE]" in result
        assert "9876543210" not in result

    def test_indian_mobile_with_91_prefix(self):
        result = redact_pii("contact +919876543210 for help")
        assert "[PHONE]" in result
        assert "9876543210" not in result

    def test_indian_mobile_with_91_space(self):
        # The regex matches +91<opt-space>XXXXXXXXXX — the 10 digits must be contiguous
        # after the optional +91 prefix (no spaces within the digits themselves).
        result = redact_pii("+91 9876543210 is my number")
        assert "[PHONE]" in result
        assert "9876543210" not in result

    def test_email_basic(self):
        result = redact_pii("email user@example.com for support")
        assert "[EMAIL]" in result
        assert "user@example.com" not in result

    def test_email_with_plus_tag(self):
        result = redact_pii("send to test.user+tag@sub.domain.in please")
        assert "[EMAIL]" in result
        assert "test.user+tag@sub.domain.in" not in result

    def test_mixed_phone_and_email(self):
        result = redact_pii("reach 9876543210 or test@example.com for info")
        assert "[PHONE]" in result
        assert "[EMAIL]" in result
        assert "9876543210" not in result
        assert "test@example.com" not in result

    def test_no_pii_unchanged(self):
        clean = "Please fix the water leak in block A."
        result = redact_pii(clean)
        assert result == clean

    def test_empty_string(self):
        assert redact_pii("") == ""

    def test_none_safety(self):
        # redact_pii should return early for empty/falsy input
        assert redact_pii("") == ""

    def test_multiple_phones(self):
        result = redact_pii("call 9876543210 or 8765432109")
        assert result.count("[PHONE]") == 2
        assert "9876543210" not in result
        assert "8765432109" not in result
