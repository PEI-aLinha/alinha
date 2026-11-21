import pytest
from fastapi import Depends
from fastapi.testclient import TestClient
from pydantic import BaseModel, SecretStr

from ai_service.deps import get_provider
from ai_service.main import create_app
from ai_service.providers.base import ProviderError, ProviderTimeout
from ai_service.providers.fake import FakeProvider


class Body(BaseModel):
    task: str


class Spy(FakeProvider):
    closed = False

    async def aclose(self):
        Spy.closed = True


TOKEN = "test-service-token"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


def make(provider):
    app = create_app(provider=provider, service_token=SecretStr(TOKEN))

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


def test_startup_fails_without_openbao_token(monkeypatch):
    # The service token always comes from OpenBao, so even the fake provider needs BAO_TOKEN.
    monkeypatch.delenv("BAO_TOKEN", raising=False)
    from ai_service.config import get_settings
    get_settings.cache_clear()
    try:
        with pytest.raises(Exception, match="BAO_TOKEN is required"):
            with TestClient(create_app(provider=FakeProvider())):
                pass
    finally:
        get_settings.cache_clear()


def test_config_errors_do_not_echo_the_openbao_token(monkeypatch):
    monkeypatch.setenv("LLM_PROVIDER", "openai_compat")
    monkeypatch.setenv("LLM_BASE_URL", "https://llm.example")
    monkeypatch.setenv("BAO_TOKEN", "s.LEAKME")
    from ai_service.config import Settings
    with pytest.raises(Exception, match="LLM_MODEL") as exc:
        Settings()
    assert "LEAKME" not in str(exc.value)


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


def test_bad_config_passes_import_and_fails_at_startup(monkeypatch):
    # `app = create_app()` runs at import; Settings is read only in the lifespan.
    import importlib

    import ai_service.main as m
    monkeypatch.setenv("LLM_PROVIDER", "openai_compat")  # no LLM_BASE_URL / LLM_MODEL
    m = importlib.reload(m)  # must not raise
    try:
        with pytest.raises(Exception, match="LLM_BASE_URL"):
            with TestClient(m.app):
                pass
    finally:
        monkeypatch.setenv("LLM_PROVIDER", "fake")
        importlib.reload(m)


def test_module_import_does_not_build_provider(monkeypatch):
    import importlib

    import ai_service.main as m
    monkeypatch.setenv("LLM_PROVIDER", "openai_compat")
    importlib.reload(m)  # must not raise: provider is built only at startup
