FROM python:3.12-slim

COPY --from=ghcr.io/astral-sh/uv:0.13.7 /uv /bin/uv

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --uid 1000 app
WORKDIR /app
RUN chown app:app /app
USER app

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    PATH="/app/.venv/bin:${PATH}" \
    PYTHONUNBUFFERED=1

COPY --chown=app:app pyproject.toml uv.lock .python-version ./
RUN uv sync --frozen --no-dev --no-install-project

COPY --chown=app:app alembic.ini ./
COPY --chown=app:app migrations ./migrations
COPY --chown=app:app light_audible ./light_audible
RUN uv sync --frozen --no-dev

CMD ["sh", "-c", "alembic upgrade head && uvicorn --factory light_audible.main:create_app --host 0.0.0.0 --port ${PORT:-8000}"]
