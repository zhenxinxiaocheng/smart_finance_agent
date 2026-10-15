#!/usr/bin/env bash
set -euo pipefail
umask 077
ACTION=$1
PROJECT=$(cd "$2" && pwd -P)
STATE="$PROJECT/.data/deploy"
export PROJECT_DIR="$PROJECT"
export RUNTIME_DIR=$(cd "$(dirname "$0")" && pwd -P)
export CONFIG_FILE="$STATE/application-local.yml"
export SERVER_DOMAIN=www.zxxc233.cn
[[ "$ACTION" == prepare || "$ACTION" == up ]] || { echo "Use prepare or up" >&2; exit 2; }
[[ -f "$CONFIG_FILE" ]] || { echo "Missing uploaded application-local.yml" >&2; exit 1; }
mkdir -p "$STATE"
chmod 700 "$STATE"
if [[ ! -f "$STATE/.env" ]]; then
  ROOT_PASSWORD=$(openssl rand -hex 32)
  DATABASE_PASSWORD=$(openssl rand -hex 32)
  INTERNAL_TOKEN=$(openssl rand -hex 32)
  printf 'MYSQL_ROOT_PASSWORD=%s\nMYSQL_PASSWORD=%s\nANALYSIS_INTERNAL_TOKEN=%s\n' "$ROOT_PASSWORD" "$DATABASE_PASSWORD" "$INTERNAL_TOKEN" > "$STATE/.env.new"
  chmod 600 "$STATE/.env.new"
  mv "$STATE/.env.new" "$STATE/.env"
fi
[[ "$ACTION" == up ]] || { echo "Prepared; existing configuration and credentials preserved."; exit 0; }
[[ "$(id -u)" == 0 ]] || { echo "Run as root." >&2; exit 1; }
[[ -f "$PROJECT/backend/pom.xml" && -f "$PROJECT/frontend/package-lock.json" ]] || { echo "Project source is incomplete." >&2; exit 1; }
docker compose version
chown 10001:10001 "$CONFIG_FILE"
chmod 600 "$CONFIG_FILE" "$STATE/.env"
compose() { docker compose -p smart-finance-agent --env-file "$STATE/.env" -f "$RUNTIME_DIR/compose.yaml" "$@"; }
compose config --quiet
if [[ -n "$(compose ps --status running -q mysql)" ]]; then
  compose stop web backend analysis
  mkdir -p "$STATE/backups"
  BACKUP="$STATE/backups/$(date -u +%Y%m%dT%H%M%SZ)-$$.sql"
  if ! compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump -u"$MYSQL_USER" --single-transaction --routines --events --triggers --no-tablespaces --set-gtid-purged=OFF "$MYSQL_DATABASE"' > "$BACKUP.partial"; then
    compose start analysis backend web || true
    echo "Database backup failed. Update stopped." >&2
    exit 1
  fi
  mv "$BACKUP.partial" "$BACKUP"
fi
for SERVICE in analysis backend web; do
  if ! compose build "$SERVICE"; then
    compose start analysis backend web || true
    echo "Build failed; previous containers were not replaced." >&2
    exit 1
  fi
done
if ! compose up -d --no-build --wait --wait-timeout 360; then
  echo "Startup failed. Keep any database backup; do not downgrade a migrated database." >&2
  compose logs --tail=60
  exit 1
fi
compose ps
echo "Containers ready. Verify https://www.zxxc233.cn"
