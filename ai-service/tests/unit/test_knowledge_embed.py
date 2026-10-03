"""Tests for embedding and chunking logic."""

import pytest
from app.config import Settings, EMBEDDING_DIM
from app.knowledge import embed, chunk_text


@pytest.fixture
def settings():
    return Settings(
        llm_provider='fake',
        kafka_enabled=False,
        run_migrations=False,
        embeddings_provider='hashing',
    )


class TestEmbed:
    def test_returns_correct_dimensions(self, settings):
        vec = embed("hello world", settings)
        assert len(vec) == EMBEDDING_DIM

    def test_all_floats(self, settings):
        vec = embed("test input", settings)
        assert all(isinstance(v, float) for v in vec)

    def test_deterministic(self, settings):
        v1 = embed("same text here", settings)
        v2 = embed("same text here", settings)
        assert v1 == v2

    def test_different_texts_differ(self, settings):
        v1 = embed("water pipe leak in bathroom", settings)
        v2 = embed("light bulb fused in bedroom", settings)
        assert v1 != v2

    def test_unit_vector(self, settings):
        import math
        vec = embed("some text", settings)
        norm = math.sqrt(sum(v * v for v in vec))
        assert abs(norm - 1.0) < 1e-5


class TestChunkText:
    def test_short_text_single_chunk(self):
        text = "Hello world."
        chunks = chunk_text(text, 100)
        assert len(chunks) == 1
        assert chunks[0] == text.strip()

    def test_long_text_splits(self):
        text = "x" * 500
        chunks = chunk_text(text, 100)
        assert len(chunks) > 1

    def test_no_chunk_exceeds_limit(self):
        text = "sentence one. sentence two. " * 20
        chunk_chars = 100
        chunks = chunk_text(text, chunk_chars)
        for chunk in chunks:
            assert len(chunk) <= chunk_chars * 1.2  # allow slight overflow at boundaries

    def test_content_preserved(self):
        words = ["apple", "banana", "cherry", "date", "elderberry"]
        text = ". ".join(words) + "."
        chunks = chunk_text(text, 15)
        combined = " ".join(chunks)
        for word in words:
            assert word in combined

    def test_empty_text(self):
        assert chunk_text("", 100) == []

    def test_exact_fit(self):
        text = "a" * 50
        chunks = chunk_text(text, 100)
        assert len(chunks) == 1
