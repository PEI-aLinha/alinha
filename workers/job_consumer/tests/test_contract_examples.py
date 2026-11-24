"""The worker's side of contracts/jobs, checked against the examples the core's tests also use (contracts/README.md)."""
import json
from pathlib import Path

import jsonschema
import pytest

from alinha_job_consumer import contracts

EXAMPLES = Path(__file__).resolve().parents[3] / "contracts" / "jobs" / "examples"
RESULT = contracts.RESULT_SCHEMA.removesuffix(".schema.json")


def _examples(valid: bool) -> list[pytest.param]:
    folder = "valid" if valid else "invalid"
    files = sorted(EXAMPLES.glob(f"*/{folder}/*.json"))
    return [pytest.param(f.parent.parent.name, json.loads(f.read_text(encoding="utf-8")), id=f"{f.parent.parent.name}/{f.name}") for f in files]


def _validate(contract: str, document: dict) -> None:
    """Through the same functions the consumer and the result publisher call."""
    if contract == RESULT:
        contracts.validate_result(document)
        return
    # The consumer picks the schema from the stream it reads, never from the document: same here, from the folder.
    # job_schema() only turns dots into dashes, so the folder's base name maps to the same file.
    base, version = contract.rsplit(".v", 1)
    contracts.validate_envelope(document, base, int(version))


@pytest.mark.parametrize(("contract", "document"), _examples(valid=True))
def test_valid_example_is_accepted(contract, document):
    _validate(contract, document)


@pytest.mark.parametrize(("contract", "document"), _examples(valid=False))
def test_invalid_example_is_refused(contract, document):
    with pytest.raises(jsonschema.ValidationError):
        _validate(contract, document)


def test_every_job_contract_has_valid_and_invalid_examples():
    schemas = {p.name.removesuffix(".schema.json") for p in EXAMPLES.parent.glob("*.schema.json")}
    for contract in schemas:
        assert list((EXAMPLES / contract / "valid").glob("*.json")), contract
        assert list((EXAMPLES / contract / "invalid").glob("*.json")), contract
