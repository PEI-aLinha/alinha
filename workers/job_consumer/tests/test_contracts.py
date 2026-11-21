import uuid

import jsonschema
import pytest

from alinha_job_consumer import contracts


def envelope(**overrides):
    value = {
        "jobId": str(uuid.uuid4()),
        "type": "evidence.collect",
        "tenantId": str(uuid.uuid4()),
        "provider": "code-repository",
        "connectionRef": "bao://tenant/secret",
        "requestedAt": "2026-11-18T10:00:00Z",
    }
    value.update(overrides)
    return value


def test_accepts_a_valid_envelope():
    contracts.validate_envelope(envelope(), "evidence.collect", 1)


def test_rejects_an_envelope_missing_a_provider():
    invalid = envelope()
    del invalid["provider"]
    with pytest.raises(jsonschema.ValidationError):
        contracts.validate_envelope(invalid, "evidence.collect", 1)


def test_rejects_a_non_uuid_job_id():
    with pytest.raises(jsonschema.ValidationError):
        contracts.validate_envelope(envelope(jobId="1"), "evidence.collect", 1)


def test_rejects_a_bad_timestamp():
    with pytest.raises(jsonschema.ValidationError):
        contracts.validate_envelope(envelope(requestedAt="not-a-date"), "evidence.collect", 1)


def test_rejects_a_missing_contract_file():
    with pytest.raises(FileNotFoundError):
        contracts.validate_envelope(envelope(), "no.such.type", 1)
