# TianXian Web TXB 扩展

此目录为 TianXian Server V1 TXB 增加可选的 Web 快照扩展，同时保持旧 TXB 可恢复。

## 目标

新备份可包含：

```text
database/postgres.dump
config/env.enc
server/server-release.tar.gz
web/web-release.tar.gz
web/web-env.enc
web/web-metadata.env
metadata/manifest.env
manifest.json
checksums/SHA256SUMS
```

其中 `web/` 为可选目录。旧 TXB 没有 `web/` 时，新恢复器会直接跳过 Web，不影响 Server/PostgreSQL 恢复。

## Web 自动检测

备份器按以下顺序寻找 TianXian Web：

1. `--web-dir DIR`
2. Docker 容器 `tianxian-web` 的 Compose 工作目录
3. 常用目录：
   - `/opt/TianXian-Web`
   - `/opt/tianxian-web`
   - `/home/ubuntu/tianxian-web`
   - `/srv/tianxian-web`
4. `/opt /home /srv /root` 下的 TianXian Web `package.json`

识别条件包括 `package.json` 中的 `"name": "tianxian-web"` 与 `docker-compose.yml`。

检测不到 Web 时，备份仍正常完成，只是不包含 Web。

## Web 快照内容

`web-release.tar.gz` 保存可重建的 Web 项目，并排除：

- `.env*`
- `node_modules/`
- `.next/`
- `.git/`
- `*.log`

Web `.env` 不明文放入快照；存在时使用与 Server 相同的 backup-key 加密为 `web-env.enc`。

## 端口

当前默认：

- Backend：宿主机 `18080` → 容器 `8000`
- Web：宿主机 `3800` → 容器 `3000`

新恢复器支持：

```bash
--backend-port PORT
--backend-bind 127.0.0.1,192.168.6.1
--web-port PORT
--web-bind 127.0.0.1,192.168.6.1
--web-target DIR
--skip-web
```

多个 IPv4 地址使用**英文逗号**分隔，所有选中地址共用该服务的端口。必须保证这些地址已配置在恢复服务器的本机网卡。对于 `0.0.0.0`，只能单独输入，不能与其他 IP 并列；它监听所有 IPv4 网卡，必须结合防火墙确认公网访问范围。

裸机恢复入口会在**服务启动前**询问两项服务的监听地址和端口，检查本地 IP 存在性、重复绑定及端口占用。选择 `0.0.0.0` 会要求再次输入 `EXPOSE`。

需要恢复指定 TXB 而不是“最新一份”时，支持：

```bash
tianxian-recover --r2-key \
  "tianxian-backups/manual/TianXian-Backup-20261002-023345-manual.txb.enc"
```

对于之前生成的 TXB，裸机恢复入口会在验证备份后获取带新监听功能的恢复器；无需为了更换监听地址重新制作旧备份。

## 安装到现有 Server

测试分支：

```bash
curl -fsSL \
https://raw.githubusercontent.com/sockc/TianXianFruit/main/server-tools/txb-web/install.sh \
-o /tmp/tianxian-web-txb-install.sh

bash -n /tmp/tianxian-web-txb-install.sh \
&& chmod +x /tmp/tianxian-web-txb-install.sh \
&& /tmp/tianxian-web-txb-install.sh
```

Manager 也提供：

```bash
tianxian web-txb-install
```

安装器会先备份旧版 `tianxian-backup` / `tianxian-restore`，再替换工具。

## 备份

安装后，原有手动/cron 命令无需改变。备份器会自动检测 Web。

例如：

```bash
cd /opt/TianXian-Server
./tianxian-backup --type manual --password-file .backup-key --r2
```

有 Web 时应在输出中看到 Web snapshot 信息；解包 TXB 后应存在 `web/` 三个文件（无 Web .env 时没有 `web-env.enc`）。

## 兼容原则

- TXB 主格式仍为 `backup_format=1`
- Web 扩展使用 `TXB_WEB_EXTENSION_VERSION=1`
- 旧 TXB → 新恢复器：支持，自动跳过 Web
- 新 TXB → 新恢复器：恢复 Server + PostgreSQL + Web
- 旧恢复器不负责恢复 Web，因此需要 Web 灾难恢复时必须使用本目录的新恢复器

## 自动测试

GitHub Actions 会执行：

1. 所有 Shell 脚本 `bash -n`
2. 构造假的 Server + Web
3. 生成带 Web 的 TXB
4. 验证 Web 快照与加密 env
5. 恢复到新目录
6. 验证 Backend 端口修改
7. 验证 Web 端口修改
8. 验证 Web env/源码恢复

## 恢复监听默认值

新 VPS 恢复时，不会自动继承旧 TXB 的宿主机 IP/端口。备份原设置仅在提示前显示供参考。
无论原服务器是 `192.168.x.x`、`0.0.0.0` 还是其他端口，**直接回车的安全默认值**始终是：

- Server：`127.0.0.1:18080`
- Web：`127.0.0.1:3800`

确实需要局域网多地址监听时，手动输入以英文逗号分隔的本机 IPv4 地址。
选择 `0.0.0.0` 必须单独填写并再次确认，避免无意暴露数据库的 Web/API 接口。
Web 默认通过专用 Docker 网络 `http://backend:8000` 连接 Server，
因此宿主机选择 `127.0.0.1` 不影响它们的内部通信。

## Web API 自动迁移

新版恢复器默认自动将 Web API 地址改为 `http://backend:8000`，
同时为 Backend 与 Web 的 Compose 新建持久的专用 Docker 桥接网络。
此地址仅供 Web 容器访问恢复后的 Backend，不依赖宿主机绑定的 IP 或端口，
也不需要手动在 Web `.env` 中填写新 VPS 的局域网 IP。

裸机恢复在 Web 监听选项之后询问 API 连接方式：

1. **自动使用私有 Docker 网络（默认）**。新 VPS 和隔离测试优先选此项，防止访问原生产 API。
2. **保留备份中的 API 地址**。仅用于正式原域名迁移；需自行切换 DNS/Lucky 并确认它们指向新服务器。
3. **指定 HTTP(S) API URL**。用于已经准备好的新测试域名或其他指定接口。

独立恢复器默认选择自动模式。可显式传入 `--keep-web-api`
或 `--web-api-url https://new-api.example.com`。新版本对 Web 首页和
Web 容器内部的 API `/health` 都进行检查；但若保留原域名，接口可访问
并不能证明 DNS 已指向新服务器。

旧 TXB 不必重做：新版恢复器在恢复时改写 Web 配置，
不会修改已经存放在 R2 上的原始 TXB。
此前已经恢复成功的 EQ 不会因仓库代码更新而自动变动。
Android APK 若使用原域名仍需进行 DNS/Lucky 切换；
如果改用新域名，APK 仍须更改自己的服务器设置。

## 在 EQ 测试时注意

新版默认通过私有 Docker 网络连接 EQ；旧版恢复器才需手动修改 Web `.env`。如果主动选择保留备份里的 API 域名，须先确认 DNS/Lucky 已迁移，才能做业务写入测试。

恢复不会自动修改 Lucky、DNS 或生产服务器，也不会默认开启自动备份。
