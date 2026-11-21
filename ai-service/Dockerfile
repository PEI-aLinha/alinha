# syntax=docker/dockerfile:1
# Build from this folder: docker build -t alinha-ai-service ai-service/

# --- build: install the locked dependencies and the service into a venv ---
FROM python:3.12-slim AS build

COPY --from=ghcr.io/astral-sh/uv:0.9.5 /uv /bin/uv

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=0

WORKDIR /app

# Dependencies first, so this layer is cached while only the code changes.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY src ./src
RUN uv sync --frozen --no-dev --no-editable

# --- runtime: only the venv, no uv, no build tools, non-root ---
FROM python:3.12-slim

RUN useradd --system --uid 10001 --no-create-home app

COPY --from=build /app/.venv /app/.venv

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    ALLOW_LOCAL_LLM=false \
    ALLOW_FAKE_LLM=false

USER 10001
EXPOSE 8000

# Liveness only (/health never calls the LLM or OpenBao).
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)"]

# Config comes from env (LLM_PROVIDER, LLM_BASE_URL, LLM_MODEL, BAO_ADDR, BAO_TOKEN);
# the LLM API key is read from OpenBao at startup, never baked into the image.
CMD ["uvicorn", "ai_service.main:app", "--host", "0.0.0.0", "--port", "8000"]
