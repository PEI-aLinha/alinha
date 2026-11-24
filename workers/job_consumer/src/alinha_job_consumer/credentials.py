"""Read a provider's per-tenant credential from OpenBao (docs/jobs.md, "Credentials for a collector").

The path is built from the envelope's `tenantId` (stamped by the core) and `provider`, never from `connectionRef`,
so a payload cannot point a collector at another tenant's or another provider's secret. The worker's token can only
read `secret/data/tenants/+/providers/+` (deploy/openbao/alinha-worker.hcl).
"""
from __future__ import annotations

import json
import os
import re
import urllib.error
import urllib.request
import uuid
from typing import Any, Callable

from .collectors import CollectorError, TransientError

# Same rule as the app's OpenBaoTenantSecretStore.
_PROVIDER_ID = re.compile(r"[a-z0-9][a-z0-9-]{0,62}")
_TIMEOUT_S = 10.0

# (url, token) -> (status, body); replaced in tests.
Fetch = Callable[[str, str], tuple[int, bytes]]


def connection_ref(provider: str) -> str:
    """The only `connectionRef` a job for `provider` may carry."""
    return f"providers/{provider}"


def provider_secret(envelope: dict[str, Any], fetch: Fetch | None = None) -> dict[str, str]:
    """The credential the app stored for this job's tenant and provider.

    Raises `CollectorError` (not retryable) when the job does not reference its own provider's credential, when
    nothing is stored, or when the worker is not configured; `TransientError` when OpenBao does not answer.
    """
    tenant_id = envelope.get("tenantId")
    provider = envelope.get("provider")
    if not (isinstance(provider, str) and _PROVIDER_ID.fullmatch(provider) and _is_uuid(tenant_id)):
        raise CollectorError("invalid_connection_ref", "the job does not name a tenant and provider")
    if envelope.get("connectionRef") != connection_ref(provider):
        raise CollectorError("invalid_connection_ref", f"connectionRef must be {connection_ref(provider)}")

    address = os.environ.get("BAO_ADDR", "").rstrip("/")
    token = os.environ.get("BAO_TOKEN", "")
    if not (address and token):
        raise CollectorError("secrets_unavailable", "the worker has no OpenBao address or token")

    url = f"{address}/v1/secret/data/tenants/{tenant_id}/providers/{provider}"
    status, body = (fetch or _fetch)(url, token)
    if status == 404:
        raise CollectorError("connection_not_found", "no credential is stored for this provider")
    if status in (401, 403):
        raise CollectorError("secrets_unavailable", "the worker's OpenBao token was refused")
    if status >= 500 or status == 429:
        raise TransientError(f"OpenBao answered {status}")
    if status != 200:
        raise CollectorError("secrets_unavailable", f"OpenBao answered {status}")
    return _data(body)


def _data(body: bytes) -> dict[str, str]:
    try:
        data = json.loads(body)["data"]["data"]
    except (ValueError, KeyError, TypeError):
        raise CollectorError("secrets_unavailable", "OpenBao answered an unexpected body") from None
    if not isinstance(data, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in data.items()):
        raise CollectorError("secrets_unavailable", "the stored credential is not a map of strings")
    return data


def _fetch(url: str, token: str) -> tuple[int, bytes]:
    request = urllib.request.Request(url, headers={"X-Vault-Token": token}, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=_TIMEOUT_S) as response:  # noqa: S310 - URL from BAO_ADDR
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, b""
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise TransientError("OpenBao is unreachable") from error


def _is_uuid(value: Any) -> bool:
    try:
        uuid.UUID(str(value))
    except ValueError:
        return False
    return isinstance(value, str)
