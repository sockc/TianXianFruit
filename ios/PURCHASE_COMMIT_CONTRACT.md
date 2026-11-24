# iOS 正式采购事务写入接口契约（V0.4.3）

**状态：接口设计、客户端规范化事务提案及计划版本检查已提交；V1.0.25 服务端独立源码升级包已生成，但尚未部署到现网；默认功能关闭。** 这不是服务器已部署说明。当前仓库不包含生产同步服务的完整后端源码。iOS 的 `PurchaseCloudReconcilePolicy` 只做只读云端对账，不会发送采购变更。

## 为什么不能直接调用旧 `/api/v1/sync/push`

安卓“完成采购”不仅更新 `purchase_order` 和 `purchase_item`，还会在同一个操作中维护 `purchase_plan_item`、`purchase_collaboration`、`purchase_activity` 等关联及同日期利润/资金结算派生记录。多个单行 `push` 即使收到 ACK，也不能证明这些修改是一个事务：网络中断可能留下订单而无商品明细，或完成计划但未生成正式订单。客户端回读只能发现不一致，**无法保证不会发生**。

## 拟增加的服务端事务语义

建议新增独立的 `POST /api/v1/purchases/commit`（最终名称需与服务端实际 API 对齐），服务器必须先验证账号当前所在账本、采购创建权限和目标账本归属，不能仅信任客户端发来的 `book_id` 或 OWNER/EDITOR 角色。每笔创建/完成请求包含：

- `book_id`：当前授权账本，不得跨账本修改。
- `operation_id`：由设备生成且与账号、账本和采购草稿永久绑定的不可变 UUID（服务器唯一约束 `book_id + operation_id`）。
- `payload_hash`：规范化业务内容指纹；同 `operation_id` 不同内容必须返回明确冲突。
- `expected_versions`：编辑/完成计划时父计划、计划明细和协作记录的行版本。服务器使用事务级 CAS 检查，不可由客户端决定版本是否足够新。
- `purchase`：采购日期、类型、采购人稳定 sync_id、备注，以及每条商品的稳定 sync_id、数量、真实单价、行总价、单位、每件重量；整单总金额由服务端再次精确到分核对。
- `linked_plan_item_sync_ids`：仅当完成已有计划时提供，完整标记已完成；独立临时采购不能凭名称臆造计划关联。

服务器返回 `committed`、`already_committed`、`version_conflict`、`permission_denied` 或 `rejected` 之一，并附上完整订单、商品明细、计划关联的 sync_id 与新行版本。**网络超时后的查询通过 `GET /api/v1/purchases/operations/{operation_id}` 只读确认**，不能盲目发起第二次创建。

## 不可妥协的事务边界

在同一个数据库事务内完成：权限验证及账本锁定 → 幂等键查询/占用 → 版本校验 → 采购主单/商品明细写入 → 计划/协作完成状态与实际金额更新（如有关联） → Android 资金结算派生数据失效或原有事务规则同等重算 → 变更同步日志写入 → 提交成功。任一步失败必须完整回滚。禁止成功应答后再异步补齐商品或结算。

- **安全重试**：同一幂等键+同一指纹重复请求只返回已存在的事务结果，不能再创建新订单；不同指纹冲突。
- **删除/恢复/编辑**：各自独立操作类型与版本 CAS，必须按安卓原有计划、订单及结算清理规则一起事务性修改，不可对单个行直接 DELETE。
- **采购金额口径**：采购订单的整单金额不能充当某件商品成本，非零单价记忆不能被零值覆盖；计划金额与已完成实际金额分开统计。
- **冲突界面**：只展示“云端已有完全相同订单/同日可能重复/无法核对/最新数据未发现”四种只读结果；没有服务端事务接口前，iOS 不出现假成功，也不把本机草稿算入经营利润。

## 上线门槛

1. 在真实同步后端实现以上原子操作、账本权限与数据库唯一约束；
2. 用后端自动回归覆盖两设备同时提交、订单写到一半断网、重复 `operation_id`、修改已结算日采购和跨账本越权；
3. iOS 接入一次性事务请求并持久化不可变 outbox，再使用完整 `/sync/pull` 核对全部主子行；
4. 最后才允许在正式采购页展示“已完成并云同步”以及云端采购编辑/删除。

**安卓现有当日结算计算逻辑、字段、UI、转账规则均不应因这个接口而改变。**

## V0.4.3 客户端现有对接代码

- `PurchaseCommitPlanningPolicy.prepare` 可从真实账号的本机采购草稿与当前云端快照，生成含 `protocol_version=1`、`book_id`、`draft_id`、`buyer_sync_id`、`date`、`purchase_type`、`operation`、每条商品精确到分金额、`expected_versions` 与 `payload_hash` 的规范化请求提案。
- 来源为当天待采购计划时需验证计划仍待完成、商品/单位匹配、原计划和协作记录身份唯一且版本有效；只创建独立采购时不伪造计划关联。混合有/无计划来源必须拆单。
- 原计划商品的 `sourcePlanItemSyncID` 从安卓同步计划导入并保存到本机草稿（旧版 JSON 缺失字段仍可解码），修改商品或单位后会清除来源，防止把错误商品误标为已完成。
- V0.4.3 已新增在服务端明确开启功能且版本匹配时的专用 POST /api/v1/purchases/commit，请求前将不可变提案保存到本机操作表；网络状态未知时仅查询原 operation_id，绝不自动重复 POST。新接口的部署、权限、强制幂等、实际数据库事务及异常回读仍是阻塞条件；绝对不得将客户端 `payload_hash` 视为服务器事务证明。

## V0.4.3 服务端实现状态

独立交付的 TianXian-Server V1.0.25 FULL/PATCH（基于资料库 V1.0.24）已包含：

- GET /api/v1/purchases/capabilities（原子写入能力明示）
- POST /api/v1/purchases/commit（采购主单、商品明细、采购活动、可选计划/协作、审计及同步事件统一 SQL 事务；账本+草稿 UUID 幂等记录）
- GET /api/v1/purchases/operations/{operation_id}（登录账号、同账本只读恢复）
- 默认 TIANXIAN_PURCHASE_COMMIT_ENABLED=false；改动未部署正式服务器。独立 SQLite/SQLAlchemy 事务合同测试通过，但生产 PostgreSQL 行锁并发与安卓实机互通仍需在正式启用前核验。

iOS 只允许服务器功能标识、版本、atomic 和 idempotency 能力全部匹配后发送显式确认的请求。收到回执后必须云端 full pull 核对订单主单及全部明细，否则保持待核对状态。Android 当日结算算法、转账及 UI 不变。
