#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
BACKUP_TOOL="$ROOT_DIR/server-tools/txb-web/tianxian-backup"
RESTORE_TOOL="$ROOT_DIR/server-tools/txb-web/tianxian-restore"

bash -n "$BACKUP_TOOL"
bash -n "$RESTORE_TOOL"
chmod +x "$BACKUP_TOOL" "$RESTORE_TOOL"

TMP="$(mktemp -d /tmp/tianxian-web-txb-smoke.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/server/scripts" "$TMP/server/backend/app" "$TMP/web" "$TMP/bin" "$TMP/out"

cp "$BACKUP_TOOL" "$TMP/server/scripts/tianxian-backup"
cp "$RESTORE_TOOL" "$TMP/server/scripts/tianxian-restore"
chmod +x "$TMP/server/scripts/tianxian-backup" "$TMP/server/scripts/tianxian-restore"

cat >"$TMP/server/scripts/tianxian-backup-check" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
FILE="$1"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
tar -xzf "$FILE" -C "$T"
(cd "$T" && sha256sum -c checksums/SHA256SUMS >/dev/null)
SH
chmod +x "$TMP/server/scripts/tianxian-backup-check"

cat >"$TMP/server/scripts/tianxian_state.py" <<'PY'
raise SystemExit(0)
PY

cat >"$TMP/server/backend/app/main.py" <<'PY'
app = FastAPI(version="1.0.18")
PY

cat >"$TMP/server/.env" <<'ENV'
POSTGRES_DB=tianxian
POSTGRES_USER=tianxian
POSTGRES_PASSWORD=secret
ENV

cat >"$TMP/server/.env.example" <<'ENV'
POSTGRES_DB=tianxian
ENV

cat >"$TMP/server/docker-compose.yml" <<'YML'
services:
  postgres:
    image: postgres:16
  backend:
    ports:
      - "127.0.0.1:19080:8000"
YML

printf '%064d\n' 1 >"$TMP/server/.backup-key"

cat >"$TMP/web/package.json" <<'JSON'
{"name":"tianxian-web","version":"1.9.1"}
JSON

cat >"$TMP/web/docker-compose.yml" <<'YML'
services:
  tianxian-web:
    build: .
    container_name: tianxian-web
    ports:
      - "127.0.0.1:4900:3000"
YML

cat >"$TMP/web/Dockerfile" <<'EOF_DOCKER'
FROM scratch
EOF_DOCKER

cat >"$TMP/web/.env" <<'ENV'
TIANXIAN_API_BASE_URL=https://example.invalid
ENV

echo 'web snapshot smoke test' >"$TMP/web/index.txt"

cat >"$TMP/bin/docker" <<'SH'
#!/usr/bin/env bash
set -e
if [[ "$1 $2" == "network inspect" ]]; then
  exit 1
fi
if [[ "$1 $2" == "network create" ]]; then
  exit 0
fi
if [[ "$1 $2 $3 $4" == "compose exec -T tianxian-web" ]]; then
  echo "Web → restored API: OK"
  exit 0
fi
if [[ "$1 $2" == "compose version" ]]; then
  echo 'Docker Compose version v2.test'
  exit 0
fi
if [[ "$1" == "inspect" && "$2" == "tianxian-web" && $# -eq 2 ]]; then
  exit 0
fi
if [[ "$1" == "inspect" && "$2" == "-f" ]]; then
  printf '%s\n' "$FAKE_WEB_DIR"
  exit 0
fi
if [[ "$1 $2 $3 $4" == "compose exec -T postgres" ]]; then
  shift 4
  case "$1" in
    pg_isready) exit 0 ;;
    pg_dump)
      case " $* " in
        *" --schema-only "*) echo 'CREATE TABLE smoke(id int);' ;;
        *) echo 'FAKE_CUSTOM_DUMP' ;;
      esac
      exit 0
      ;;
    psql) echo '16.15'; exit 0 ;;
    pg_restore) cat >/dev/null; exit 0 ;;
  esac
fi
if [[ "$1 $2" == "compose up" || "$1 $2" == "compose build" ]]; then
  exit 0
fi
if [[ "$1" == "compose" ]]; then
  exit 0
fi
echo "unexpected docker args: $*" >&2
exit 1
SH
chmod +x "$TMP/bin/docker"

cat >"$TMP/bin/curl" <<'SH'
#!/usr/bin/env bash
if [[ "$*" == *'/health'* ]]; then
  echo '{"ok":true,"service":"tianxian-sync","version":"1.0.18"}'
fi
exit 0
SH
chmod +x "$TMP/bin/curl"

export PATH="$TMP/bin:$PATH"
export FAKE_WEB_DIR="$TMP/web"

(
  cd "$TMP/server"
  ./scripts/tianxian-backup     --type manual     --password-file .backup-key     --output-dir "$TMP/out" >/dev/null
)

TXB="$(find "$TMP/out" -maxdepth 1 -name '*.txb' -type f | head -n1)"
[[ -n "$TXB" ]]

tar -tzf "$TXB" | grep -q '^\./web/web-release.tar.gz$'
tar -tzf "$TXB" | grep -q '^\./web/web-env.enc$'

mkdir -p "$TMP/inspect"
tar -xzf "$TXB" -C "$TMP/inspect"
grep -q '^WEB_SNAPSHOT_INCLUDED=1$' "$TMP/inspect/metadata/manifest.env"
grep -q '^WEB_VERSION=1.9.1$' "$TMP/inspect/metadata/manifest.env"
grep -q '^BACKEND_HOST_PORT=19080$' "$TMP/inspect/metadata/manifest.env"
grep -q '^WEB_HOST_PORT=4900$' "$TMP/inspect/metadata/manifest.env"

# Backed-up nondefault ports must never become the new machine's defaults.
"$RESTORE_TOOL" "$TXB" \
  --password-file "$TMP/server/.backup-key" \
  --target "$TMP/default-server" \
  --web-target "$TMP/default-web" >/dev/null
grep -q '127.0.0.1:18080:8000' "$TMP/default-server/docker-compose.yml"
grep -q '127.0.0.1:3800:3000' "$TMP/default-web/docker-compose.yml"
grep -q '^TIANXIAN_API_BASE_URL=http://backend:8000$' "$TMP/default-web/.env"

grep -q "^DEFAULT_BACKEND_BIND='127.0.0.1'$" "$ROOT_DIR/server-tools/backup-manager/tianxian-recover"
grep -q '^DEFAULT_BACKEND_PORT=18080$' "$ROOT_DIR/server-tools/backup-manager/tianxian-recover"
grep -q '^DEFAULT_WEB_PORT=3800$' "$ROOT_DIR/server-tools/backup-manager/tianxian-recover"


"$RESTORE_TOOL" "$TXB" \
  --password-file "$TMP/server/.backup-key" \
  --target "$TMP/restored-server" \
  --backend-port 18081 \
  --backend-bind '127.0.0.1,127.0.0.2' \
  --web-target "$TMP/restored-web" \
  --web-port 3900 \
  --web-bind '127.0.0.1,127.0.0.3' >/dev/null

grep -q '127.0.0.1:18081:8000' "$TMP/restored-server/docker-compose.yml"
grep -q '127.0.0.2:18081:8000' "$TMP/restored-server/docker-compose.yml"
grep -q '127.0.0.1:3900:3000' "$TMP/restored-web/docker-compose.yml"
grep -q '127.0.0.3:3900:3000' "$TMP/restored-web/docker-compose.yml"
grep -q 'TIANXIAN_API_BASE_URL=http://backend:8000' "$TMP/restored-web/.env"
grep -q 'txb_web_api' "$TMP/restored-server/docker-compose.yml"
grep -q 'txb_web_api' "$TMP/restored-web/docker-compose.yml"
grep -q 'tianxian-recovery-' "$TMP/restored-server/docker-compose.yml"
grep -q 'web snapshot smoke test' "$TMP/restored-web/index.txt"

"$RESTORE_TOOL" "$TXB" \
  --password-file "$TMP/server/.backup-key" \
  --target "$TMP/wildcard-server" \
  --backend-port 28081 \
  --backend-bind '0.0.0.0' \
  --web-target "$TMP/wildcard-web" \
  --web-port 4900 \
  --web-bind '0.0.0.0' >/dev/null
grep -q '0.0.0.0:28081:8000' "$TMP/wildcard-server/docker-compose.yml"
grep -q '0.0.0.0:4900:3000' "$TMP/wildcard-web/docker-compose.yml"

if "$RESTORE_TOOL" "$TXB" --password-file "$TMP/server/.backup-key" \
   --target "$TMP/bad-server" --backend-port 28082 \
   --backend-bind '0.0.0.0,127.0.0.1' \
   --web-target "$TMP/bad-web" --web-port 4901 >/dev/null 2>&1; then
  echo 'Wildcard + specific address should have failed' >&2
  exit 1
fi
[[ ! -e "$TMP/bad-server" ]]

if "$RESTORE_TOOL" "$TXB" --password-file "$TMP/server/.backup-key" \
   --target "$TMP/bad-server2" --backend-port 28083 \
   --backend-bind '203.0.113.254' \
   --web-target "$TMP/bad-web2" --web-port 4902 >/dev/null 2>&1; then
  echo 'Unassigned host address should have failed' >&2
  exit 1
fi
[[ ! -e "$TMP/bad-server2" ]]

# Explicit production-domain retention must be opt-in.
"$RESTORE_TOOL" "$TXB" \
  --password-file "$TMP/server/.backup-key" \
  --target "$TMP/preserved-server" \
  --backend-port 28084 \
  --web-target "$TMP/preserved-web" \
  --web-port 4904 \
  --keep-web-api >/dev/null
grep -q 'TIANXIAN_API_BASE_URL=https://example.invalid' "$TMP/preserved-web/.env"
if grep -q 'txb_web_api' "$TMP/preserved-web/docker-compose.yml"; then
  echo 'Preserve mode should not install unnecessary local API network' >&2
  exit 1
fi

# Custom domain mode must override the original backed-up production API.
"$RESTORE_TOOL" "$TXB" \
  --password-file "$TMP/server/.backup-key" \
  --target "$TMP/custom-server" \
  --backend-port 28085 \
  --web-target "$TMP/custom-web" \
  --web-port 4905 \
  --web-api-url 'https://eq.example.test' >/dev/null
grep -q 'TIANXIAN_API_BASE_URL=https://eq.example.test' "$TMP/custom-web/.env"

echo 'TXB Web smoke passed (auto private API, explicit preserve/custom API, multi-IP).'

