"""Consumer-group loop: read, validate, dispatch to a collector, publish one result per attempt."""
from __future__ import annotations

import json
import logging
import os
import socket
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable

import jsonschema
from redis.exceptions import RedisError, ResponseError

from . import collectors, contracts, results

log = logging.getLogger(__name__)

# Provider exception text can carry URLs or tokens; keep it in logs, never in the results stream.
SAFE_COLLECTOR_MESSAGE = "collector failed"


def stream_name(type_: str, version: int) -> str:
    return f"alinha:jobs:{type_}:v{version}"


def _env_int(name: str, default: int) -> int:
    return int(os.environ.get(name, default))


def _parse_job_types(value: str) -> tuple[tuple[str, int], ...]:
    parsed = []
    for item in value.split(","):
        type_, _, version = item.strip().partition(":")
        parsed.append((type_, int(version)))
    return tuple(parsed)


@dataclass(frozen=True)
class Config:
    redis_url: str = "redis://localhost:6379/0"
    job_types: tuple[tuple[str, int], ...] = (("evidence.collect", 1),)
    group: str = "alinha-workers"
    name: str = field(default_factory=socket.gethostname)
    block_ms: int = 5000
    batch: int = 10
    claim_idle_ms: int = 60000
    dedupe_ttl_s: int = 86400

    @classmethod
    def from_env(cls) -> "Config":
        return cls(
            redis_url=os.environ.get("REDIS_URL", "redis://localhost:6379/0"),
            job_types=_parse_job_types(os.environ.get("WORKER_JOB_TYPES", "evidence.collect:1")),
            group=os.environ.get("WORKER_GROUP", "alinha-workers"),
            name=os.environ.get("WORKER_NAME", socket.gethostname()),
            block_ms=_env_int("WORKER_BLOCK_MS", 5000),
            batch=_env_int("WORKER_BATCH", 10),
            claim_idle_ms=_env_int("WORKER_CLAIM_IDLE_MS", 60000),
            dedupe_ttl_s=_env_int("WORKER_DEDUPE_TTL_S", 86400),
        )

    @property
    def streams(self) -> dict[str, tuple[str, int]]:
        return {stream_name(type_, version): (type_, version) for type_, version in self.job_types}


class Consumer:
    def __init__(
        self,
        redis: Any,
        config: Config | None = None,
        collector_lookup: Callable[[str], collectors.Collector | None] = collectors.get_collector,
    ) -> None:
        self.redis = redis
        self.config = config or Config.from_env()
        self._streams = self.config.streams
        self._collector_lookup = collector_lookup
        self._last_claim = 0.0

    def ensure_groups(self) -> None:
        for stream in self._streams:
            try:
                self.redis.xgroup_create(stream, self.config.group, id="0", mkstream=True)
            except ResponseError as error:
                if "BUSYGROUP" not in str(error):
                    raise

    def run(self) -> None:
        self.ensure_groups()
        while True:
            try:
                self.consume_once()
                if time.monotonic() - self._last_claim >= self.config.claim_idle_ms / 1000:
                    self.reclaim()
            except ResponseError as error:
                if "NOGROUP" in str(error):
                    self.ensure_groups()
                else:
                    log.warning("Redis rejected the command, retrying", exc_info=True)
                    time.sleep(1)
            except RedisError:
                log.warning("Redis unavailable, retrying", exc_info=True)
                time.sleep(1)

    def consume_once(self) -> None:
        response = self.redis.xreadgroup(
            self.config.group,
            self.config.name,
            {stream: ">" for stream in self._streams},
            count=self.config.batch,
            block=self.config.block_ms,
        )
        for stream, entries in response or []:
            for entry_id, fields in entries:
                self.handle_entry(stream, entry_id, fields)

    def reclaim(self) -> None:
        """Take over entries left pending by a worker that died."""
        self._last_claim = time.monotonic()
        for stream in self._streams:
            cursor = "0-0"
            while True:
                cursor, entries, _ = self.redis.xautoclaim(
                    stream,
                    self.config.group,
                    self.config.name,
                    min_idle_time=self.config.claim_idle_ms,
                    start_id=cursor,
                    count=self.config.batch,
                )
                for entry_id, fields in entries:
                    self.handle_entry(stream, entry_id, fields)
                if cursor == "0-0":
                    break

    def handle_entry(self, stream: str, entry_id: str, fields: dict[str, str] | None) -> None:
        type_, version = self._streams[stream]
        fields = fields or {}
        envelope = _json(fields.get("envelope"))
        attempt = _attempt(fields.get("attempt"))
        job_id = envelope.get("jobId") if isinstance(envelope, dict) else None
        tenant_id = envelope.get("tenantId") if isinstance(envelope, dict) else None
        if not (_uuid(job_id) and _uuid(tenant_id) and attempt >= 1):
            # Without a valid jobId/tenantId no contract-valid result exists; the core would dead-letter it.
            log.error("dropping %s from %s: no usable jobId, tenantId or attempt", entry_id, stream)
            self._ack(stream, entry_id)
            return

        dedupe_key = f"alinha:worker:done:{job_id}:{attempt}"
        if self.redis.exists(dedupe_key):
            self._ack(stream, entry_id)
            return

        try:
            contracts.validate_envelope(envelope, type_, version)
        except jsonschema.ValidationError as error:
            outcome = results.failed(envelope, type_, version, attempt, "invalid_payload", results.safe_message(error))
        else:
            outcome = self._run_collector(envelope, type_, version, attempt)

        self._publish(outcome, envelope, type_, version, attempt)
        self.redis.set(dedupe_key, 1, ex=self.config.dedupe_ttl_s)
        self._ack(stream, entry_id)

    def _run_collector(
        self, envelope: dict[str, Any], type_: str, version: int, attempt: int
    ) -> dict[str, Any]:
        try:
            collector = self._collector_lookup(envelope["provider"])
            if collector is None:
                return results.failed(
                    envelope, type_, version, attempt, "unknown_provider", f"no collector for {envelope['provider']}"
                )
            output = collector(envelope)
        except collectors.TransientError as error:
            log.warning("collector for %s transient failure: %s", envelope["provider"], error)
            return results.failed(
                envelope, type_, version, attempt, "transient_error", SAFE_COLLECTOR_MESSAGE, retryable=True
            )
        except Exception:  # collector bugs and bad credentials are not retryable
            log.exception("collector for %s failed", envelope["provider"])
            return results.failed(envelope, type_, version, attempt, "collector_error", SAFE_COLLECTOR_MESSAGE)
        return results.completed(envelope, type_, version, attempt, output)

    def _publish(
        self, outcome: dict[str, Any], envelope: dict[str, Any], type_: str, version: int, attempt: int
    ) -> None:
        try:
            results.publish(self.redis, outcome)
        except results.InvalidResult:
            log.exception("collector output does not match job-result.v1")
            try:
                results.publish(
                    self.redis,
                    results.failed(
                        envelope, type_, version, attempt, "invalid_result", "worker produced an invalid result"
                    ),
                )
            except results.InvalidResult:
                log.exception("no valid result could be produced for %s attempt %s", envelope.get("jobId"), attempt)

    def _ack(self, stream: str, entry_id: str) -> None:
        self.redis.xack(stream, self.config.group, entry_id)


def _json(text: str | None) -> Any:
    try:
        return json.loads(text or "")
    except json.JSONDecodeError:
        return None


def _attempt(text: str | None) -> int:
    try:
        return int(text or 0)
    except ValueError:
        return 0


def _uuid(value: Any) -> bool:
    try:
        uuid.UUID(str(value))
    except ValueError:
        return False
    return True
