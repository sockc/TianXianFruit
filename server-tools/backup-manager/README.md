# 天鲜账本服务器一键备份 / 恢复管理器

这是现有 TianXian Server TXB V1 工具链的上层管理器，不改变备份格式，也不替代已经验证过的 `tianxian-backup`、`tianxian-backup-check`、`tianxian-restore`、R2 工具。

## 目标

- 一条命令进入服务器管理：`sudo tianxian`
- 一键生成 TXB，本地校验后上传 R2（已配置时）
- 一键从最新 R2 备份恢复
- 可选择历史 R2 备份恢复
- 本地 TXB 恢复
- 恢复前强制创建 `pre-restore` 快照
- 恢复前先做 TXB 完整性 / 密钥校验
- 恢复后自动检查 `127.0.0.1:18080/health` 与 PostgreSQL
- 继续使用现有 `.backup-key`、`.r2.env` 和 TXB V1，不制造第二套密钥体系

## 已兼容的既有基线

- Server V1.0.17/1.0.18-Lucky 一代 TXB V1 工具链
- `tianxian-backup --type manual --password-file ... --domain ...`
- `tianxian-backup-check`
- `tianxian-restore ... --password-file ... --target ...`
- `tianxian-backup-list`
- `tianxian-r2-download`
- `scripts/init-r2-config.sh`
- `scripts/install-txb-cron.sh`

默认正式后端：`127.0.0.1:18080`。

## 安装

把本目录放到服务器后：

```bash
cd server-tools/backup-manager
chmod +x install.sh tianxian-manager
sudo ./install.sh
sudo tianxian
```

首次运行会自动尝试识别：

```text
/opt/TianXian-Server
/home/ubuntu/TianXian-Server_V1.0.1-Lucky
```

并默认复用 Server 目录内的 `.backup-key`。

> 如果服务器已有 TXB，绝对不要生成新的 backup-key 来替代原密钥。必须使用原 `.backup-key`，否则旧备份中的加密配置无法恢复。

## 菜单

```text
1. 一键备份（本地 + R2）
2. 一键恢复最新云端备份
3. 选择历史云端备份恢复
4. 从本地 TXB 恢复
5. 查看云端备份列表
6. 系统检查
7. 配置管理
8. 查看备份/管理日志
0. 退出
```

## 密码与配置

管理器自身只保存路径和非秘密设置：

```text
/etc/tianxian-manager/config.env
```

权限为 `600`。

敏感信息不复制到 Manager 配置：

- TXB 密钥继续放在现有 `.backup-key`
- R2 Access Key / Secret 继续由 Server 原有 `.r2.env` 管理
- 数据库密码继续由恢复后的 Server `.env` 管理

R2 新配置直接调用：

```bash
/opt/TianXian-Server/scripts/init-r2-config.sh
```

这样不会同时维护两份 R2 密码。

## 恢复保护

每次恢复执行顺序固定为：

```text
校验目标 TXB
→ 要求输入 RESTORE
→ 生成 pre-restore TXB
→ 校验 pre-restore TXB
→ 调用现有 tianxian-restore
→ 检查 /health
→ 检查 PostgreSQL
```

管理器不会执行：

```bash
docker compose down -v
```

避免误删 PostgreSQL volume。

## 自动备份

仍然复用 Server 自带：

```bash
scripts/install-txb-cron.sh
```

当前既有策略可以继续保持 daily / weekly / pre-upgrade，并由 `--r2-if-configured` 在 R2 已配置时上传。

## 后续阶段

真正“裸 VPS 一条命令灾难恢复”建议作为下一阶段：安装 Docker → 获取恢复工具 → 首次录入 R2 → 下载 `.txb.enc` → 解密 → 恢复 `/opt/TianXian-Server` → 健康检查。它必须绑定一个稳定的 Server Release 下载源，避免灾难时下载到与 TXB 不兼容的恢复工具。
