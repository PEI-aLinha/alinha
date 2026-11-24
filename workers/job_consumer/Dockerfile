FROM python:3.12.16-slim@sha256:5e2bdb1b0d542d5039cffb8181c65930093949194a65c365dfb1b0fe13a4fa5b

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    CONTRACTS_DIR=/app/contracts/jobs

WORKDIR /app

# Build from the repo root so the contracts can be copied in:
#   docker build -f workers/job_consumer/Dockerfile .
COPY workers/job_consumer /app/worker
# Dependencies only from wheels, so no third-party setup script runs at build time.
RUN pip install --no-cache-dir --only-binary :all: /app/worker && useradd --create-home worker

# Provider collectors (plugins/provider/<id>/worker, git submodules), registered through the `alinha.collectors`
# entry points. A provider without a worker package, or a submodule that was not checked out, is skipped.
COPY plugins/provider /app/providers
RUN for package in /app/providers/*/worker; do \
      if [ -f "$package/pyproject.toml" ]; then \
        pip install --no-cache-dir --only-binary :all: "$package" || exit 1; \
      fi; \
    done && rm -rf /app/providers

COPY contracts/jobs /app/contracts/jobs

USER worker
CMD ["alinha-job-consumer"]
