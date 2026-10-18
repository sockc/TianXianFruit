#!/usr/bin/env python3
"""Repair a deployed TianXian Server/Web pair without touching PostgreSQL data."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
from datetime import datetime


def run(args, cwd=None, check=True):
    return subprocess.run(args, cwd=cwd, text=True, capture_output=True, check=check)


def compose_config(directory):
    result = run(["docker", "compose", "config", "--format", "json"], cwd=directory)
    return json.loads(result.stdout)


def host_addresses():
    raw = json.loads(run(["ip", "-j", "-4", "addr", "show"]).stdout)
    found = []
    for interface in raw:
        for item in interface.get("addr_info", []):
            if item.get("family") == "inet":
                found.append(ipaddress.ip_interface(
                    f'{item["local"]}/{item["prefixlen"]}'))
    return found


def choose_binds(old, assigned, override=None):
    addresses = [str(addr.ip) for addr in assigned]
    if override:
        selected = [value.strip() for value in override.split(",")]
    else:
        selected = []
        for value in old:
            if value == "0.0.0.0" or value in addresses:
                candidate = value
            else:
                matches = [str(addr.ip) for addr in assigned
                           if not addr.ip.is_loopback and not addr.ip.is_link_local
                           and ipaddress.ip_address(value) in addr.network]
                if len(matches) != 1:
                    raise ValueError(f"旧监听 IP {value} 已失效，无法唯一确定替代地址；请通过 --backend-bind/--web-bind 指定")
                candidate = matches[0]
                print(f"  失效 IP：{value} → {candidate}")
            if candidate not in selected:
                selected.append(candidate)
    if not selected or any(not value for value in selected):
        raise ValueError("监听 IP 不可为空")
    for value in selected:
        ipaddress.IPv4Address(value)
        if value != "0.0.0.0" and value not in addresses:
            raise ValueError(f"本机没有 IP：{value}")
    if "0.0.0.0" in selected and len(selected) != 1:
        raise ValueError("0.0.0.0 不能与其他监听地址混用")
    return selected


def service_ports(config, service, target):
    ports = [p for p in config["services"][service].get("ports", [])
             if str(p.get("target")) == str(target) and p.get("protocol", "tcp") == "tcp"]
    if not ports:
        raise ValueError(f"{service} 未定义容器 {target}/tcp 的宿主机端口")
    published = {str(p["published"]) for p in ports}
    if len(published) != 1:
        raise ValueError(f"{service} 当前有多个外部端口，请先手动核对：{published}")
    return next(iter(published)), [p.get("host_ip") or "0.0.0.0" for p in ports]


def service_block(text, name):
    start = re.search(r"(?m)^  " + re.escape(name) + r":[ \t]*$", text)
    if not start:
        raise ValueError(f"Compose 未找到服务 {name}")
    tail = re.search(r"(?m)^(?:  [A-Za-z0-9_-]+:|[A-Za-z0-9_-]+:)", text[start.end():])
    end = start.end() + tail.start() if tail else len(text)
    return start.start(), end, text[start.start():end]


def patch_ports(text, service, target, port, binds):
    start, end, block = service_block(text, service)
    lines = block.splitlines(keepends=True)
    indexes = []
    quote = '"'
    indent = "      "
    for i, line in enumerate(lines):
        m = re.fullmatch(r'([ \t]*)-[ \t]*(["\x27]?)(.+?)\2[ \t]*\n?', line)
        if not m:
            continue
        value = m.group(3)
        parts = value.removesuffix("/tcp").split(":")
        if len(parts) not in (2, 3) or parts[-1] != str(target):
            continue
        if not all(part.isdigit() for part in parts[-2:]):
            continue
        indexes.append(i)
        indent, quote = m.group(1), m.group(2) or '"'
    if not indexes:
        raise ValueError(f"{service} 的 ports 不包含短格式 {target} 映射；未修改文件")
    replacements = [f'{indent}- {quote}{ip}:{port}:{target}{quote}\n' for ip in binds]
    first = indexes[0]
    lines[first] = "".join(replacements)
    for i in reversed(indexes[1:]):
        del lines[i]
    return text[:start] + "".join(lines) + text[end:]


def patch_backend_alias(text, network):
    start, end, block = service_block(text, "backend")
    lines = block.splitlines(keepends=True)
    net_index = next((i for i, s in enumerate(lines)
                      if re.match(r"^    networks:[ \t]*$", s.rstrip("\n"))), None)
    if net_index is None:
        raise ValueError("backend 缺少 networks，拒绝覆盖现有网络")
    tail = next((i for i in range(net_index + 1, len(lines))
                 if lines[i].strip() and len(lines[i]) - len(lines[i].lstrip(" ")) < 6),
                len(lines))
    entries = lines[net_index + 1:tail]
    list_names = []
    all_list = True
    for line in entries:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        m = re.match(r"^      - ([A-Za-z0-9_-]+)[ \t]*$", line.rstrip("\n"))
        if not m:
            all_list = False
            break
        list_names.append(m.group(1))
    if all_list:
        if network not in list_names:
            raise ValueError(f"backend 不在共享网络 {network} 上")
        replacement = []
        for name in list_names:
            if name == network:
                replacement.extend([f"      {name}:\n", "        aliases:\n", "          - backend\n"])
            else:
                replacement.append(f"      {name}: {{}}\n")
        lines[net_index + 1:tail] = replacement
    else:
        idx = next((i for i in range(net_index + 1, tail)
                    if re.match(r"^      " + re.escape(network) + r":[ \t]*(?:\{\})?[ \t]*$",
                                lines[i].rstrip("\n"))), None)
        if idx is None:
            raise ValueError(f"无法安全编辑 backend 的 {network} 网络别名")
        nested_end = next((i for i in range(idx + 1, tail)
                           if lines[i].strip() and len(lines[i]) - len(lines[i].lstrip(" ")) <= 6),
                          tail)
        if not any(re.match(r"^        aliases:[ \t]*$", v.rstrip("\n"))
                   for v in lines[idx + 1:nested_end]):
            lines[idx] = f"      {network}:\n"
            lines.insert(idx + 1, "        aliases:\n          - backend\n")
        elif not any(re.match(r"^          - backend[ \t]*$", v.rstrip("\n"))
                     for v in lines[idx + 1:nested_end]):
            alias_idx = next(i for i in range(idx + 1, nested_end)
                             if re.match(r"^        aliases:[ \t]*$", lines[i].rstrip("\n")))
            lines.insert(alias_idx + 1, "          - backend\n")
    return text[:start] + "".join(lines) + text[end:]


def shared_network(server, web):
    a = server["services"]["backend"].get("networks") or {}
    b = web["services"]["tianxian-web"].get("networks") or {}
    for logical in set(a) & set(b):
        a_net = server.get("networks", {}).get(logical, {})
        b_net = web.get("networks", {}).get(logical, {})
        if a_net.get("name") == b_net.get("name") and a_net.get("name"):
            return logical, a_net["name"]
    raise ValueError("Server/Web 没有指向同一个 Docker 共享网络，请先检查 Compose")


def update_manager_config(path, backend_port, backend_binds, web_port, web_binds):
    if not path.is_file():
        print("未找到 Manager 配置，跳过同步")
        return
    value = path.read_text()
    changes = {
        "BACKEND_PORT": backend_port,
        "BACKEND_BIND": ",".join(backend_binds),
        "WEB_PORT": web_port,
        "WEB_BIND": ",".join(web_binds),
    }
    for key, setting in changes.items():
        statement = key + "=" + shlex.quote(str(setting))
        pattern = r"(?m)^" + key + r"=.*$"
        value = re.sub(pattern, lambda _: statement, value) if re.search(pattern, value) else value + "\n" + statement
    path.write_text(value.rstrip("\n") + "\n")
    path.chmod(0o600)


def main():
    parser = argparse.ArgumentParser(description="修复天鲜服务器失效的绑定 IP、Web 内网别名与管理中心端口")
    parser.add_argument("--server-dir", default="/opt/TianXian-Server")
    parser.add_argument("--web-dir", default="/opt/TianXian-Web")
    parser.add_argument("--backend-port", type=int)
    parser.add_argument("--web-port", type=int)
    parser.add_argument("--backend-bind", help="如 127.0.0.1,192.168.6.203")
    parser.add_argument("--web-bind")
    parser.add_argument("--apply", action="store_true", help="实际写入并重建后端和 Web；需交互确认")
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.error("需要 root 权限")
    server_dir, web_dir = Path(args.server_dir), Path(args.web_dir)
    server_file, web_file = server_dir / "docker-compose.yml", web_dir / "docker-compose.yml"
    if not server_file.is_file() or not web_file.is_file():
        parser.error("找不到 Server/Web docker-compose.yml")
    try:
        server = compose_config(server_dir)
        web = compose_config(web_dir)
        net_key, net_name = shared_network(server, web)
        s_port, s_ips = service_ports(server, "backend", 8000)
        w_port, w_ips = service_ports(web, "tianxian-web", 3000)
        s_port = args.backend_port or int(s_port)
        w_port = args.web_port or int(w_port)
        if any(not (1 <= p <= 65535) for p in (s_port, w_port)):
            raise ValueError("端口超出 1～65535")
        assigned = host_addresses()
        s_binds = choose_binds(s_ips, assigned, args.backend_bind)
        w_binds = choose_binds(w_ips, assigned, args.web_bind)
        if s_port == w_port and ("0.0.0.0" in s_binds + w_binds or set(s_binds) & set(w_binds)):
            raise ValueError("Server/Web 监听地址与端口冲突")
        s_text = patch_backend_alias(patch_ports(server_file.read_text(), "backend", 8000, s_port, s_binds), net_key)
        w_text = patch_ports(web_file.read_text(), "tianxian-web", 3000, w_port, w_binds)
    except (KeyError, ValueError, subprocess.CalledProcessError) as exc:
        print(f"✗ 无法安全修复：{exc}", file=sys.stderr)
        return 1

    print(f"共享网络：{net_name}（backend 显式别名）")
    print(f"Server：{','.join(s_binds)}:{s_port} → 8000")
    print(f"Web：{','.join(w_binds)}:{w_port} → 3000")
    print("PostgreSQL 不参与重建，不修改数据库/备份密钥。")
    if not args.apply:
        print("以上仅为预览。确认后运行：tianxian repair --apply")
        return 0
    if input("将备份配置并重建 backend、Web。输入 REPAIR 确认：").strip() != "REPAIR":
        print("已取消")
        return 1

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = server_dir / "backups" / "network-repair" / stamp
    backup.mkdir(parents=True, mode=0o700, exist_ok=False)
    shutil.copy2(server_file, backup / "server-compose.yml")
    shutil.copy2(web_file, backup / "web-compose.yml")
    config_path = Path("/etc/tianxian-manager/config.env")
    if config_path.exists():
        shutil.copy2(config_path, backup / "manager-config.env")
    try:
        server_file.write_text(s_text)
        web_file.write_text(w_text)
        run(["docker", "compose", "config", "--quiet"], cwd=server_dir)
        run(["docker", "compose", "config", "--quiet"], cwd=web_dir)
    except (OSError, subprocess.CalledProcessError) as exc:
        shutil.copy2(backup / "server-compose.yml", server_file)
        shutil.copy2(backup / "web-compose.yml", web_file)
        print(f"✗ Compose 校验失败，已还原文件：{exc}", file=sys.stderr)
        return 1

    print("正在只重建 backend、Web（不触碰 PostgreSQL）...")
    try:
        run(["docker", "compose", "up", "-d", "--no-deps", "--force-recreate", "backend"], cwd=server_dir)
        run(["docker", "compose", "up", "-d", "--no-deps", "--force-recreate", "tianxian-web"], cwd=web_dir)
        addr = "127.0.0.1" if "127.0.0.1" in s_binds or "0.0.0.0" in s_binds else s_binds[0]
        status = run(["curl", "-fsS", "--max-time", "10", f"http://{addr}:{s_port}/health"])
        print("Server:", status.stdout.strip())
        script = ('fetch("http://backend:8000/health",{signal:AbortSignal.timeout(10000)})'
                  '.then(async r=>{const j=await r.json();if(!r.ok||j.ok!==true||j.service!=="tianxian-sync")throw Error("API health invalid");console.log("Web → Server: HTTP",r.status)})'
                  '.catch(e=>{console.error(e.message);process.exitCode=1})')
        check = run(["docker", "exec", "tianxian-web", "node", "-e", script])
        print(check.stdout.strip())
    except (OSError, subprocess.CalledProcessError) as exc:
        print(f"✗ 服务重建或健康检查失败：{exc}", file=sys.stderr)
        print(f"配置备份：{backup}；保留新文件以便排查，未自动回退到已失效的旧 IP。", file=sys.stderr)
        return 1

    update_manager_config(config_path, s_port, s_binds, w_port, w_binds)
    print(f"✓ 修复完成，配置备份：{backup}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
