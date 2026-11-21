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
