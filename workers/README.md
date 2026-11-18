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
| `WORKER_CLAIM_IDLE_MS` | `60000` | Claim entries a crashed worker left pending |
| `WORKER_DEDUPE_TTL_S` | `86400` | TTL of the `(jobId, attempt)` dedupe key |
| `CONTRACTS_DIR` | repo `contracts/jobs`, else `/app/contracts/jobs` | Where the JSON Schemas live |

Image: `docker build -f workers/job_consumer/Dockerfile .` from the repo root.

## Installing a provider collector

A provider's worker package registers one callable in the `alinha.collectors` entry-point group, keyed by its
provider id:

```toml
[project.entry-points."alinha.collectors"]
code-repository = "alinha_coderepository.worker:collect"
```

`collect(envelope) -> dict` receives the validated job envelope and returns the type-specific `result` object.
Raise `alinha_job_consumer.collectors.TransientError` for a failure worth retrying (timeout, 5xx, connection
reset); any other exception is a final failure. A provider that is not installed produces a non-retryable
`unknown_provider` result instead of crashing the worker.
