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
  transaction rolls back. Because dots become dashes, `a-b.c` and `a.b-c` would share one contract file: never
  register two types that differ only there.
- `idempotencyKey`: the same `(tenant, type, key)` returns the **existing** jobId and writes nothing. The payload of
  the second call is ignored, but it is still validated first (an invalid one throws).
  The key never expires while the row exists (until retention, #87), **even if that job is `dead` or `failed`**: put
  something in the key that changes when you really want a new job (e.g. `collect:<assessmentId>:<runId>`).
- Every new job is audited (`job.dispatched`) in the same transaction.
- Never put credentials in the payload: use `connectionRef` (OpenBao, `TenantSecretStore`).

## Job states (`core.job_outbox.status`)

| Status | Meaning |
|---|---|
| `pending` | waiting for the relay (`next_attempt_at`) |
| `published` | on the stream, waiting for the worker's result |
| `completed` / `failed` | the worker answered (results consumer, see below) |
| `dead` | publishing failed `max-publish-failures` times for a reason other than Redis (see Relay); `JobFailed` (`publish_failed`) is emitted |

## Relay

- Every `alinha.jobs.relay.poll-interval-ms`, one transaction runs
  `select … where status = 'pending' and next_attempt_at <= now() for update skip locked limit batch-size`.
  Several app instances never publish the same row in the same round.
- `XADD alinha:jobs:<type>:v<n>` with the fields `envelope` (JSON) and `attempt`. There is **no `MAXLEN`**:
  trimming by length also drops entries no worker has read yet, and the row already says `published`, so the job
  would be lost. Streams grow until acknowledged entries are trimmed (#87).
- If the publish fails, the batch stops and the rest waits for the next round. What happens to the row depends on
  the error:
  - **Redis** (down, timing out, refusing writes: any `DataAccessException`): the row stays `pending`, retried after
    `backoff-base`, and **no failure is counted**. However long the outage, no job dies because of it; the relay
    catches up when Redis is back. The error is kept in `last_error`.
  - **Anything else** (a bug, an unexpected exception): `publish_failures + 1` and exponential backoff
    (`backoff-base` × 2ⁿ, at most `backoff-max`). A row that always fails ends `dead` instead of blocking the rows
    behind it.
- Row locks are held while publishing, so a slow-but-alive Redis keeps one transaction open for up to
  `batch-size` × the Redis timeout. Lower `batch-size` if that matters.
- **At-least-once**: a crash after `XADD` but before the row is marked `published` publishes the job again.
- Once a row is `published`, the job lives only in Redis. Redis must keep its data across restarts (AOF, as in
  `deploy/docker-compose.dev.yml`). Until the watchdog (#86) exists, a job Redis loses stays `published` forever.
- Redis is **not** part of the app's health (`management.health.redis.enabled: false`): while it is down, dispatch
  keeps working and the relay catches up later.

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
| `relay.enabled` | `true` | also turns off the `JobFailed` notifier for dead jobs; `false` in tests that drive both by hand |
| `relay.poll-interval-ms` | `1000` | |
| `relay.batch-size` | `50` | |
| `relay.max-publish-failures` | `10` | non-Redis failures, then `dead` |
| `relay.backoff-base` / `relay.backoff-max` | `2s` / `5m` | `backoff-base` is also the retry delay while Redis is down |
| `default-max-attempts` | `3` | worker attempts per job |

Redis: `spring.data.redis.host/port` (`REDIS_HOST`/`REDIS_PORT`), with a timeout of `2s`, because the relay holds
row locks while it publishes.
