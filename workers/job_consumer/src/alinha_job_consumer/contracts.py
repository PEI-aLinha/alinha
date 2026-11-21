"""Load and validate the job JSON Schemas from contracts/jobs."""
from __future__ import annotations

import json
import os
from functools import lru_cache
from pathlib import Path
from typing import Any

import jsonschema

RESULT_SCHEMA = "job-result.v1.schema.json"


def contracts_dir() -> Path:
    """`CONTRACTS_DIR` in the image, the repo path in development."""
    configured = os.environ.get("CONTRACTS_DIR")
    if configured:
        return Path(configured)
    repo = Path(__file__).resolve().parents[4] / "contracts" / "jobs"
    return repo if repo.is_dir() else Path("/app/contracts/jobs")


def job_schema(type_: str, version: int) -> str:
    return f"{type_.replace('.', '-')}.v{version}.schema.json"


@lru_cache
def _validator(name: str) -> jsonschema.Draft202012Validator:
    schema = json.loads((contracts_dir() / name).read_text(encoding="utf-8"))
    return jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())


def load(job_types: tuple[tuple[str, int], ...]) -> None:
    """Load every schema the worker will need, so a bad config fails at startup instead of on a message."""
    for name in (RESULT_SCHEMA, *(job_schema(type_, version) for type_, version in job_types)):
        try:
            _validator(name)
        except FileNotFoundError as error:
            raise ValueError(f"no contract {name} in {contracts_dir()}") from error


def validate_envelope(envelope: Any, type_: str, version: int) -> None:
    """Raise `jsonschema.ValidationError` when the envelope does not match its contract."""
    _validator(job_schema(type_, version)).validate(envelope)


def validate_result(result: Any) -> None:
    """Raise `jsonschema.ValidationError` when the result does not match job-result.v1."""
    _validator(RESULT_SCHEMA).validate(result)
