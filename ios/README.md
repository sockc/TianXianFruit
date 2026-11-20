# 天鲜账本 iOS V0.4.8 — 正式营业隔离保护

**版本 0.4.8 / Build 28**。与 TianXian-Server V1.0.27 的限制一致：只有名称严格以 `iOS_TEST_` 开头的测试账本才可能启用正式营业事务提交。其他账本依然可查看和保存本机营业草稿，但「提交正式营业」始终禁用。

服务端还要求在线编辑权限、设备验证及 `TIANXIAN_BUSINESS_COMMIT_ENABLED=true`，默认关闭。客户端开关或缓存不构成授权。未取得服务器能力时，保持禁用。旧安卓结算规则不变。V1.0.27 服务端还将 PostgreSQL 建表和兼容迁移放入同一 advisory transaction lock 防并发重复创建数据库对象。

当前源码已提交 GitHub；iOS IPA 编译与安卓/iOS 双端联调未验证，不宣称已可发布。

---

# 天鲜账本 iOS V0.4.7 — 安卓营业结构对齐

**版本 0.4.7 / Build 27**，支持 iPhone X / iOS 16.6 / TrollStore（最低 iOS 15），Bundle ID 不变。覆盖安装保留本机草稿；请勿卸载旧版。

## 本版修改

- 找回资料库中 **TianXian-Server V1.0.25-PurchaseAtomic FULL** 真实源码，并在其基础上编写 **Server V1.0.26-BusinessAtomic FULL/PATCH**（当前只是交付源码，没有部署线上）。
- Android 正式营业逐人收款实际存储在 `store_daily_record.receipt_splits_json`；修复 V0.4.6 中误认为独立 `store_daily_collector` 的回读逻辑。
- iOS 录入表增加必填「支出 / 开摊库存估值 / 收摊库存估值」，对旧本机草稿缺少这些值的情况保持空白，禁止默认为零。
- 服务端采用 Android 现有利润计算式：`profit = revenue + stock_left_value - opening_stock_value - expense`。客户端将四项作为不可变事务载荷的一部分，服务端自行复算。
- 保留营业草稿账号/账本隔离、服务器事务能力检测、双次完整刷新、同日同位置防重复、SQLite 操作锁、异常只读查询和回读校验。
- 新服务端营业接口默认关闭：`TIANXIAN_BUSINESS_COMMIT_ENABLED=false`，即使客户端版本升级，也不会自动开放正式账本写入。

## 服务器匹配条件

正式服务器升级、PostgreSQL 并发核验、安卓客户端真实字段回读、账号权限与独立测试账本确认之前，不得手动打开营业写入开关。当前未部署 V1.0.26，iOS 上传按钮依然禁用。

V0.4.7 **不修改安卓当日结算、利润分配、资金转账及凌晨收款算法**。一切正式营业提交须使用专用原子事务，不通过 `/sync/push` 伪造多记录事务。

详见 [BUSINESS_COMMIT_CONTRACT.md](BUSINESS_COMMIT_CONTRACT.md)。

## 验证状态

- 服务端 V1.0.25 原采购原子测试 8 项通过；新增 V1.0.26 营业事务测试 4 项通过，测试是独立离线环境。
- Python 服务端源码 `compileall` 通过；尚未获得 iOS GitHub Actions 编译成功证明，也未进行线上 PostgreSQL/安卓跨端联合测试。
