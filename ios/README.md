# 天鲜账本 iOS V0.6.7 · Build 44 — 测试账本单笔当日结算原子写入

## 当前版更新
- **实际结算操作**：在 iOS_TEST_ 测试账本中选择既有 DAILY 当日转账，支持部分结算、全额结算及恢复未结；所有变化经新的服务器 V1.0.31 原子事务处理，直接使用安卓原有“修改转账 settled_amount + 更新 daily_cash_settlement.status”规则。
- **服务端版本与权限识别**：客户端仅在在线授权、可编辑测试账本，并且 /api/v1/settlements/capabilities 返回正确 feature=settlement_transfer_atomic_v1、atomic/idempotency/operation_lookup 等能力时显示实际提交入口。否则继续使用本机核对单，不会尝试写入。
- **异常防重复**：提交前先把账号、账本、不可变操作UUID、日结/转账 sync_id、row_version、目标登记金额原子保存到本机文件。确认请求超时、崩溃或断网时，禁止创建新 ID 再发 POST；仅通过 /operations/{原ID} 查询结果。
- **严格回读**：服务端返回确认后客户端同步检查两条更新过的 sync_records（转账金额和日结原始状态）、版本及同步游标；未完整回读的操作一直保留锁定。
- **原始记录安全**：在预审阶段核对 Android 精确64位日结编号、转账与日结关联、单日唯一性、金额精度，拒绝超限、负数、无变动、错误日期或已变更的记录。
- **测试**：新增接口能力、编码金额、持久化操作锁、错误回执和双记录云端回读的自动单测。iOS 源码不修改 Android 当日结算算法。
- **尚未开放功能**：生成当日结算方案、利润分配、跨日结清截至今日、计算当前累计资金余额及正式账本写入全部保持锁定；后续需要服务端权威计算接口后再开放。

## 对应服务器升级
从用户文件空间里的 V1.0.30 完整修复源码构建了 TianXian-Server V1.0.31 完整包与补丁包。新增：
- GET /api/v1/settlements/capabilities
- POST /api/v1/settlements/commit（只对 iOS_TEST_ 生效）
- GET /api/v1/settlements/operations/{operation_id}
- PostgreSQL settlement_operations 幂等记录表，同步与审计原子落库。

服务器补丁脚本会备份源码与 .env，核验线上版本 1.0.30，运行专项测试，启用仅测试账本的结算能力，升级到 1.0.31，失败回滚。GitHub Actions 只验证 iOS 客户端；它不代表用户真实服务器已经升级。没有部署服务端前，Build44 的实际结算按钮仍不可用。

## 安装
从 GitHub Actions 下载 TianXianFruit-iOS-v0.6.7-Build44-TrollStore，解压用 TrollStore 在 iPhone X (iOS 16.6) 覆盖安装，勿卸载以保留本机草稿和操作锁。
