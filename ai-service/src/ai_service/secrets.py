"""Reads the AI Service secrets from OpenBao (NFR09): the LLM API key and the service token.

Called once at startup. Secrets never come from code, env or versioned config;
only the address and token to reach OpenBao do (see config.py).
"""

import hvac
import requests
from hvac.exceptions import Forbidden, InvalidPath, VaultError
from pydantic import SecretStr

from ai_service.config import Settings

KV_MOUNT = "secret"


class SecretsError(RuntimeError):
    """The secret could not be read. The service must not start without it."""


def read_llm_api_key(settings: Settings) -> SecretStr:
    return _read_field(settings, settings.bao_api_key_field)


def read_service_token(settings: Settings) -> SecretStr:
    """The token the AI Gateway must present on every /v1 request."""
    return _read_field(settings, settings.bao_service_token_field)


def _read_field(settings: Settings, field: str) -> SecretStr:
    if settings.bao_token is None:
        raise SecretsError("BAO_TOKEN is not set")

    client = hvac.Client(
        url=settings.bao_addr,
        token=settings.bao_token.get_secret_value(),
        timeout=5,
    )

    try:
        response = client.secrets.kv.v2.read_secret_version(
            path=settings.bao_secret_path,
            mount_point=KV_MOUNT,
            raise_on_deleted_version=True,
        )
    except InvalidPath as e:
        raise SecretsError(f"no secret at {KV_MOUNT}/{settings.bao_secret_path}") from e
    except Forbidden as e:
        raise SecretsError("BAO_TOKEN may not read the AI Service secret") from e
    except VaultError as e:
        raise SecretsError(f"OpenBao returned an error ({type(e).__name__})") from e
    except requests.RequestException as e:
        raise SecretsError(f"cannot reach OpenBao at {settings.bao_addr}") from e

    data = response["data"]["data"]
    value = data.get(field)
    if not value:
        raise SecretsError(f"field '{field}' is missing or empty")
    return SecretStr(value)