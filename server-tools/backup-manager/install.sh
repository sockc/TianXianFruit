#!/usr/bin/env bash
set -Eeuo pipefail

[[ ${EUID:-$(id -u)} -eq 0 ]] || { echo '请使用 root 运行安装脚本。' >&2; exit 1; }

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SRC="$SCRIPT_DIR/tianxian-manager"
DST="/usr/local/sbin/tianxian"

[[ -f "$SRC" ]] || { echo "缺少 $SRC" >&2; exit 1; }
install -m 0755 "$SRC" "$DST"
mkdir -p /etc/tianxian-manager /var/log/tianxian-manager
chmod 700 /etc/tianxian-manager
chmod 700 /var/log/tianxian-manager

echo "已安装：$DST"
echo "运行：sudo tianxian"
echo "首次运行会自动识别现有 TianXian Server，并复用 .backup-key / .r2.env。"
