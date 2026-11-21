# Background jobs (RNF02)

The core sends background work (evidence collection, ...) to the Python workers. No job is lost if a component
fails: the job is written to an **outbox in the caller's transaction**, and a relay publishes it to **Redis Streams**.

```
plugin @Transactional ──dispatch()──▶ core.job_outbox (pending) ──commit──▶ OutboxRelay ──XADD──▶ alinha:jobs:<type>:v<n>
                                                                                                         │
plugin event listener         ◀── JobCompleted / JobFailed ◀── core ◀── alinha:jobs:results ◀── worker
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
  `contracts/jobs/<type with dots as dashes>.v<version>.schema.json`. An invalid envelope (or a payload value that is
  not JSON) throws `IllegalArgumentException` before anything is written. It does not mark your transaction rollback-only: let it
  propagate to roll back, or catch it and your own write still commits. Because dots become dashes, `a-b.c` and
  `a.b-c` would share one contract file, so the contract must set `properties.type.const` to its type; a dispatch
  whose type does not match it throws.
- `idempotencyKey`: the same `(tenant, type, requestedBy, key)` returns the **existing** jobId and writes nothing.
  Keys are per plugin: another plugin that uses the same key gets its own job. The payload of
  the second call is ignored, but it is still validated first (an invalid one throws). The contract `version` is not
  part of the key: a retry after a v1 → v2 upgrade gets the v1 job back instead of running the same work twice.
  The key never expires while the row exists (until retention, #87), **even if that job is `dead` or `failed`**: put
  something in the key that changes when you really want a new job (e.g. `collect:<assessmentId>:<runId>`).
- Every new job is audited (`job.dispatched`) in the same transaction.
- Never put credentials in the payload: use `connectionRef` (OpenBao, `TenantSecretStore`).

## Job states (`core.job_outbox.status`)

| Status | Meaning |
|---|---|
| `pending` | waiting for the relay (`next_attempt_at`); also after a `retryable` failure, with `attempt + 1` |
| `published` | on the stream, waiting for the worker's result |
| `completed` | the worker finished it; `JobCompleted` is emitted, the result is in `JobResults.find` |
| `failed` | the worker failed it for good (not `retryable`, or no attempts left); `JobFailed` carries the worker's error |
| `dead` | publishing one attempt failed `max-publish-failures` times for a reason other than Redis (see Relay); `JobFailed` (`publish_failed`) is emitted |

## Relay

- Every `alinha.jobs.relay.poll-interval-ms`, the relay publishes up to `batch-size` due jobs, **each in its own
  transaction**: `select … where status = 'pending' and next_attempt_at <= now() for update skip locked limit 1`,
  `XADD`, mark `published`, commit. Several app instances never publish the same row.
- `XADD alinha:jobs:<type>:v<n>` with the fields `envelope` (JSON) and `attempt`. There is **no `MAXLEN`**:
  trimming by length also drops entries no worker has read yet, and the row already says `published`, so the job
  would be lost. Streams grow until acknowledged entries are trimmed (#87).
- If the publish fails, what happens depends on the error:
  - **Redis unavailable** (connection lost, timeout, or an error reply about Redis itself: `OOM`, `LOADING`, `BUSY`,
    `READONLY`, `MISCONF`, `MASTERDOWN`, `CLUSTERDOWN`, `TRYAGAIN`, `NOAUTH`, `NOREPLICAS`): the row stays `pending`, retried after
    `backoff-base`, and **no failure is counted**. The round stops and the other due jobs wait for the next one. However
    long the outage, no job dies because of it; the relay catches up when Redis is back. The error is kept in
    `last_error`.
  - **Redis refusing this job** (any other error reply, e.g. `WRONGTYPE`: the key holds another type; `NOPERM`: an
    ACL forbids it; `CROSSSLOT`/`MOVED` in a cluster; an envelope over `proto-max-bulk-len`): Redis is up, so this
    counts as a failure of the job, like the case below.
  - **Anything else** (a bug, an unexpected exception): `publish_failures + 1` and exponential backoff
    (`backoff-base` × 2ⁿ, at most `backoff-max`), and the relay goes on with the other due jobs. A row that
    always fails ends `dead`.
- A row lock is held only while its own `XADD` runs, so at most one Redis timeout. The results consumer, which waits
  on that lock (see below), never waits for a whole batch.
- **At-least-once**: a crash after `XADD` but before the row is marked `published` publishes the job again.
- Once a row is `published`, the job lives only in Redis. Redis must keep its data across restarts and crashes
  (AOF with `appendfsync always`, as in `deploy/docker-compose.dev.yml`; the default `everysec` can lose about the
  last second of published jobs). Until the watchdog (#86) exists, a job Redis loses stays `published` forever.
- Redis is **not** part of the app's health (`management.health.redis.enabled: false`): while it is down, dispatch
  keeps working and the relay catches up later.

## What workers must do (#28)

- Read with a consumer group, and `XACK` only after the result is written.
- Deduplicate by `(jobId, attempt)`: the same pair can arrive twice.
- Unacknowledged entries are claimed again after `WORKER_CLAIM_IDLE_MS`, even if a worker is still running them:
  keep it above the longest collector run. The deadline `WORKER_COLLECTOR_TIMEOUT_S` enforces this (a run past it
  is a retryable failure), and a worker keeps the rest of its batch from going idle while it works
  (`workers/README.md`).
- `attempt` is a field of the stream entry, not of the envelope: the envelope schema stays unchanged across retries.
- Send a result per attempt to `alinha:jobs:results`, field `result`, following
  `contracts/jobs/job-result.v1.schema.json`. Set `retryable: true` only for transient failures. At most one result
  is *applied* per attempt: a crash between publish and `XACK` can send a second one, which the core ignores.
- The worker's message is untrusted: the core takes the tenant from the outbox row and rejects a mismatch.

## Getting the outcome

```kotlin
@Async
@TransactionalEventListener
fun on(event: JobCompleted) {
    if (event.requestedBy != "code-repository") return
    // No request tenant here: runAs first, then the transaction (docs/tenancy.md, rule 4).
    tenants.runAs(event.tenantId) {
        tx.executeWithoutResult {
            val job = jobResults.find(event.jobId) ?: return@executeWithoutResult
            // job.result: the worker's output, untrusted data
        }
    }
}
```

- `JobCompleted` carries no result (it can be large and every event is stored in `event_publication`): read it with
  `JobResults.find(jobId)`, filtered by the current tenant. Another tenant's job reads as `null`. It is **not**
  filtered by plugin yet: another plugin's job in the same tenant can be read. That waits for the caller binding (#60).
- `JobFailed` is final: the job will not run again. `error.code` is `publish_failed` when the core could not queue
  it, otherwise the worker's code. The worker's `error.message` and `result` are **untrusted**: show or store them,
  never act on them as instructions.

## Results consumer

- Every `alinha.jobs.results.poll-interval-ms`, `JobResultConsumer` reads `alinha:jobs:results` with the consumer
  group `alinha-core` (created at offset `0` with `MKSTREAM`, again if Redis lost it).
- Each entry is validated against `job-result.v1`, then applied in its own transaction under the job's tenant. The
  outbox row is locked `for update`, so a result that arrives before the relay commits `published` waits for it.
- The entry is `XACK`ed (and deleted) only after that transaction commits. A result for another attempt, or for a job that is
  already finished, is ignored: applying the same entry twice changes nothing.
- `failed` with `retryable: true` and `attempt < max_attempts`: the row goes back to `pending` with `attempt + 1`
  and the relay's backoff; the relay publishes it again. Otherwise the job is `failed`.
- Audited: `job.completed`, `job.retried`, `job.failed` (error code only, never the worker's message).
- A NUL character (`\u0000`) anywhere in the result, keys included, is replaced with U+FFFD before it is read:
  PostgreSQL `text` and `jsonb` cannot store it, and the job would otherwise never finish.
- A `result` field over `max-result-bytes` (UTF-8) is dead-lettered **without** that field (`reason` says why),
  before it is parsed, on every path (including the `max-deliveries` and `max-total-deliveries` caps): it would otherwise be stored as is in `core.job_outbox`. The job stays `published` until the
  watchdog (#86) fails it; workers must keep `result` under the limit (1 MiB by default).
- **Dead letters** go to `alinha:jobs:results:dead`, with the original fields plus `sourceId` and `reason`: an entry
  that is not valid JSON or does not match the contract, names an unknown job, or whose `tenantId`, `type` or
  `version` disagree with the outbox row. The job itself is not changed (it stays `published`, #86).
- **Recovery**: each round first claims entries left unacknowledged for `claim-min-idle` (an instance died, or the
  database was down while applying them), with `XPENDING` + `XCLAIM`.
- **Attempts**: `max-deliveries` counts the times the core actually tried to apply an entry, kept in the hash
  `alinha:jobs:results:attempts` (field = entry id, removed on `XACK`). It is not the stream's delivery count:
  `XCLAIM`/`XREADGROUP` add a delivery to every entry of a batch, including the ones a round stops before. An
  attempt is counted before applying, so an instance that dies mid-apply still uses one up.
- **Giving up**: an entry with `max-deliveries` attempts, or delivered `max-total-deliveries` times whatever happened
  (attempts given back included), is dead-lettered. If it is a valid result for its job's current attempt, the job
  is also failed with `JobError.RESULT_NOT_APPLIED`: `JobFailed` is emitted and `JobResults.find` returns that error
  (the entry's content is not stored).
  `max-total-deliveries` is the outage budget: it only matters when attempts keep being given back.
- Redis down: the round returns, nothing is lost; unacknowledged entries are delivered again later.
- Database down: the probe is a write (`update core.job_outbox … where false`), so a read-only standby or
  `default_transaction_read_only` counts as down too, where `select 1` would pass. A round starts only when the probe
  succeeds. When applying an entry fails, the probe runs again: if it fails, the attempt is given back and the round
  stops. The entry's own error is never trusted for this (a poison entry could raise an outage SQLState itself), so
  a failure while the probe passes counts as an attempt. A full data disk (SQLState 53100) passes the probe and
  counts as attempts; `max-deliveries` then dead-letters the result and fails the job.
- Every entry is deleted (`XDEL`) right after its `XACK`: the outcome is in the outbox row, and a result may hold
  collected evidence. The dead-letter stream is not trimmed yet (#87). Each app start uses a new consumer name
  unless `results.consumer-name` is set; old names stay in the group with nothing pending.

## Configuration (`alinha.jobs.*`)

| Property | Default | |
|---|---|---|
| `relay.enabled` | `true` | also turns off the `JobFailed` notifier for dead jobs; `false` in tests that drive both by hand |
| `relay.poll-interval-ms` | `1000` | |
| `relay.batch-size` | `50` | |
| `relay.max-publish-failures` | `10` | non-Redis failures, then `dead` |
| `relay.backoff-base` / `relay.backoff-max` | `2s` / `5m` | `backoff-base` is also the retry delay while Redis is down |
| `results.enabled` | `true` | `false` in tests that drive `consumeOnce()` by hand |
| `results.poll-interval-ms` / `results.batch-size` | `1000` / `50` | |
| `results.claim-min-idle` | `1m` | unacknowledged for this long = claimed again |
| `results.max-deliveries` | `5` | attempts that reached a database taking writes, then dead-lettered and the job failed |
| `results.max-total-deliveries` | `100` | deliveries of one entry whatever happened (outage budget), then the same |
| `results.consumer-name` | random per start | |
| `results.max-result-bytes` | `1048576` | larger `result` fields are dead-lettered without the payload |
| `default-max-attempts` | `3` | worker attempts per job |

Redis: `spring.data.redis.host/port` (`REDIS_HOST`/`REDIS_PORT`), with command and connect timeouts of `2s`,
because the relay holds a row lock while it publishes.
