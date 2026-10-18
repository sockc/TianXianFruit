#!/usr/bin/env bash
set -Eeuo pipefail

[[ ${EUID:-$(id -u)} -eq 0 ]] || { echo '请使用 root 运行安装脚本。' >&2; exit 1; }

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SRC="$SCRIPT_DIR/tianxian-manager"
DST="/usr/local/sbin/tianxian"
REPAIR_SRC="$SCRIPT_DIR/tianxian_repair.py"
REPAIR_DST="/usr/local/sbin/tianxian-repair"

[[ -f "$SRC" ]] || { echo "缺少 $SRC" >&2; exit 1; }
[[ -f "$REPAIR_SRC" ]] || { echo "缺少 $REPAIR_SRC" >&2; exit 1; }
python3 -m py_compile "$REPAIR_SRC"
install -m 0755 "$SRC" "$DST"
install -m 0755 "$REPAIR_SRC" "$REPAIR_DST"
mkdir -p /etc/tianxian-manager /var/log/tianxian-manager
chmod 700 /etc/tianxian-manager
chmod 700 /var/log/tianxian-manager

echo "已安装：$DST 和 $REPAIR_DST"
echo "运行：sudo tianxian"
echo "首次运行会自动识别现有 TianXian Server，并复用 .backup-key / .r2.env。"
