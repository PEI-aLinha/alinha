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
})


class Settings(BaseSettings):
    # Validation errors must never echo the env values: BAO_TOKEN would end up in the logs.
    model_config = SettingsConfigDict(hide_input_in_errors=True)

    # Which adapter to use. Required, with no default: a production image that forgets it
    # must fail to start, not serve fake drafts. "fake" never calls a real model (dev/tests).
    llm_provider: Literal["fake", "openai_compat"]
    llm_base_url: str | None = None  # host must be in EU_LLM_HOSTS
    llm_model: str | None = None
    llm_timeout_seconds: float = Field(default=30.0, gt=0)

    # OpenBao (NFR09). Dev values are seeded by deploy/openbao/init.sh.
    bao_addr: str = "http://localhost:8200"
    bao_token: SecretStr | None = None
    bao_secret_path: str = "alinha/ai-service"  # KV v2 path under the "secret" mount
    bao_api_key_field: str = "llm.api-key"
    # Shared with the core's AI Gateway, which sends it as "Authorization: Bearer ...".
    bao_service_token_field: str = "service.token"

    @model_validator(mode="after")
    def _real_provider_needs_config(self) -> "Settings":
        if self.llm_provider != "fake":
            missing = [
                name
                for name, value in (
                    ("LLM_BASE_URL", self.llm_base_url),
                    ("LLM_MODEL", self.llm_model),
                    ("BAO_TOKEN", self.bao_token),
                )
                if not value
            ]
            if missing:
                raise ValueError(
                    f"LLM_PROVIDER={self.llm_provider} requires: {', '.join(missing)}"
                )
            assert self.llm_base_url  # checked above; narrows the type
            host = (urlsplit(self.llm_base_url).hostname or "").lower()
            if host not in EU_LLM_HOSTS:
                raise ValueError(
                    f"LLM_BASE_URL host '{host}' is not in EU_LLM_HOSTS (config.py): "
                    "the LLM must process data in the EU"
                )
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
