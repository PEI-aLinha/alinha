# Worker runtime (Python, separate process)

- `job_consumer/` — reads jobs from Redis Streams (`contracts/jobs/`) and dispatches them to the right collector. **MVP.**
- `document_sandbox/` — analyses uploaded files, no network, allowed file types only (RNF10). **Post-MVP.**

Provider **collectors** do NOT live here: they are in each provider's own repo
(`plugins/provider/<id>/worker/`) and are installed into the runtime as Python packages.
