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


def test_fake_provider_needs_the_explicit_opt_in(monkeypatch):
    # The production image sets ALLOW_FAKE_LLM=false: a stray LLM_PROVIDER=fake must not
    # serve placeholder drafts that look real.
    monkeypatch.setenv("ALLOW_FAKE_LLM", "false")

    with pytest.raises(ValidationError, match="ALLOW_FAKE_LLM=true"):
        Settings()


def test_fake_opt_in_is_off_by_default(monkeypatch):
    monkeypatch.delenv("ALLOW_FAKE_LLM")

    with pytest.raises(ValidationError, match="ALLOW_FAKE_LLM=true"):
        Settings()


def test_fake_opt_in_does_not_affect_real_providers(monkeypatch):
    monkeypatch.setenv("ALLOW_FAKE_LLM", "false")

    assert real().llm_provider == "openai_compat"


def test_max_output_tokens_default_and_bounds():
    assert Settings().llm_max_output_tokens == 4096
    for bad in (0, -1, 32769):
        with pytest.raises(ValidationError):
            Settings(llm_max_output_tokens=bad)


@pytest.mark.parametrize("provider", ["fake", "openai_compat"])
def test_bao_token_is_required_for_every_provider(monkeypatch, provider):
    monkeypatch.delenv("BAO_TOKEN")
    monkeypatch.setenv("LLM_PROVIDER", provider)

    with pytest.raises(ValidationError, match="BAO_TOKEN is required"):
        Settings()


@pytest.mark.parametrize("url", [
    "https://api.mistral.ai/v1",
    "https://API.Scaleway.ai/v1",          # host match is case-insensitive
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


def test_accepts_default_https_port_explicitly():
    url = "https://api.mistral.ai:443/v1"
    assert real(llm_base_url=url).llm_base_url == url


def test_local_llm_is_off_by_default():
    assert Settings().allow_local_llm is False


@pytest.mark.parametrize("url", [
    "http://localhost:11434/v1",
    "http://127.0.0.1:8001/v1",
    "https://localhost:8443/v1",
])
def test_local_hosts_are_rejected_without_the_dev_flag(url):
    # Loopback is not in the production allowlist: in a container it could only reach
    # a sidecar or a metadata proxy, never a vetted EU provider.
    with pytest.raises(ValidationError, match="ALLOW_LOCAL_LLM"):
        real(llm_base_url=url)


@pytest.mark.parametrize("url", [
    "http://localhost:11434/v1",           # local: http and any port allowed
    "http://127.0.0.1:8001/v1",
    "https://localhost:8443/v1",
])
def test_local_hosts_are_accepted_with_the_dev_flag(url):
    assert real(llm_base_url=url, allow_local_llm=True).llm_base_url == url


def test_dev_flag_comes_from_env(monkeypatch):
    monkeypatch.setenv("ALLOW_LOCAL_LLM", "true")
    monkeypatch.setenv("LLM_PROVIDER", "openai_compat")
    monkeypatch.setenv("LLM_BASE_URL", "http://localhost:11434/v1")
    monkeypatch.setenv("LLM_MODEL", "m")

    assert Settings().allow_local_llm is True


def test_dev_flag_does_not_widen_the_eu_allowlist():
    with pytest.raises(ValidationError, match="not in EU_LLM_HOSTS"):
        real(llm_base_url="https://api.openai.com/v1", allow_local_llm=True)


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
        real(llm_base_url=url, allow_local_llm=True)  # flag on, so ftp://localhost hits the scheme check


def test_url_errors_do_not_echo_credentials():
    with pytest.raises(ValidationError) as exc:
        real(llm_base_url="https://user:s3cr3t@api.mistral.ai/v1")
    assert "s3cr3t" not in str(exc.value)
