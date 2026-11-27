"""The worker's side of contracts/jobs, checked against the examples the core's tests also use (contracts/README.md)."""
import json
import re
from pathlib import Path

import jsonschema
import pytest

from alinha_job_consumer import contracts

EXAMPLES = Path(__file__).resolve().parents[3] / "contracts" / "jobs" / "examples"
RESULT = contracts.RESULT_SCHEMA.removesuffix(".schema.json")


def _examples(valid: bool) -> list[pytest.param]:
    folder = "valid" if valid else "invalid"
    files = sorted(EXAMPLES.glob(f"*/{folder}/*.json"))
    return [pytest.param(f.parent.parent.name, f, id=f"{f.parent.parent.name}/{f.name}") for f in files]


def _load(file: Path) -> dict:
    return json.loads(file.read_text(encoding="utf-8"))


def _expected_errors(contract: str) -> dict[str, list[str]]:
    return _load(EXAMPLES / contract / "expected-errors.json")


def _describe(error: jsonschema.ValidationError) -> str:
    """`location: keyword`, plus the property for `required`: the same text the core's validator gives."""
    text = "/" + "/".join(str(part) for part in error.absolute_path) + ": " + error.validator
    if error.validator == "required":
        text += " " + re.match(r"'([^']+)' is a required property", error.message)[1]
    return text


def _validate(contract: str, document: dict) -> None:
    """Through the same functions the consumer and the result publisher call."""
    if contract == RESULT:
        contracts.validate_result(document)
        return
    # The consumer picks the schema from the stream it reads, never from the document: same here, from the folder.
    # job_schema() only turns dots into dashes, so the folder's base name maps to the same file.
    base, version = contract.rsplit(".v", 1)
    contracts.validate_envelope(document, base, int(version))


@pytest.mark.parametrize(("contract", "file"), _examples(valid=True))
def test_valid_example_is_accepted(contract, file):
    _validate(contract, _load(file))


@pytest.mark.parametrize(("contract", "file"), _examples(valid=False))
def test_invalid_example_is_refused_for_its_reason(contract, file):
    document = _load(file)
    with pytest.raises(jsonschema.ValidationError):
        _validate(contract, document)
    # Every violation, not just the first one validate() raises: refused for the reason the example exists.
    schema = _load(contracts.contracts_dir() / f"{contract}.schema.json")
    validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
    assert sorted({_describe(e) for e in validator.iter_errors(document)}) == sorted(_expected_errors(contract)[file.name])


def test_every_job_contract_has_valid_and_invalid_examples():
    schemas = {p.name.removesuffix(".schema.json") for p in EXAMPLES.parent.glob("*.schema.json")}
    for contract in schemas:
        assert list((EXAMPLES / contract / "valid").glob("*.json")), contract
        invalid = {f.name for f in (EXAMPLES / contract / "invalid").glob("*.json")}
        assert invalid, contract
        assert set(_expected_errors(contract)) == invalid, contract
