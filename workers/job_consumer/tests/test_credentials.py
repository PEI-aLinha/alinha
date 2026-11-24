import json
import uuid

import pytest

from alinha_job_consumer.collectors import CollectorError, TransientError
from alinha_job_consumer.credentials import _fetch, connection_ref, platform_secret, provider_secret

TENANT = str(uuid.uuid4())


def envelope(**overrides):
    value = {
        "jobId": str(uuid.uuid4()),
        "type": "evidence.collect",
        "tenantId": TENANT,
        "provider": "code-repository",
        "connectionRef": "providers/code-repository",
        "requestedAt": "2026-11-24T10:00:00Z",
    }
    value.update(overrides)
    return value


@pytest.fixture(autouse=True)
def openbao_env(monkeypatch):
    monkeypatch.setenv("BAO_ADDR", "http://openbao:8200/")
    monkeypatch.setenv("BAO_TOKEN", "worker-token")


def answering(status, data=None):
    calls = []

    def fetch(url, token):
        calls.append((url, token))
        body = json.dumps({"data": {"data": data}}).encode() if data is not None else b""
        return status, body

    fetch.calls = calls
    return fetch


def test_reads_the_tenants_credential_for_its_own_provider():
    fetch = answering(200, {"token": "ghp_x", "host": "github"})

    secret = provider_secret(envelope(), fetch)

    assert secret == {"token": "ghp_x", "host": "github"}
    assert fetch.calls == [
        (f"http://openbao:8200/v1/secret/data/tenants/{TENANT}/providers/code-repository", "worker-token")
    ]


def test_connection_ref_is_the_provider_path():
    assert connection_ref("code-repository") == "providers/code-repository"


@pytest.mark.parametrize(
    "ref",
    [
        "providers/external-surface",
        f"tenants/{uuid.uuid4()}/providers/code-repository",
        "providers/code-repository/../../alinha/app",
        "bao://tenant/secret",
    ],
)
def test_refuses_a_ref_that_is_not_its_own_provider(ref):
    fetch = answering(200, {"token": "x"})

    with pytest.raises(CollectorError) as error:
        provider_secret(envelope(connectionRef=ref), fetch)

    assert error.value.code == "invalid_connection_ref"
    assert fetch.calls == []


@pytest.mark.parametrize(
    "overrides",
    [{"tenantId": "not-a-uuid"}, {"tenantId": None}, {"provider": "../alinha"}, {"provider": "Code"}],
)
def test_refuses_an_envelope_without_a_valid_tenant_or_provider(overrides):
    with pytest.raises(CollectorError) as error:
        provider_secret(envelope(**overrides), answering(200, {}))

    assert error.value.code == "invalid_connection_ref"


@pytest.mark.parametrize("missing", ["BAO_ADDR", "BAO_TOKEN"])
def test_unconfigured_worker_is_a_final_error(monkeypatch, missing):
    monkeypatch.delenv(missing)

    with pytest.raises(CollectorError) as error:
        provider_secret(envelope(), answering(200, {}))

    assert error.value.code == "secrets_unavailable"


@pytest.mark.parametrize(
    ("status", "code"),
    [(404, "connection_not_found"), (403, "secrets_unavailable"), (401, "secrets_unavailable"),
     (400, "secrets_unavailable")],
)
def test_openbao_refusals_are_final(status, code):
    with pytest.raises(CollectorError) as error:
        provider_secret(envelope(), answering(status))

    assert error.value.code == code


@pytest.mark.parametrize("status", [429, 500, 503])
def test_openbao_outages_are_retryable(status):
    with pytest.raises(TransientError):
        provider_secret(envelope(), answering(status))


@pytest.mark.parametrize("body", [b"not json", b"{}", json.dumps({"data": {"data": {"token": 1}}}).encode()])
def test_unexpected_bodies_are_final_and_never_echoed(body):
    with pytest.raises(CollectorError) as error:
        provider_secret(envelope(), lambda url, token: (200, body))

    assert error.value.code == "secrets_unavailable"
    assert "1" not in error.value.message and "not json" not in error.value.message


def test_unreachable_openbao_is_retryable():
    with pytest.raises(TransientError):
        _fetch("http://127.0.0.1:9/v1/secret/data/x", "token")


def test_reads_the_providers_platform_credential():
    fetch = answering(200, {"github_app_id": "1"})

    assert platform_secret("code-repository", fetch) == {"github_app_id": "1"}
    assert fetch.calls == [("http://openbao:8200/v1/secret/data/providers/code-repository", "worker-token")]


def test_missing_platform_credential_is_a_final_error():
    with pytest.raises(CollectorError) as error:
        platform_secret("code-repository", answering(404))
    assert error.value.code == "provider_not_configured"


@pytest.mark.parametrize("provider", ["", "../tenants", "Code", None])
def test_platform_credential_refuses_an_invalid_provider(provider):
    with pytest.raises(CollectorError) as error:
        platform_secret(provider, answering(200, {}))
    assert error.value.code == "invalid_connection_ref"
