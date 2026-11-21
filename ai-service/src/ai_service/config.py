"""Service configuration, read from environment variables.

Secrets are not configured here: the LLM API key is read from OpenBao
(see secrets.py). Only the address and token to reach OpenBao come from env.
"""

from functools import lru_cache
from typing import Literal
from urllib.parse import urlsplit

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# Hosts approved to receive client context: the LLM must process data in the EU (issue #31).
# Kept in code, not env, so approving a new provider needs a reviewed PR.
# Hosts are matched exactly (no wildcards). Add one only after checking where the provider
# processes and stores the data, and record that in ai-service/README.md.
EU_LLM_HOSTS: frozenset[str] = frozenset({
    "api.mistral.ai",    # Mistral AI (FR), La Plateforme
    "api.scaleway.ai",   # Scaleway (FR), Generative APIs
    "localhost",         # self-hosted model on the same machine (e.g. vLLM, Ollama)
    "127.0.0.1",         
    #all models will be changed to approve the host in the future, but for now we allow it for local testing
})

# Hosts where plain http and any port are allowed: the traffic never leaves the machine.
LOCAL_LLM_HOSTS: frozenset[str] = frozenset({"localhost", "127.0.0.1"})


class Settings(BaseSettings):
    # Validation errors must never echo the env values: BAO_TOKEN would end up in the logs.
    model_config = SettingsConfigDict(hide_input_in_errors=True)

    # Which adapter to use. Required, with no default: a production image that forgets it
    # must fail to start, not serve fake drafts. "fake" never calls a real model (dev/tests).
    llm_provider: Literal["fake", "openai_compat"]
    llm_base_url: str | None = None  # host must be in EU_LLM_HOSTS
    llm_model: str | None = None
    llm_timeout_seconds: float = Field(default=30.0, gt=0)

    # Safety net against huge bodies sent to a paid LLM (413). Generous on purpose: the full
    # assessment of every framework is estimated at ~150 KB. The real per-model budget belongs
    # in the core's AI Gateway, which knows the model's context window.
    max_request_bytes: int = Field(default=262_144, gt=0)

    # OpenBao (NFR09). Dev values are seeded by deploy/openbao/init.sh.
    bao_addr: str = "http://localhost:8200"
    bao_token: SecretStr | None = None
    bao_secret_path: str = "alinha/ai-service"  # KV v2 path under the "secret" mount
    bao_api_key_field: str = "llm.api-key"
    # Shared with the core's AI Gateway, which sends it as "Authorization: Bearer ...".
    bao_service_token_field: str = "service.token"

    @model_validator(mode="after")
    def _check(self) -> "Settings":
        # Every provider needs OpenBao: the service token the AI Gateway sends is read there.
        if not self.bao_token:
            raise ValueError(
                "BAO_TOKEN is required for every LLM_PROVIDER: the service token is read from OpenBao"
            )
        if self.llm_provider == "fake":
            return self

        missing = [
            name
            for name, value in (("LLM_BASE_URL", self.llm_base_url), ("LLM_MODEL", self.llm_model))
            if not value
        ]
        if missing:
            raise ValueError(f"LLM_PROVIDER={self.llm_provider} requires: {', '.join(missing)}")
        assert self.llm_base_url  # checked above; narrows the type
        _check_llm_url(self.llm_base_url)
        return self


def _check_llm_url(url: str) -> None:
    """The API key and client context go to this URL, so it must be an approved EU host over TLS."""
    parts = urlsplit(url)
    host = (parts.hostname or "").lower()
    if host not in EU_LLM_HOSTS:
        raise ValueError(
            f"LLM_BASE_URL host '{host}' is not in EU_LLM_HOSTS (config.py): "
            "the LLM must process data in the EU"
        )
    if parts.username is not None or parts.password is not None:
        raise ValueError("LLM_BASE_URL must not contain credentials; the API key comes from OpenBao")

    local = host in LOCAL_LLM_HOSTS
    if local:
        # Plain HTTP only where the traffic never leaves the machine.
        if parts.scheme not in ("http", "https"):
            raise ValueError("LLM_BASE_URL must use http or https")
    elif parts.scheme != "https":
        raise ValueError(f"LLM_BASE_URL must use https for '{host}'")

    try:
        port = parts.port
    except ValueError:
        raise ValueError("LLM_BASE_URL has an invalid port") from None
    if not local and port not in (None, 443):
        raise ValueError(f"LLM_BASE_URL must use the default https port for '{host}'")


@lru_cache
def get_settings() -> Settings:
    return Settings()
