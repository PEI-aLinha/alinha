# Background jobs (RNF02)

The core sends background work (evidence collection, ...) to the Python workers. No job is lost if a component
fails: the job is written to an **outbox in the caller's transaction**, and a relay publishes it to **Redis Streams**.

```
plugin @Transactional ──dispatch()──▶ core.job_outbox (pending) ──commit──▶ OutboxRelay ──XADD──▶ alinha:jobs:<type>:v<n>
                                                                                                         │
plugin @ApplicationModuleListener ◀── JobCompleted / JobFailed ◀── core ◀── alinha:jobs:results ◀── worker
```

## Dispatching a job

```kotlin
@Transactional
fun startCollection(...) {
    // ... business write + auditTrail.record(...)
    jobs.dispatch(
        JobRequest(
            type = "evidence.collect",
            payload = mapOf("provider" to "code-repository", "connectionRef" to ref, "scope" to scope),
            requestedBy = "code-repository",         // your plugin id
            idempotencyKey = "collect:$assessmentId", // optional
        ),
    )
}
```

- `dispatch` must run **inside a transaction** (`MANDATORY`). The job commits or rolls back with your write.
- Pass only the type-specific fields. The core adds `jobId`, `type`, `tenantId` (from `TenantContext`, never
  from you) and `requestedAt`, then validates the whole envelope against
  `contracts/jobs/<type with dots as dashes>.v<version>.schema.json`. An invalid envelope throws, and your
  transaction rolls back.
- `idempotencyKey`: the same `(tenant, type, key)` returns the **existing** jobId and writes nothing. The payload of
  the second call is ignored.
- Every new job is audited (`job.dispatched`) in the same transaction.
- Never put credentials in the payload: use `connectionRef` (OpenBao, `TenantSecretStore`).

## Job states (`core.job_outbox.status`)

| Status | Meaning |
|---|---|
| `pending` | waiting for the relay (`next_attempt_at`) |
| `published` | on the stream, waiting for the worker's result |
| `completed` / `failed` | the worker answered (results consumer, see below) |
| `dead` | Redis refused it `max-publish-failures` times; `JobFailed` (`publish_failed`) is emitted |

## Relay

- Every `alinha.jobs.relay.poll-interval-ms`, one transaction runs
  `select … where status = 'pending' and next_attempt_at <= now() for update skip locked limit batch-size`.
  Several app instances never publish the same row in the same round.
- `XADD alinha:jobs:<type>:v<n>` with the fields `envelope` (JSON) and `attempt`. There is **no `MAXLEN`**:
  trimming by length also drops entries no worker has read yet, and the row already says `published`, so the job
  would be lost. Streams grow until acknowledged entries are trimmed (#87).
- If the publish fails, the batch stops (Redis is probably down), and the row gets `publish_failures + 1` and
  exponential backoff (`backoff-base` × 2ⁿ, at most `backoff-max`).
- **At-least-once**: a crash after `XADD` but before the row is marked `published` publishes the job again.

## What workers must do (#28)

- Read with a consumer group, and `XACK` only after the result is written.
- Deduplicate by `(jobId, attempt)`: the same pair can arrive twice.
- `attempt` is a field of the stream entry, not of the envelope: the envelope schema stays unchanged across retries.
- Send exactly one result per attempt to `alinha:jobs:results`, field `result`, following
  `contracts/jobs/job-result.v1.schema.json`. Set `retryable: true` only for transient failures.
- The worker's message is untrusted: the core takes the tenant from the outbox row and rejects a mismatch.

## Results and retries

The second part of #13: the results consumer, `JobCompleted`, re-dispatch of `retryable` failures up to
`max_attempts`, and a DLQ. Until it lands, `published` jobs stay `published`.

## Configuration (`alinha.jobs.*`)

| Property | Default | |
|---|---|---|
| `relay.enabled` | `true` | `false` in tests that drive the relay by hand |
| `relay.poll-interval-ms` | `1000` | |
| `relay.batch-size` | `50` | |
| `relay.max-publish-failures` | `10` | then `dead` |
| `relay.backoff-base` / `relay.backoff-max` | `2s` / `5m` | |
| `default-max-attempts` | `3` | worker attempts per job |

Redis: `spring.data.redis.host/port` (`REDIS_HOST`/`REDIS_PORT`), with a timeout of `2s`, because the relay holds
row locks while it publishes.
