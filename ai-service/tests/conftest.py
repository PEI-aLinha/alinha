import pytest

from ai_service import config

# Hosts used by the tests' fake endpoints; never real providers.
TEST_HOSTS = {"llm.example.eu", "llm.example"}


@pytest.fixture(autouse=True)
def _test_config(monkeypatch):
    # LLM_PROVIDER has no default (a forgotten value must stop production), so tests set it.
    monkeypatch.setenv("LLM_PROVIDER", "fake")
    monkeypatch.setenv("ALLOW_FAKE_LLM", "true")  # fake needs the explicit dev/test opt-in
    # Required for every provider; tests that inject service_token never contact OpenBao.
    monkeypatch.setenv("BAO_TOKEN", "test-bao-token")
    monkeypatch.setattr(config, "EU_LLM_HOSTS", config.EU_LLM_HOSTS | TEST_HOSTS)
    config.get_settings.cache_clear()
    yield
    config.get_settings.cache_clear()
