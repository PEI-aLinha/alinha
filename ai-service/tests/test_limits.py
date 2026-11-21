import json

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from ai_service.config import Settings
from ai_service.drafts import MAX_CONTEXT_DEPTH
from ai_service.main import create_app
from ai_service.providers.fake import FakeProvider

TOKEN = "test-service-token"
AUTH = {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"}


def app(limit=None, provider=None):
    return create_app(
        provider=provider or FakeProvider(),
        service_token=SecretStr(TOKEN),
        max_request_bytes=limit,
    )


def body_of(size: int) -> bytes:
    pad = "x" * max(0, size - 40)
    return json.dumps({"task": "roadmap", "context": {"pad": pad}}).encode()


def nested(depth: int) -> dict:
    value: dict = {}
    for _ in range(depth - 1):
        value = {"a": value}
    return value


def test_default_limit_is_256_kib():
    assert Settings().max_request_bytes == 262_144


def test_body_over_the_limit_is_413_and_never_reaches_the_llm():
    provider = FakeProvider()
    with TestClient(app(limit=1_000, provider=provider)) as c:
        r = c.post("/v1/drafts", content=body_of(2_000), headers=AUTH)

    assert r.status_code == 413
    assert r.json() == {"code": "payload_too_large", "message": "Request body is too large."}
    assert provider.calls == []


def test_limit_applies_before_authentication():
    # FastAPI reads the body before running the token check; the limit must come first.
    with TestClient(app(limit=1_000)) as c:
        r = c.post("/v1/drafts", content=body_of(2_000), headers={"Content-Type": "application/json"})

    assert r.status_code == 413


def test_chunked_body_without_content_length_is_counted():
    def chunks():
        for _ in range(10):
            yield b" " * 500  # 5 000 bytes, no Content-Length

    with TestClient(app(limit=1_000)) as c:
        r = c.post("/v1/drafts", content=chunks(), headers=AUTH)

    assert r.status_code == 413


def test_body_within_the_limit_passes():
    with TestClient(app(limit=1_000)) as c:
        r = c.post("/v1/drafts", content=body_of(900), headers=AUTH)

    assert r.status_code == 200


def test_full_assessment_sized_context_passes_the_default_limit():
    # ~150 KB: the estimated assessment of every framework.
    with TestClient(app()) as c:
        r = c.post("/v1/drafts", content=body_of(150_000), headers=AUTH)

    assert r.status_code == 200


def test_limit_comes_from_settings(monkeypatch):
    monkeypatch.setenv("MAX_REQUEST_BYTES", "1000")
    with TestClient(app()) as c:
        r = c.post("/v1/drafts", content=body_of(2_000), headers=AUTH)

    assert r.status_code == 413


def test_context_at_max_depth_passes():
    with TestClient(app()) as c:
        r = c.post(
            "/v1/drafts",
            json={"task": "roadmap", "context": nested(MAX_CONTEXT_DEPTH)},
            headers={"Authorization": f"Bearer {TOKEN}"},
        )

    assert r.status_code == 200


@pytest.mark.parametrize("make", [
    lambda d: nested(d),
    lambda d: {"list": _nested_list(d - 1)},  # arrays count too
])
def test_context_deeper_than_max_is_422(make):
    provider = FakeProvider()
    with TestClient(app(provider=provider)) as c:
        r = c.post(
            "/v1/drafts",
            json={"task": "roadmap", "context": make(MAX_CONTEXT_DEPTH + 1)},
            headers={"Authorization": f"Bearer {TOKEN}"},
        )

    assert r.status_code == 422
    assert r.json()["code"] == "invalid_request"
    assert provider.calls == []


def _nested_list(depth: int) -> list:
    value: list = []
    for _ in range(depth - 1):
        value = [value]
    return value


def test_unparseable_deep_json_is_400_invalid_request():
    # Too deep for the JSON parser itself (within the size limit): FastAPI answers 400.
    n = 40_000
    raw = '{"task":"roadmap","context":' + '{"a":' * n + "1" + "}" * n + "}"
    with TestClient(app()) as c:
        r = c.post("/v1/drafts", content=raw, headers=AUTH)

    assert r.status_code == 400
    assert r.json()["code"] == "invalid_request"


def test_health_is_not_affected():
    with TestClient(app(limit=10)) as c:
        assert c.get("/health").status_code == 200
