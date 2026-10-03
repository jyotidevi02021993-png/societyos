"""Tests for estate health score computation."""

import pytest
from app.estate import compute_score


class TestComputeScore:
    def test_no_signals_perfect_score(self):
        score, status = compute_score([])
        assert score == 100
        assert status == 'GOOD'

    def test_one_p1_complaint(self):
        signals = [{'kind': 'COMPLAINT', 'priority': 'P1', 'cnt': 1}]
        score, status = compute_score(signals)
        assert score == 90       # 100 - 10
        assert status == 'GOOD'

    def test_cert_expired_penalty(self):
        signals = [{'kind': 'CERT_EXPIRED', 'priority': None, 'cnt': 1}]
        score, status = compute_score(signals)
        assert score == 85       # 100 - 15
        assert status == 'GOOD'

    def test_multiple_signals_cumulative(self):
        signals = [
            {'kind': 'COMPLAINT', 'priority': 'P1', 'cnt': 2},   # 2 * 10 = 20
            {'kind': 'SLA_BREACH', 'priority': None, 'cnt': 3},   # 3 * 8  = 24
            {'kind': 'CERT_EXPIRED', 'priority': None, 'cnt': 1}, # 1 * 15 = 15
        ]
        score, status = compute_score(signals)
        assert score == max(0, 100 - 20 - 24 - 15)  # 41
        assert status == 'WATCH'

    def test_score_never_below_zero(self):
        signals = [{'kind': 'COMPLAINT', 'priority': 'P1', 'cnt': 100}]
        score, status = compute_score(signals)
        assert score == 0
        assert status == 'CRITICAL'

    def test_good_status_boundary(self):
        # Exactly 70 → GOOD
        signals = [{'kind': 'COMPLAINT', 'priority': 'P2', 'cnt': 6}]  # 6 * 5 = 30
        score, status = compute_score(signals)
        assert score == 70
        assert status == 'GOOD'

    def test_watch_status(self):
        signals = [{'kind': 'COMPLAINT', 'priority': 'P2', 'cnt': 8}]  # 8 * 5 = 40
        score, status = compute_score(signals)
        assert score == 60
        assert status == 'WATCH'

    def test_critical_status(self):
        signals = [
            {'kind': 'CERT_EXPIRED', 'priority': None, 'cnt': 4},  # 4 * 15 = 60
            {'kind': 'SLA_BREACH', 'priority': None, 'cnt': 5},    # 5 * 8  = 40
        ]
        score, status = compute_score(signals)
        assert score == 0
        assert status == 'CRITICAL'

    def test_p2_complaint_penalty(self):
        signals = [{'kind': 'COMPLAINT', 'priority': 'P2', 'cnt': 1}]
        score, _ = compute_score(signals)
        assert score == 95  # 100 - 5

    def test_p3_complaint_penalty(self):
        signals = [{'kind': 'COMPLAINT', 'priority': 'P3', 'cnt': 1}]
        score, _ = compute_score(signals)
        assert score == 98  # 100 - 2

    def test_unknown_kind_no_penalty(self):
        signals = [{'kind': 'UNKNOWN_TYPE', 'priority': None, 'cnt': 5}]
        score, status = compute_score(signals)
        assert score == 100
        assert status == 'GOOD'
