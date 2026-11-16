"""Service configuration, read from environment variables.

Secrets are not configured here: the LLM API key is read from OpenBao
(see secrets.py). Only the address and token to reach OpenBao come from env.
"""

from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # Which adapter to use. "fake" never calls a real model.
    llm_provider: Literal["fake", "openai_compat"] = "fake"
    llm_base_url: str | None = None  # e.g. the EU endpoint of the chosen provider
    llm_model: str | None = None
    llm_timeout_seconds: float = Field(default=30.0, gt=0)

    # OpenBao (NFR09). Dev values are seeded by deploy/openbao/init.sh.
    bao_addr: str = "http://localhost:8200"
    bao_token: SecretStr | None = None
    bao_secret_path: str = "alinha/ai-service"  # KV v2 path under the "secret" mount
    bao_api_key_field: str = "llm.api-key"

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
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
