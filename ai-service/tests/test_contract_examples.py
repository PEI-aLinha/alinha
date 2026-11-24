"""The AI Service's side of contracts/ai, checked against the examples the core's tests also use (contracts/README.md)."""
import json
from pathlib import Path

import pytest
import yaml
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from pydantic import SecretStr
from pydantic import ValidationError as ModelError

from ai_service.drafts import AiDraft
from ai_service.main import create_app
from ai_service.providers.fake import FakeProvider

CONTRACTS = Path(__file__).resolve().parents[2] / "contracts" / "ai"
SCHEMAS = yaml.safe_load((CONTRACTS / "ai-service.v1.yaml").read_text())["components"]["schemas"]
EXAMPLES = CONTRACTS / "examples" / "ai-service.v1"
TOKEN = "test-service-token"


def validator(schema: str) -> Draft202012Validator:
    return Draft202012Validator(SCHEMAS[schema], format_checker=FormatChecker())


def examples(schema: str, valid: bool) -> list[pytest.param]:
    folder = EXAMPLES / schema / ("valid" if valid else "invalid")
    files = sorted(folder.glob("*.json"))
    assert files, f"no examples in {folder}"
    return [pytest.param(json.loads(f.read_text(encoding="utf-8")), id=f.name) for f in files]


def post(body):
    with TestClient(create_app(provider=FakeProvider(), service_token=SecretStr(TOKEN))) as c:
        return c.post("/v1/drafts", json=body, headers={"Authorization": f"Bearer {TOKEN}"})


@pytest.mark.parametrize("body", examples("DraftRequest", valid=True))
def test_valid_request_example_gets_a_draft_matching_the_contract(body):
    validator("DraftRequest").validate(body)

    r = post(body)

    assert r.status_code == 200
    validator("AiDraft").validate(r.json())


@pytest.mark.parametrize("body", examples("DraftRequest", valid=False))
def test_invalid_request_example_is_refused(body):
    with pytest.raises(ValidationError):
        validator("DraftRequest").validate(body)

    r = post(body)

    assert r.status_code == 422
    assert r.json()["code"] == "invalid_request"
    validator("Error").validate(r.json())


@pytest.mark.parametrize("draft", examples("AiDraft", valid=True))
def test_valid_draft_example_matches_the_contract(draft):
    validator("AiDraft").validate(draft)


@pytest.mark.parametrize("draft", examples("AiDraft", valid=False))
def test_the_service_cannot_produce_an_invalid_draft_example(draft):
    # The response model is what the service sends: it must refuse every draft the contract refuses.
    with pytest.raises(ValidationError):
        validator("AiDraft").validate(draft)
    with pytest.raises(ModelError):
        AiDraft.model_validate(draft)
