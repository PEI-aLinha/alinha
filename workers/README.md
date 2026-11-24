# Worker runtime (Python, separate process)

- `job_consumer/` — reads jobs from Redis Streams (`contracts/jobs/`) and dispatches them to the right collector. **MVP.**
- `document_sandbox/` — analyses uploaded files, no network, allowed file types only (RNF10). **Post-MVP.**

Provider **collectors** do NOT live here: they are in each provider's own repo
(`plugins/provider/<id>/worker/`) and are installed into the runtime as Python packages.

## Running the job consumer

```bash
pip install -e "workers/job_consumer[test]"
REDIS_URL=redis://localhost:6379/0 alinha-job-consumer
```

Requires Redis 7 or later (`XAUTOCLAIM` returns three values from 7.0).

The job and result formats, retry rules and dedupe keys are in `docs/jobs.md`; this file only covers how to run
the consumer.

| Variable | Default | Meaning |
|---|---|---|
| `REDIS_URL` | `redis://localhost:6379/0` | Streams backend |
| `WORKER_JOB_TYPES` | `evidence.collect:1` | `type:version`, comma-separated; stream is `alinha:jobs:<type>:v<n>` |
| `WORKER_GROUP` | `alinha-workers` | Consumer group per stream |
| `WORKER_NAME` | hostname | Consumer name inside the group |
| `WORKER_BLOCK_MS` | `5000` | `XREADGROUP` block time |
| `WORKER_BATCH` | `10` | Entries per read and per claim |
| `WORKER_CLAIM_IDLE_MS` | `60000` | Claim entries a crashed worker left pending; must be larger than the longest collector run (below) |
| `WORKER_COLLECTOR_TIMEOUT_S` | `50` | Deadline for one collector run; a run past it publishes a retryable `transient_error`. Must be below `WORKER_CLAIM_IDLE_MS` (checked at startup) |
| `WORKER_DEDUPE_TTL_S` | `86400` | TTL of the `(jobId, attempt)` dedupe key |
| `WORKER_MAX_DELIVERIES` | `3` | Deliveries of one entry that may fail unexpectedly; the next one publishes a non-retryable `too_many_deliveries` result |
| `BAO_ADDR`, `BAO_TOKEN` | unset | OpenBao address and the worker's read-only token, for collectors that need credentials |
| `CONTRACTS_DIR` | repo `contracts/jobs`, else `/app/contracts/jobs` | Where the JSON Schemas live |

`XAUTOCLAIM` cannot tell a crashed worker from a slow one: an entry still running after `WORKER_CLAIM_IDLE_MS` is
taken over and the collector runs a second time. Set it above the longest collector run; `WORKER_COLLECTOR_TIMEOUT_S`
enforces that, and before each entry of a batch the worker resets the idle time of the entries still waiting their
turn (`XCLAIM ... JUSTID`), so they are not claimed while it runs the earlier ones. A timed-out collector keeps
running in a background thread until it returns; the worker moves on. If a run does happen
twice, the core applies only the first result for a `(jobId, attempt)` and ignores the other (docs/jobs.md,
"Results consumer").

Image: `docker build -f workers/job_consumer/Dockerfile .` from the repo root.

## Installing a provider collector

A provider's worker package registers one callable in the `alinha.collectors` entry-point group, keyed by its
provider id:

```toml
[project.entry-points."alinha.collectors"]
code-repository = "alinha_coderepository.collector:collect"
```

`collect(envelope) -> dict` receives the validated job envelope and returns the type-specific `result` object.
Raise `alinha_job_consumer.collectors.TransientError` for a failure worth retrying (timeout, 5xx, connection
reset), and `CollectorError(code, message)` for an expected final failure the core may show (`invalid_credentials`);
its code and message go into the result as they are, so never put a credential or provider text in them. Any other
exception is a final, generic `collector_error`.

A collector that needs the tenant's credential calls `alinha_job_consumer.credentials.provider_secret(envelope)`
(reads OpenBao with `BAO_ADDR` and `BAO_TOKEN`, the `alinha-worker` policy). See docs/jobs.md, "Credentials for a
collector".

A provider that is not installed produces a non-retryable `unknown_provider` result instead of crashing the worker.
