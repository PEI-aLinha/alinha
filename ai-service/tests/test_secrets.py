from unittest.mock import MagicMock

import pytest
import requests
from hvac.exceptions import Forbidden, InternalServerError, InvalidPath
from pydantic import SecretStr

from ai_service import secrets
from ai_service.config import Settings
from ai_service.secrets import SecretsError, read_llm_api_key, read_service_token

TOKEN = "s.super-secret-token"


def settings(**overrides):
    values = {"bao_addr": "http://bao:8200", "bao_token": SecretStr(TOKEN)} | overrides
    return Settings(**values)


@pytest.fixture
def client_cls(monkeypatch):
    """Replaces hvac.Client; tests set what read_secret_version returns or raises."""
    cls = MagicMock()
    monkeypatch.setattr(secrets.hvac, "Client", cls)
    return cls


def read_returns(client_cls, data):
    client_cls.return_value.secrets.kv.v2.read_secret_version.return_value = {
        "data": {"data": data}
    }


def read_raises(client_cls, error):
    client_cls.return_value.secrets.kv.v2.read_secret_version.side_effect = error


def test_reads_key_from_kv_v2(client_cls):
    read_returns(client_cls, {"llm.api-key": "sk-live"})

    key = read_llm_api_key(settings())

    assert isinstance(key, SecretStr) and key.get_secret_value() == "sk-live"
    client_cls.assert_called_once_with(url="http://bao:8200", token=TOKEN, timeout=5)
    client_cls.return_value.secrets.kv.v2.read_secret_version.assert_called_once_with(
        path="alinha/ai-service", mount_point="secret", raise_on_deleted_version=True
    )


def test_reads_service_token_from_the_same_path(client_cls):
    read_returns(client_cls, {"llm.api-key": "sk-live", "service.token": "svc"})

    token = read_service_token(settings())

    assert isinstance(token, SecretStr) and token.get_secret_value() == "svc"
    kwargs = client_cls.return_value.secrets.kv.v2.read_secret_version.call_args.kwargs
    assert kwargs["path"] == "alinha/ai-service"


def test_missing_service_token_field(client_cls):
    read_returns(client_cls, {"llm.api-key": "sk-live"})

    with pytest.raises(SecretsError, match="'service.token' is missing or empty"):
        read_service_token(settings())


def test_uses_configured_path_and_field(client_cls):
    read_returns(client_cls, {"other-key": "sk-2"})

    key = read_llm_api_key(settings(bao_secret_path="alinha/other", bao_api_key_field="other-key"))

    assert key.get_secret_value() == "sk-2"
    kwargs = client_cls.return_value.secrets.kv.v2.read_secret_version.call_args.kwargs
    assert kwargs["path"] == "alinha/other"


def test_missing_token_fails_without_contacting_openbao(client_cls):
    with pytest.raises(SecretsError, match="BAO_TOKEN is not set"):
        read_llm_api_key(settings(bao_token=None))

    client_cls.assert_not_called()


@pytest.mark.parametrize("error,message", [
    (InvalidPath("gone"), "no secret at secret/alinha/ai-service"),
    (Forbidden("denied"), "may not read"),
    (InternalServerError("boom"), "OpenBao returned an error"),
    (requests.ConnectionError("refused"), "cannot reach OpenBao at http://bao:8200"),
    (requests.Timeout("slow"), "cannot reach OpenBao"),
])
def test_openbao_failures_become_secrets_error(client_cls, error, message):
    read_raises(client_cls, error)

    with pytest.raises(SecretsError, match=message) as exc:
        read_llm_api_key(settings())

    assert exc.value.__cause__ is error
    assert TOKEN not in str(exc.value)


@pytest.mark.parametrize("data", [{}, {"llm.api-key": ""}, {"llm.api-key": None}])
def test_missing_or_empty_field(client_cls, data):
    read_returns(client_cls, data)

    with pytest.raises(SecretsError, match="'llm.api-key' is missing or empty"):
        read_llm_api_key(settings())
