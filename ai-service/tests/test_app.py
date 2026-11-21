import pytest
from fastapi import Depends
from fastapi.testclient import TestClient
from pydantic import BaseModel

from ai_service.main import create_app, get_provider
from ai_service.providers.base import ProviderError, ProviderTimeout
from ai_service.providers.fake import FakeProvider


class Body(BaseModel):
    task: str


class Spy(FakeProvider):
    closed = False

    async def aclose(self):
        Spy.closed = True


def make(provider):
    app = create_app(provider=provider)

    @app.post("/probe")
    async def probe(body: Body, p=Depends(get_provider)):
        r = await p.complete(system="s", user=body.task)
        return {"text": r.text}

    return app


def test_health():
    with TestClient(make(FakeProvider())) as c:
        r = c.get("/health")
    assert r.status_code == 200 and r.json() == {"status": "ok"}


def test_ok_path():
    with TestClient(make(FakeProvider(text="hi"))) as c:
        assert c.post("/probe", json={"task": "x"}).json() == {"text": "hi"}


@pytest.mark.parametrize("err,status,code", [
    (ProviderTimeout("t"), 504, "provider_timeout"),
    (ProviderError("secret ctx"), 502, "provider_error"),
])
def test_provider_errors(err, status, code):
    with TestClient(make(FakeProvider(error=err))) as c:
        r = c.post("/probe", json={"task": "x"})
    assert r.status_code == status
    assert r.json()["code"] == code and set(r.json()) == {"code", "message"}
    assert "secret" not in r.text


def test_validation():
    with TestClient(make(FakeProvider())) as c:
        r = c.post("/probe", json={"task": {"leak": "SENSITIVE"}})
    assert r.status_code == 422
    assert r.json()["code"] == "invalid_request" and "SENSITIVE" not in r.text
    assert "body.task" in r.json()["message"]


def test_aclose_called():
    Spy.closed = False
    with TestClient(make(Spy())):
        assert not Spy.closed
    assert Spy.closed


def test_startup_fails_on_bad_config(monkeypatch):
    monkeypatch.setenv("LLM_PROVIDER", "openai_compat")
    from ai_service.config import get_settings
    get_settings.cache_clear()
    try:
        with pytest.raises(Exception, match="LLM_BASE_URL"):
            with TestClient(create_app()):
                pass
    finally:
        get_settings.cache_clear()


def test_module_import_does_not_build_provider(monkeypatch):
    import importlib, ai_service.main as m
    monkeypatch.setenv("LLM_PROVIDER", "openai_compat")
    importlib.reload(m)  # must not raise: provider is built only at startup
