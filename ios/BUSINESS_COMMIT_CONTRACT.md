# iOS V0.4.6 — 正式营业事务接口契约（待服务端实现）

> **状态：客户端已接入；正式 TianXian-Server 未部署此接口，默认禁用。**
> 不能将此文件当作已经交付的服务端实现，也不得将旧 `/api/v1/sync/push` 当作替代品。

## 受限 API

- `GET /api/v1/business/capabilities?book_id=<uuid>`
- `POST /api/v1/business/commit`
- `GET /api/v1/business/operations/<operation_uuid>?book_id=<uuid>`

只有正式服务端返回 HTTP 200 JSON：
`{"version":1,"enabled":true,"atomic":true,"idempotency":true,"supports_collector_splits":true,"feature":"business_atomic_commit_v1"}`
并通过当前账号在线授权后，iOS 才能启用提交按钮。不存在、超时、响应字段不完整、开关关闭或离线，一律禁止写入。

## 原子请求

`POST /commit` 传入 `protocol_version=1`、`book_id`、`account`、`operation_id`（本机草稿 UUID）、`device_id`（现有注册设备哈希）、`date`、`store_sync_id`、`store_name`、`collectors` 数组（`partner_sync_id`, `wechat`, `alipay`, `cash`），`actual_start_time`、`actual_end_time`、汇总 `wechat/alipay/cash/total_revenue` 与 `payload_hash`。金额规范字符串两位小数；服务端必须自行重新验证、禁止相信客户端提供的汇总或哈希。

服务端必须在 **同一个 PostgreSQL 事务**完成：
1. 验证 JWT、设备身份、账号账本编辑权限、所有 `partner_sync_id` 与位置属于同一账本且未删除，拒绝跨账本 ID；同一事务中重新验证权限。
2. 验证自然日、跨午夜时间、非负/精确到分的金额，收款人不得重复；按整数分重新计算每人及渠道总计，不允许浮点误差。
3. 通过账本+自然日+位置唯一约束及事务内串行化或相当级别锁，拒绝任何已存在记录；处理安卓同时录入、并发 iOS 提交，且必须保留旧逻辑对多位置的支持。
4. 通过唯一 `(book_id,operation_id)` 幂等表保存不可变请求指纹、提交状态和回执。同 UUID 同 payload 返回 `already_committed`，同 UUID 不同 payload 返回 409，不能再次落账；回执与账表更新原子提交。
5. 同事务写入安卓现有营业主表及逐收款人关联表，保证外键/收款人归属与 Android 同步逻辑一致。**当前客户端暂按 `store_daily_collector` 作为约定的回读表名；正式实现时需依据安卓实际 schema 统一更正契约，不可凭此名称猜建数据库表。**
6. 由服务端产生同步事件和递增游标，并将同一组主表+收款人事件对安卓可见。
7. 如果营业日已发生利润分配、资金结算、凌晨收款引用或其他依赖关系且会导致账务不一致，拒绝直接修改；V0.4.6 仅新增业务，不实现历史更正、删除、分配、结算、凌晨收款计算。
8. 只读 `GET /operations/{id}` 返回原始提交结果，并核验当前账号账本权限；丢失响应时客户端只能 GET 查询，不得重新 POST。404 不是自动重试许可。

成功回执：
`{"status":"committed","operation_id":"...","book_id":"...","record_sync_id":"...","collector_sync_ids":["..."],"payload_hash":"sha256","sync_cursor":123}`

回读阶段需按回执里的 UUID / sync_id 对照完整主表营业金额、日期、位置及每人微信支付宝现金数据，确认前保持锁定。

## 部署限制

- 必须先获得与 **当前 TianXian-Server V1.0.25** 完全一致的正式服务端源码、迁移文件及测试数据库；没有这些文件，无法安全实现数据库事务、权限检查、同步事件与安卓兼容性。
- 线上开关必须默认 `TIANXIAN_BUSINESS_COMMIT_ENABLED=false`，且关闭时服务端拒绝 POST（不只是隐藏按钮）。
- 上线前使用独立测试账本核验 Android 与 iOS 相互同步、同日同位置并发、断网未知状态、重复 UUID、跨账本引用、已结算日拒绝写入，以及未修改任何现有结算算法。
- 服务端实际未部署及开关未核验时，**不能声称 iOS 已实现正式营业云端录入**。
