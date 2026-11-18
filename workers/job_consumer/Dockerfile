FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    CONTRACTS_DIR=/app/contracts/jobs

WORKDIR /app

# Build from the repo root so the contracts can be copied in:
#   docker build -f workers/job_consumer/Dockerfile .
COPY workers/job_consumer /app/worker
RUN pip install --no-cache-dir /app/worker && useradd --create-home worker

COPY contracts/jobs /app/contracts/jobs

USER worker
CMD ["alinha-job-consumer"]
