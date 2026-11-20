"""Build job-result.v1 messages and publish them to the results stream."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

import jsonschema

from . import contracts

RESULTS_STREAM = "alinha:jobs:results"
# The core dead-letters larger `result` fields without reading them (docs/jobs.md, results.max-result-bytes).
MAX_RESULT_BYTES = 1048576


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
        payload = json.dumps(result, allow_nan=False)
    except jsonschema.ValidationError as error:
        raise InvalidResult(safe_message(error)) from error
    except (TypeError, ValueError) as error:
        raise InvalidResult("result is not JSON-serialisable") from error
    if len(payload.encode()) > MAX_RESULT_BYTES:
        raise InvalidResult(f"result is larger than {MAX_RESULT_BYTES} bytes")
    redis.xadd(RESULTS_STREAM, {"result": payload})


def safe_message(error: jsonschema.ValidationError) -> str:
    """Location and keyword only: `error.message` embeds the offending value, which may be a credential."""
    return f"{error.json_path}: failed '{error.validator}'"
