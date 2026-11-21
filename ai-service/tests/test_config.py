import pytest
from pydantic import SecretStr, ValidationError

from ai_service.config import Settings


def real(**overrides):
    values = {
        "llm_provider": "openai_compat",
        "llm_base_url": "https://api.mistral.ai/v1",
        "llm_model": "m",
        "bao_token": SecretStr("t"),
    } | overrides
    return Settings(**values)


def test_llm_provider_has_no_default(monkeypatch):
    monkeypatch.delenv("LLM_PROVIDER")

    with pytest.raises(ValidationError, match="llm_provider"):
        Settings()


def test_fake_provider_needs_no_llm_config():
    assert Settings().llm_provider == "fake"


@pytest.mark.parametrize("provider", ["fake", "openai_compat"])
def test_bao_token_is_required_for_every_provider(monkeypatch, provider):
    monkeypatch.delenv("BAO_TOKEN")
    monkeypatch.setenv("LLM_PROVIDER", provider)

    with pytest.raises(ValidationError, match="BAO_TOKEN is required"):
        Settings()


@pytest.mark.parametrize("url", [
    "https://api.mistral.ai/v1",
    "https://API.Scaleway.ai/v1",          # host match is case-insensitive
    "http://localhost:11434/v1",
])
def test_accepts_eu_hosts(url):
    assert real(llm_base_url=url).llm_base_url == url


@pytest.mark.parametrize("url", [
    "https://api.openai.com/v1",
    "https://api.mistral.ai.evil.com/v1",  # exact match, not prefix
    "https://evil.com/api.mistral.ai",     # host, not path
    "not a url",
])
def test_rejects_hosts_outside_the_eu_allowlist(url):
    with pytest.raises(ValidationError, match="not in EU_LLM_HOSTS"):
        real(llm_base_url=url)


@pytest.mark.parametrize("url", [
    "https://api.mistral.ai:443/v1",
    "http://127.0.0.1:8001/v1",          # local: http and any port allowed
    "https://localhost:8443/v1",
])
def test_accepts_tls_or_local_urls(url):
    assert real(llm_base_url=url).llm_base_url == url


@pytest.mark.parametrize("url,message", [
    ("http://api.mistral.ai/v1", "must use https"),             # key and context in plaintext
    ("ftp://api.mistral.ai/v1", "must use https"),
    ("ftp://localhost/v1", "must use http or https"),
    ("https://user:pw@api.mistral.ai/v1", "must not contain credentials"),
    ("https://user@api.mistral.ai/v1", "must not contain credentials"),
    ("https://api.mistral.ai:8443/v1", "default https port"),
    ("https://api.mistral.ai:99999/v1", "invalid port"),
])
def test_rejects_unsafe_urls(url, message):
    with pytest.raises(ValidationError, match=message):
        real(llm_base_url=url)


def test_url_errors_do_not_echo_credentials():
    with pytest.raises(ValidationError) as exc:
        real(llm_base_url="https://user:s3cr3t@api.mistral.ai/v1")
    assert "s3cr3t" not in str(exc.value)
