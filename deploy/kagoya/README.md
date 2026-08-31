# KAGOYA VPS deployment

This deployment runs the MonaKabu realtime API and PostgreSQL on a KAGOYA
VPS. PostgreSQL is reachable only inside Docker. The API is bound to
`127.0.0.1:10000`; Caddy is the only public entry point and terminates HTTPS
and WebSocket connections.

The commands below assume Debian or Ubuntu and a checkout at
`/opt/monakabu-realtime/monakabu`.

## 1. Install the runtime

```bash
sudo apt update
sudo apt install -y docker.io docker-compose git postgresql-client caddy
sudo systemctl enable --now docker caddy
```

Debian 13 (Trixie) provides Compose v2 through its `docker-compose` package.
On a distribution where this package is unavailable, install the Docker
Compose plugin from Docker's official repository before continuing. Confirm
that `sudo docker compose version` succeeds.

## 2. Create the private environment

```bash
cd /opt/monakabu-realtime/monakabu/deploy/kagoya
cp .env.example .env
openssl rand -hex 32
nano .env
chmod 600 .env
```

Put the generated value in `POSTGRES_PASSWORD`. Put the existing Paper server
`MONAKABU_REALTIME_SECRET` value in `MONAKABU_SHARED_SECRET`. Use only
URL-safe hexadecimal text for `POSTGRES_PASSWORD` because it is embedded in a
PostgreSQL connection URL.

The value of `POSTGRES_VERSION` should match the major version displayed for
the source Render database. A newer target major version is also supported by
`pg_restore`, but matching versions makes rollback simpler.

The database volume is mounted at `/var/lib/postgresql`, as required by the
official PostgreSQL 18+ image's version-specific cluster layout. Do not change
it back to `/var/lib/postgresql/data`.

Validate the rendered Compose configuration without printing the private
environment file:

```bash
sudo docker compose config --quiet
```

## 3. Dry-run the Render database migration

Keep Render serving traffic for this rehearsal. Obtain the external database
URL from Render's database Connect menu, then enter it without putting it in
shell history:

```bash
read -rsp "Render External Database URL: " RENDER_DATABASE_URL
echo
export RENDER_DATABASE_URL
pg_dump --format=custom --no-owner --no-privileges \
  --file="$HOME/monakabu-render.dump" "$RENDER_DATABASE_URL"
unset RENDER_DATABASE_URL
pg_restore --list "$HOME/monakabu-render.dump" | head
```

Start only PostgreSQL and restore into the empty target database:

```bash
sudo docker compose up -d db
sudo docker compose exec -T db pg_restore \
  -U monakabu -d monakabu_realtime --no-owner --no-privileges \
  < "$HOME/monakabu-render.dump"
sudo docker compose up -d --build api
sudo docker compose ps
curl --fail http://127.0.0.1:10000/health
```

If the rehearsal succeeds, remove the target database before the final import.
This command affects only the new KAGOYA Docker database:

```bash
sudo docker compose stop api
sudo docker compose exec -T db dropdb -U monakabu --if-exists monakabu_realtime
sudo docker compose exec -T db createdb -U monakabu monakabu_realtime
```

## 4. Enable HTTPS

Create an A record such as `api.example.jp` that points to the KAGOYA IPv4
address. Allow inbound TCP ports 80 and 443 in both the KAGOYA packet filter
and the operating-system firewall. Do not expose ports 5432 or 10000.

```bash
sudo cp Caddyfile.example /etc/caddy/Caddyfile
sudo nano /etc/caddy/Caddyfile
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
curl --fail https://api.example.jp/health
```

Caddy provisions the TLS certificate after DNS resolves and ports 80/443 are
reachable.

## 5. Final cutover

1. Stop the Paper service and suspend the Render web service so no new plugin
   events or web orders can be written during the final dump.
2. Repeat the `pg_dump` command and restore it into a newly emptied KAGOYA
   database.
3. Start the KAGOYA API and confirm `/health` and `/v1/snapshot`.
4. Set Vercel `VITE_API_URL=https://api.example.jp` and
   `VITE_WS_URL=wss://api.example.jp`, then redeploy Production.
5. Set Paper `realtime.endpoint` to
   `https://api.example.jp/v1/ingest` and start Paper.
6. Confirm charts, one-time-code login, account refresh, and one small web
   order before declaring the migration complete.
7. Keep Render suspended for at least 48 hours. Delete it only after keeping a
   verified backup and confirming KAGOYA is stable.

Useful checks:

```bash
sudo docker compose ps
sudo docker compose logs --tail=100 api
sudo journalctl -u caddy -n 100 --no-pager
curl --fail https://api.example.jp/health
curl --fail "https://api.example.jp/v1/snapshot?serverId=monaka-main"
```

Back up the database volume regularly with `pg_dump`. A Docker volume is
persistent across container recreation, but it is not an off-server backup.
