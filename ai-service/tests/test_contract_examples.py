"""The AI Service's side of contracts/ai, checked against the examples the core's tests also use (contracts/README.md)."""
import json
import re
from pathlib import Path

import pytest
import yaml
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator, FormatChecker
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


def invalid_examples(schema: str) -> list[pytest.param]:
    """(document, expected violations) per invalid example, from the schema folder's expected-errors.json."""
    expected = json.loads((EXAMPLES / schema / "expected-errors.json").read_text(encoding="utf-8"))
    files = sorted((EXAMPLES / schema / "invalid").glob("*.json"))
    assert set(expected) == {f.name for f in files}, f"{schema}/expected-errors.json must list every invalid example"
    return [pytest.param(json.loads(f.read_text(encoding="utf-8")), expected[f.name], id=f.name) for f in files]


def violations(schema: str, document) -> list[str]:
    """`location: keyword`, plus the property for `required`: the same text the core's validator gives."""
    found = set()
    for error in validator(schema).iter_errors(document):
        text = "/" + "/".join(str(part) for part in error.absolute_path) + ": " + error.validator
        if error.validator == "required":
            text += " " + re.match(r"'([^']+)' is a required property", error.message)[1]
        found.add(text)
    return sorted(found)


def post(body):
    with TestClient(create_app(provider=FakeProvider(), service_token=SecretStr(TOKEN))) as c:
        return c.post("/v1/drafts", json=body, headers={"Authorization": f"Bearer {TOKEN}"})


@pytest.mark.parametrize("body", examples("DraftRequest", valid=True))
def test_valid_request_example_gets_a_draft_matching_the_contract(body):
    validator("DraftRequest").validate(body)

    r = post(body)

    assert r.status_code == 200
    validator("AiDraft").validate(r.json())


@pytest.mark.parametrize(("body", "expected"), invalid_examples("DraftRequest"))
def test_invalid_request_example_is_refused(body, expected):
    assert violations("DraftRequest", body) == sorted(expected)

    r = post(body)

    assert r.status_code == 422
    assert r.json()["code"] == "invalid_request"
    validator("Error").validate(r.json())


@pytest.mark.parametrize("draft", examples("AiDraft", valid=True))
def test_valid_draft_example_matches_the_contract(draft):
    validator("AiDraft").validate(draft)


@pytest.mark.parametrize(("draft", "expected"), invalid_examples("AiDraft"))
def test_the_service_cannot_produce_an_invalid_draft_example(draft, expected):
    assert violations("AiDraft", draft) == sorted(expected)
    # The response model is what the service sends: it must refuse every draft the contract refuses.
    with pytest.raises(ModelError):
        AiDraft.model_validate(draft)
