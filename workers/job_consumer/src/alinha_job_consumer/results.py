"""Build job-result.v1 messages and publish them to the results stream."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

import jsonschema

from . import contracts

RESULTS_STREAM = "alinha:jobs:results"


class InvalidResult(ValueError):
    """The worker produced a result that does not match job-result.v1; nothing was published."""


def _finished_at() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def completed(envelope: dict[str, Any], type_: str, version: int, attempt: int, result: Any) -> dict[str, Any]:
    return {
        "jobId": envelope["jobId"],
        "type": type_,
        "version": version,
        "tenantId": envelope["tenantId"],
        "attempt": attempt,
        "status": "completed",
        "result": result,
        "finishedAt": _finished_at(),
    }


def failed(
    envelope: dict[str, Any],
    type_: str,
    version: int,
    attempt: int,
    code: str,
    message: str,
    retryable: bool = False,
) -> dict[str, Any]:
    return {
        "jobId": envelope["jobId"],
        "type": type_,
        "version": version,
        "tenantId": envelope["tenantId"],
        "attempt": attempt,
        "status": "failed",
        "retryable": retryable,
        "error": {"code": code, "message": message[:2000]},
        "finishedAt": _finished_at(),
    }


def publish(redis: Any, result: dict[str, Any]) -> None:
    """Validate then XADD. Raises `InvalidResult` before writing, or the Redis error."""
    try:
        contracts.validate_result(result)
    except jsonschema.ValidationError as error:
        raise InvalidResult(error.message) from error
    redis.xadd(RESULTS_STREAM, {"result": json.dumps(result)})
