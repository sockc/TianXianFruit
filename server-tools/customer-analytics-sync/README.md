## V1.4.7.108 新增可选协议事件（服务端待实现）

APK 仅在 `capabilities.features` 明确包含对应能力后上传；未支持时不会上传新事件，旧收款交易仍可同步，但新更正状态保持本机并在客户分析提示。

- `receipt_analysis_state`：`ANALYSIS`，实体键 `platform|dedupe_key`；payload 包含 `platform`、`dedupe_key`、`state`（`DEFAULT`/`EXCLUDED`/`INCLUDED`）。必须用独立修订序号与 CAS 实现多设备编辑冲突，不得覆盖原始交易。
- `analysis_settings`：`SETTINGS`，键 `global`，字段 `exclude_large_payments`（bool）、`large_payment_threshold`（元，正数）。数据与当前账本隔离，变更需 CAS。恢复默认设置也是一笔可同步变更。
- `bill_source_links`：`SOURCE_LINK`，键 `platform|dedupe_key|file_hash`，字段 `platform`、`dedupe_key`、`file_hash`。同一交易可链接多个来源文件；服务端唯一约束为 `(book_id,platform,dedupe_key,file_hash)`，不得重复计客流。服务端应确保引用的批次和交易存在，并按事件 cursor 可增量恢复。

服务端应以认证账本 ID 作为所有实体作用域，使用原有 `/customer-analytics/push` 与 `/changes` 的事务性确认格式；不接受客户端自报权限。必须覆盖先下载后上传、本地并发编辑、409 冲突、游标回放与跨设备恢复测试。未获得运行中服务器源码前不要把本协议说明当作已经部署的接口。

---

# 天鲜账本 · 客流/账单云同步的服务器对接规范（待接入现有服务）

> **不是可直接部署到生产服务器的插件。** 当前连接的 GitHub 仓库
> `sockc/TianXianFruit` 只含 Android APP 和服务器管理脚本，不含
> 运行中的 TianXian-Server 源代码。未经验证认证、权限、事务和数据表时，
> APP 不能把账单记录标成「云同步成功」。

## 目标及权限

账单交易、导入批次、人工排除状态、微信经营码官方每日新/老客汇总和
用户创建的客户分析更正必须随同一账本恢复到不同设备。

- 服务器以 **已登录用户 + 账本权限** 为唯一授权来源，不接受客户端传来的
  is_admin、book_owner 等自报权限。至少 STATS_VIEW 允许读，
  BASIC_EDIT（或更细粒度 BILL_IMPORT_EDIT）允许写；未授权返回 403。
- 所有 SQL 查询必须强制添加服务端解析的 book_id 过滤；
  不得以 Android 本地记录 id、store_id 作为跨设备主键。
- 为每账本生成独立存储空间，服务端及导出备份须包含这些表。
  原始导出文件不需要上传；上传最小化结构化交易信息。
- 不得自动生成顾客身份：微信脱敏名称、交易时间、单笔金额均不是稳定付款人标识。

## 建议持久化数据

1. `book_bill_import_batch`：主键 (book_id, platform, sha256_file)；记录
   file_name、imported_at、首次/末次 **有交易的日期**、observed_days、
   total_rows、ignored_rows 和导入设备。
2. `book_payment_transaction`：主键
   (book_id, platform, dedupe_key)；记录交易号、原始交易时间、
   原始业务日期、金额/退款/净额、业务类型和状态、来源文件摘要、
   payer_key/confidence（仅高可信才能参与身份分析）、
   **store_sync_id**，last_modified、manual_analysis_state、
   row_revision、deleted。不要用本机 store_id。
3. `book_wechat_official_daily`：主键
   (book_id, source_account_identifier, date)；手工新/老客、updated_at、
   row_revision、deleted。微信经营码账户级统计不得拆分到单个位置。
4. `book_customer_change`：不可回退的增量事件主键；
   单账本单调递增 server_cursor、table、record_key、revision、
   action、updated_at、actor_user_id、actor_device_id。

来源文件的摘要必须按账本隔离；外部平台同订单如有多条退款，
应保留各自有出处的交易/退款记录，不以相同金额时间粗暴合并。

## 建议 API（服务端版本 1）

- `GET /api/v1/books/{book_id}/customer-analytics/capabilities`：
  显式返回 `{"version":1,"supported":true,"features":["receipt_pull","receipt_push","official_daily"]}`。
  404/403 不是成功；老服务器无此接口时客户端继续本机运行，显示「本机未同步」。
- `POST /api/v1/books/{book_id}/customer-analytics/receipts:batchUpsert`：
  Bearer 用户认证，单次 200～500 条，幂等；响应逐条 accepted/unchanged/conflict，
  **事务提交后**才准客户端标记已上传。
- `GET /api/v1/books/{book_id}/customer-analytics/changes?cursor=N&limit=300`：
  分页、按递增 server_cursor；客户端确认本地事务落盘后才推进游标。
- `POST /api/v1/books/{book_id}/customer-analytics/official:upsert`：
  需带 last_seen_revision 做 CAS；冲突响应 409，不能静默丢失另一设备手工编辑。
- 服务器在返回成功时提供 cursor、revision 和 authoritative receipt digest；
  同步界面的微信/支付宝已同步笔数应该基于服务器确认，而不是文件导入成功数量。

## 数据合并规则与失败路径

- A 手机上传 1～14 日，B 手机只有 1～7 日：B 接收缺少的日期，
  绝对不得使用「本地全量覆盖云端」。
- 同文件在两台手机导入：服务端按账本和稳定账单交易键去重，不重复计客流。
- 一天两个摊位同时营业：没有可靠 store_sync_id 的账单留「未匹配」，
  绝不凭店名、位置顺序或同日唯一假设强行归属。
- 分别追踪微信、支付宝同步状态、最后成功时间与错误原因；
  不允许一个渠道成功就标全部成功。
- 网络断开、401 令牌过期、403 权限收紧、409 冲突、
  429 限流、500 服务器报错时保留本地及待传数据，指数退避。
- 旧账本恢复顺序：账本与营业位置、账单导入批次、交易、
  官方账户级汇总、客户端分析缓存重算。导入与同步不触碰正式营业额、
  采购、库存、利润及结算。
- 服务端数据库备份与灾难恢复测试必须涵盖账单与客户汇总数据；
  用脱敏测试样本做跨设备恢复、重复导入、时区和跨午夜回归。

## 当前实现范围（V1.4.7.100）

APP 已完成本机微信/支付宝各自最早/最晚收款日期、
有交易的日期数、按月逐日收款笔数和每次导入文件日期范围（含旧文件回溯）。
这些日期均是**真实观察到的经营收款日期**，不是官方导出范围，也不保证空白日的账单完整。

**尚未完成**：运行中服务器 schema/API、客户端增量上传下载、
服务端授权和冲突处理、官方新老客的跨设备同步。
因此所有账单仍标识「本机未同步」，不显示误导性的「已同步 ✓」。

正式开发须取得现有 TianXian-Server 源代码，接入现有 JWT、
账本权限、数据库迁移和同步事务框架后联调，不建议部署另起的匿名同步服务。
