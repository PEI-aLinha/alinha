import json
import uuid
from datetime import datetime, timezone

import fakeredis
import pytest
from redis.exceptions import ConnectionError

from alinha_job_consumer import collectors, results
from alinha_job_consumer.consumer import Config, Consumer, _parse_job_types, stream_name

STREAM = stream_name("evidence.collect", 1)


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


@pytest.fixture
def redis():
    return fakeredis.FakeRedis(decode_responses=True)


def add_job(redis, job, attempt=1):
    return redis.xadd(STREAM, {"envelope": json.dumps(job), "attempt": str(attempt)})


def consumer(redis, collector):
    return Consumer(redis, Config(name="test"), collector_lookup=lambda provider: collector)


def run_once(worker):
    worker.ensure_groups()
    worker.consume_once()


def published(redis):
    return [json.loads(fields["result"]) for _, fields in redis.xrange("alinha:jobs:results")]


def pending(redis):
    return redis.xpending(STREAM, "alinha-workers")["pending"]


def test_happy_path_runs_the_collector_and_acks(redis):
    job = envelope()
    calls = []

    def collect(value):
        calls.append(value)
        return {"items": []}

    add_job(redis, job)
    run_once(consumer(redis, collect))

    assert calls == [job]
    [result] = published(redis)
    assert result["status"] == "completed"
    assert result["jobId"] == job["jobId"]
    assert result["tenantId"] == job["tenantId"]
    assert result["attempt"] == 1
    assert result["version"] == 1
    assert pending(redis) == 0


def test_unknown_provider_is_a_non_retryable_failure(redis):
    add_job(redis, envelope())
    run_once(consumer(redis, None))

    [result] = published(redis)
    assert result["status"] == "failed"
    assert result["error"]["code"] == "unknown_provider"
    assert result.get("retryable", False) is False
    assert pending(redis) == 0


def test_transient_error_is_retryable(redis):
    def collect(_):
        raise collectors.TransientError("connection reset")

    add_job(redis, envelope())
    run_once(consumer(redis, collect))

    [result] = published(redis)
    assert result["status"] == "failed"
    assert result["retryable"] is True
    assert result["error"]["code"] == "transient_error"
    assert result["error"]["message"] == "collector failed"
    assert "connection reset" not in result["error"]["message"]
    assert pending(redis) == 0


def test_other_collector_errors_are_not_retryable(redis):
    def collect(_):
        raise ValueError("bad credentials")

    add_job(redis, envelope())
    run_once(consumer(redis, collect))

    [result] = published(redis)
    assert result["status"] == "failed"
    assert result["retryable"] is False
    assert result["error"]["message"] == "collector failed"
    assert "bad credentials" not in result["error"]["message"]


def test_a_broken_provider_package_does_not_crash_the_worker(redis):
    def broken_lookup(_):
        raise ImportError("half-installed provider at https://user:token@example.org")

    worker = Consumer(redis, Config(name="test"), collector_lookup=broken_lookup)
    add_job(redis, envelope())
    run_once(worker)

    [result] = published(redis)
    assert result["status"] == "failed"
    assert result["error"]["code"] == "collector_error"
    assert "half-installed provider" not in result["error"]["message"]
    assert "user:token" not in result["error"]["message"]
    assert pending(redis) == 0


def test_an_invalid_fallback_result_leaves_the_entry_pending(redis, monkeypatch):
    def always_invalid(*args, **kwargs):
        raise results.InvalidResult("invalid")

    monkeypatch.setattr(results, "publish", always_invalid)
    job = envelope()
    add_job(redis, job)
    run_once(consumer(redis, lambda _: {"items": []}))

    assert published(redis) == []
    assert pending(redis) == 1
    assert not redis.exists(f"alinha:worker:done:{job['jobId']}:1")


def test_invalid_envelope_is_not_retried(redis):
    job = envelope(provider=None)
    add_job(redis, job)
    run_once(consumer(redis, lambda _: {"items": []}))

    [result] = published(redis)
    assert result["status"] == "failed"
    assert result["error"]["code"] == "invalid_payload"
    assert result["jobId"] == job["jobId"]
    assert pending(redis) == 0


def test_invalid_collector_output_becomes_a_failure(redis):
    add_job(redis, envelope())
    run_once(consumer(redis, lambda _: "not an object"))

    [result] = published(redis)
    assert result["status"] == "failed"
    assert result["error"]["code"] == "invalid_result"
    assert pending(redis) == 0


@pytest.mark.parametrize(
    "output",
    [
        {"at": datetime.now(timezone.utc)},
        {"score": float("nan")},
        {"blob": "x" * (results.MAX_RESULT_BYTES + 1)},
    ],
    ids=["datetime", "nan", "oversized"],
)
def test_unencodable_collector_output_becomes_a_failure(redis, output):
    add_job(redis, envelope())
    run_once(consumer(redis, lambda _: output))

    [result] = published(redis)
    assert result["status"] == "failed"
    assert result["error"]["code"] == "invalid_result"
    assert pending(redis) == 0


def test_invalid_payload_message_does_not_leak_the_value(redis):
    add_job(redis, envelope(scope="ghp_SECRETTOKEN"))
    run_once(consumer(redis, lambda _: {"items": []}))

    [(_, fields)] = redis.xrange("alinha:jobs:results")
    assert json.loads(fields["result"])["error"]["code"] == "invalid_payload"
    assert "ghp_SECRETTOKEN" not in fields["result"]


def test_result_stream_down_leaves_the_entry_pending(redis):
    add_job(redis, envelope())
    worker = consumer(redis, lambda _: {"items": []})
    worker.ensure_groups()

    def down(*args, **kwargs):
        raise ConnectionError("redis down")

    redis.xadd = down
    with pytest.raises(ConnectionError):
        worker.consume_once()

    assert pending(redis) == 1
    assert published(redis) == []


def test_duplicate_job_and_attempt_runs_the_collector_once(redis):
    job = envelope()
    calls = []

    def collect(value):
        calls.append(value)
        return {"items": []}

    worker = consumer(redis, collect)
    add_job(redis, job)
    run_once(worker)
    add_job(redis, job)
    run_once(worker)

    assert len(calls) == 1
    assert len(published(redis)) == 1
    assert pending(redis) == 0


def test_two_attempts_of_the_same_job_both_run(redis):
    job = envelope()
    calls = []
    worker = consumer(redis, lambda value: calls.append(value) or {"items": []})
    add_job(redis, job, attempt=1)
    run_once(worker)
    add_job(redis, job, attempt=2)
    run_once(worker)

    assert len(calls) == 2
    assert [result["attempt"] for result in published(redis)] == [1, 2]


def test_an_entry_without_a_usable_envelope_is_acked_without_a_result(redis):
    worker = consumer(redis, lambda _: {"items": []})
    worker.ensure_groups()
    redis.xadd(STREAM, {"junk": "x"})
    worker.consume_once()

    assert published(redis) == []
    assert pending(redis) == 0


def boom(*args):
    raise RuntimeError("unexpected")


def test_an_unexpected_error_leaves_the_entry_pending(redis):
    worker = consumer(redis, lambda _: {"items": []})
    worker._run_collector = boom
    add_job(redis, envelope())
    run_once(worker)

    assert published(redis) == []
    assert pending(redis) == 1


def test_a_poison_entry_fails_after_max_deliveries(redis):
    job = envelope()
    worker = Consumer(redis, Config(name="test", claim_idle_ms=0, max_deliveries=2))
    worker._run_collector = boom
    add_job(redis, job)
    run_once(worker)
    worker.reclaim()
    assert published(redis) == []

    worker.reclaim()

    [result] = published(redis)
    assert result["jobId"] == job["jobId"]
    assert result["error"]["code"] == "too_many_deliveries"
    assert result["retryable"] is False
    assert pending(redis) == 0


def test_reclaim_processes_an_entry_a_dead_worker_left_pending(redis):
    job = envelope()
    worker = Consumer(redis, Config(name="test", claim_idle_ms=0), collector_lookup=lambda _: lambda _: {"items": []})
    add_job(redis, job)
    redis.xgroup_create(STREAM, "alinha-workers", id="0")
    redis.xreadgroup("alinha-workers", "dead-worker", {STREAM: ">"}, count=10)

    worker.reclaim()

    [result] = published(redis)
    assert result["jobId"] == job["jobId"]
    assert pending(redis) == 0


def test_job_types_parse_strictly():
    assert _parse_job_types("evidence.collect:1, other.type:2") == (("evidence.collect", 1), ("other.type", 2))


@pytest.mark.parametrize("value", ["evidence.collect", "evidence:1", "evidence.collect:x", "evidence.collect:0"])
def test_a_malformed_job_type_is_rejected(value):
    with pytest.raises(ValueError, match="WORKER_JOB_TYPES"):
        _parse_job_types(value)


def test_a_job_type_without_a_contract_fails_at_startup(redis):
    with pytest.raises(ValueError, match="no contract no-such-type"):
        Consumer(redis, Config(name="test", job_types=(("no-such.type", 1),)))
