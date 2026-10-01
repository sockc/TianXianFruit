#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

BRANCH="${TIANXIAN_TOOLS_BRANCH:-main}"
BASE_URL="https://raw.githubusercontent.com/sockc/TianXianFruit/${BRANCH}/server-tools/txb-web"
SERVER_DIR="${1:-}"

fail(){ echo "✗ $*" >&2; exit 1; }
ok(){ echo "✓ $*"; }
info(){ echo "ℹ $*"; }

[[ ${EUID:-$(id -u)} -eq 0 ]] || fail "请使用 root 运行。"

if [[ -z "$SERVER_DIR" && -f /etc/tianxian-manager/config.env ]]; then
  # shellcheck disable=SC1091
  source /etc/tianxian-manager/config.env || true
  SERVER_DIR="${SERVER_DIR:-}"
fi

if [[ -z "$SERVER_DIR" ]]; then
  for d in /opt/TianXian-Server /home/ubuntu/TianXian-Server_V1.0.1-Lucky; do
    if [[ -f "$d/scripts/tianxian-backup" && -f "$d/scripts/tianxian-restore" ]]; then
      SERVER_DIR="$d"
      break
    fi
  done
fi

[[ -n "$SERVER_DIR" ]] || fail "未找到 TianXian Server。可传入目录：install.sh /path/to/TianXian-Server"
[[ -f "$SERVER_DIR/scripts/tianxian-backup" ]] || fail "缺少 $SERVER_DIR/scripts/tianxian-backup"
[[ -f "$SERVER_DIR/scripts/tianxian-restore" ]] || fail "缺少 $SERVER_DIR/scripts/tianxian-restore"

TMP="$(mktemp -d /tmp/tianxian-web-txb-install.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

info "下载 Web TXB 备份/恢复工具..."
curl -fsSL "$BASE_URL/tianxian-backup" -o "$TMP/tianxian-backup"
curl -fsSL "$BASE_URL/tianxian-restore" -o "$TMP/tianxian-restore"

bash -n "$TMP/tianxian-backup"
bash -n "$TMP/tianxian-restore"
chmod 0755 "$TMP/tianxian-backup" "$TMP/tianxian-restore"

STAMP="$(date '+%Y%m%d-%H%M%S')"
SAFE="$SERVER_DIR/backups/tool-backups/$STAMP"
mkdir -p "$SAFE"
cp -a "$SERVER_DIR/scripts/tianxian-backup" "$SAFE/tianxian-backup"
cp -a "$SERVER_DIR/scripts/tianxian-restore" "$SAFE/tianxian-restore"

install -m 0755 "$TMP/tianxian-backup" "$SERVER_DIR/scripts/tianxian-backup"
install -m 0755 "$TMP/tianxian-restore" "$SERVER_DIR/scripts/tianxian-restore"

grep -q -- '--web-dir DIR' "$SERVER_DIR/scripts/tianxian-backup" || fail "备份脚本安装后未检测到 Web TXB 支持。"
grep -q -- '--web-port PORT' "$SERVER_DIR/scripts/tianxian-restore" || fail "恢复脚本安装后未检测到 Web 端口支持。"

ok "Web TXB 支持已安装"
echo "Server: $SERVER_DIR"
echo "旧工具备份: $SAFE"
echo
info "以后手动和 cron 自动 TXB 备份都会自动检测 TianXian Web。"
info "检测不到 Web 时只备份 Server/数据库，不会导致备份失败。"
