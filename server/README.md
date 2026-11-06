# Light Audible server

FastAPI service that signs in to Audible, converts the owner's books to `.m4b`, stores them in S3-compatible storage and serves them to the Light Phone tool.

## Local dev

```sh
cd server
scripts/dev-services.sh        # Postgres + MinIO, dev/test DBs and buckets
cp .env.example .env           # then fill in AUTH_ENCRYPTION_KEY and SETUP_PASSWORD
uv sync
uv run alembic upgrade head
uv run uvicorn --factory light_audible.main:create_app --reload
uv run pytest -q
```

Generate a Fernet key for `AUTH_ENCRYPTION_KEY`:

```sh
uv run python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

The server refuses to start if `SETUP_PASSWORD` is shorter than 8 characters or `AUTH_ENCRYPTION_KEY` is not a valid Fernet key.

## Deploy to Railway

1. Create a Railway project from the GitHub repo. In the service settings set **Root Directory** to `server` and **Config file path** to `/server/railway.json` (the config path does not follow the root directory). Railway deploys on every push to the connected branch; disconnect the branch in **Settings → Source** to deploy manually, and set **Watch paths** to `/server/**` to skip deploys for changes outside the server.
2. Add a **Postgres** database. On the service, set `DATABASE_URL` to `${{Postgres.DATABASE_URL}}`.
3. Add a **Bucket** and reference its variables (replace `Bucket` with the bucket's name in the project):
   - `S3_ENDPOINT=${{Bucket.ENDPOINT}}`
   - `S3_BUCKET=${{Bucket.BUCKET}}` (not `RAILWAY_BUCKET_NAME`)
   - `S3_ACCESS_KEY_ID=${{Bucket.ACCESS_KEY_ID}}`
   - `S3_SECRET_ACCESS_KEY=${{Bucket.SECRET_ACCESS_KEY}}`
   - `S3_REGION=${{Bucket.REGION}}`
   - `S3_ADDRESSING_STYLE=virtual` (use `path` only if the bucket's Credentials tab says so)
4. Set the remaining variables: `AUTH_ENCRYPTION_KEY` (generated as above), `SETUP_PASSWORD` (8+ chars), `AUDIBLE_LOCALE` (default `us`), `PUBLIC_BASE_URL` (the service's public URL, with `https://`), and optionally `BUCKET_SOFT_LIMIT_BYTES`.
5. Generate a public domain for the service (**Settings → Networking**). The container runs `alembic upgrade head` and then starts uvicorn on `$PORT`. Railway checks `/api/health`.
6. Open `<PUBLIC_BASE_URL>/setup` to sign in to Audible and create a pairing code.

## Moving the database to Neon

The schema is plain Postgres and the connection comes only from `DATABASE_URL`, so the move is a dump and restore. Do it before the Railway trial volumes are deleted.

```sh
pg_dump --format=custom --no-owner "$OLD_DATABASE_URL" -f light_audible.dump
pg_restore --no-owner --dbname "$NEON_DATABASE_URL" light_audible.dump
```

Then change `DATABASE_URL` on the service to the Neon connection string and redeploy.
