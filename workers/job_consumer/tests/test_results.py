import json
import uuid

import fakeredis
import pytest

from alinha_job_consumer import results


def envelope():
    return {"jobId": str(uuid.uuid4()), "type": "evidence.collect", "tenantId": str(uuid.uuid4())}


def test_completed_result_carries_the_attempt_and_tenant():
    job = envelope()
    result = results.completed(job, "evidence.collect", 1, 2, {"items": []})

    assert result["jobId"] == job["jobId"]
    assert result["tenantId"] == job["tenantId"]
    assert result["attempt"] == 2
    assert result["status"] == "completed"
    assert result["result"] == {"items": []}
    assert "error" not in result


def test_failed_result_is_retryable_only_when_asked():
    job = envelope()
    result = results.failed(job, "evidence.collect", 1, 1, "timeout", "took too long", retryable=True)

    assert result["status"] == "failed"
    assert result["retryable"] is True
    assert result["error"] == {"code": "timeout", "message": "took too long"}


def test_publish_writes_a_validated_result_to_the_results_stream():
    redis = fakeredis.FakeRedis(decode_responses=True)
    result = results.completed(envelope(), "evidence.collect", 1, 1, {"items": []})

    results.publish(redis, result)

    [(_, fields)] = redis.xrange(results.RESULTS_STREAM)
    assert json.loads(fields["result"]) == result


def test_publish_refuses_an_invalid_result():
    redis = fakeredis.FakeRedis(decode_responses=True)
    result = results.completed(envelope(), "evidence.collect", 1, 1, "not an object")

    with pytest.raises(results.InvalidResult):
        results.publish(redis, result)
    assert redis.xlen(results.RESULTS_STREAM) == 0
