#!/usr/bin/env bash
# Idempotent: starts local Postgres + MinIO and creates dev/test DBs and buckets.
set -euo pipefail

PG_FORMULA="postgresql@16"
MINIO_DATA="${HOME}/.light-audible-minio"

for pair in "psql:${PG_FORMULA}" "minio:minio" "mc:minio-mc"; do
  bin="${pair%%:*}"; formula="${pair#*:}"
  if ! command -v "$bin" >/dev/null 2>&1; then
    if [ "$bin" = "psql" ] && [ -x "$(brew --prefix)/opt/${PG_FORMULA}/bin/psql" ]; then continue; fi
    brew install "$formula"
  fi
done

PG_BIN="$(brew --prefix)/opt/${PG_FORMULA}/bin"
export PATH="${PG_BIN}:${PATH}"

brew services start "${PG_FORMULA}" >/dev/null || true
for _ in $(seq 1 30); do
  pg_isready -q && break
  sleep 1
done
pg_isready -q || { echo "Postgres did not become ready" >&2; exit 1; }

for db in light_audible_dev light_audible_test; do
  if ! psql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='${db}'" | grep -q 1; then
    createdb "${db}"
  fi
done

if ! lsof -iTCP:9000 -sTCP:LISTEN >/dev/null 2>&1; then
  mkdir -p "${MINIO_DATA}"
  nohup minio server "${MINIO_DATA}" --address :9000 >"${HOME}/.light-audible-minio.log" 2>&1 &
fi
for _ in $(seq 1 30); do
  curl -sf http://localhost:9000/minio/health/live >/dev/null && break
  sleep 1
done

mc alias set local http://localhost:9000 minioadmin minioadmin >/dev/null
for bucket in light-audible-dev light-audible-test; do
  mc mb --ignore-existing "local/${bucket}"
done
echo "Services ready."
