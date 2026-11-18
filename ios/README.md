# 天鲜账本 iOS V0.2.1 · 隔离测试采购同步

适用 **iPhone X / iOS 16.6 / TrollStore**，最低 iOS 15。iOS bundle ID 不变，build 6。

## 本版范围

- **正式服务器 `https://sync.830888.xyz` 仍只能读取数据。** 原 `CloudAPI.swift` 无业务修改 API。iOS 仅在`更多 → 隔离测试采购同步`中由人工确认，才使用新的独立 `StagingPurchaseAPI` 发送采购订单及明细。
- 保留 V0.2.0 本机独立采购、营业草稿。营业、凌晨收款、资金结算、客户账单以及正式云端任何业务表**仍不允许写入**。
- 使用另行输入的测试 HTTPS URL 和**独立测试账号密码**登录；不使用、复制或传递生产账号的 Keychain token/password。测试登录状态只在本页内存中保存。
- 测试服务器必须先返回 `GET /api/v1/ios-test/attestation` 证明，与生产数据库隔离，逐账本 allowlist 启用 iOS 采购写入。无此接口、返回错误、不支持 staging 或 ID 不匹配时 fail-closed。**仅把正式账本改名 `iOS_TEST_` 不会获得上传权限。**
- 对应云端测试账本名称必须以 `iOS_TEST_` 开头，`OWNER/SUPERADMIN`；测试服务端账本 **ID、名称** 必须与草稿来源测试账本一致。这通常要求通过测试环境完整恢复/克隆测试账本，不能仅创建一个同名但不同 ID 的新账本。
- 首先从测试服务端 **GET** 正式同步记录，按稳定 `sync_id` 确认真实采购人和商品及其测试环境数字 ID。测试快照中找不到的引用绝不强行上传。
- 在 SQLite3 **单独持久化每笔上传意图**（地址、实例 ID、账号、账本 ID、生成的订单/明细 `sync_id`、数值 ID、冻结 payload、状态）。不可把同一草稿换成另一个服务端重新推送。
- 一笔采购仅包含 `purchase_order` 和 `purchase_item`。先 `POST /api/v1/sync/push` 一个订单，验证 `accepted/conflicts`，再从测试环境 `sync/pull` 完整回读；确认订单后才上传明细并回读。所有出网状态**先落盘再请求**。
- 请求超时/冲突时不自动重复上传。进入`结果不确定`，通过按钮人工回读同一服务端的同一 `sync_id`；仅在服务端确认订单后继续发送缺少的明细。原本机草稿始终保留。
- 测试未验证：**Android 端端到端回读、业务利润与结算一致性、服务端父子记录 ID remap 在多种碰撞场景下的稳定性。** 本版只提供隔离测试的可控写入链路，不声称已在你的测试服务器实际上传成功。

## 测试服务器必须先部署

本仓库只有 Android/iOS 客户端源码，**没有可审查的正式服务端源代码，也没有已配置并通过隔离证明的在线测试服务器**。因此本提交不能部署服务端或声称已完成真实跨设备验证。

部署人员需在**非生产数据库和非生产服务实例**创建隔离的 HTTPS 测试环境，独立账户、数据、token signing keys、对象存储和备份。Android 测试客户端也必须连接相同测试环境；Android 正式客户端继续只访问生产环境。

新测试环境提供公开的：

`GET https://<独立测试域名>/api/v1/ios-test/attestation`

样本见 `ios/staging-contract/attestation.example.json`。只有当环境实际隔离且其测试库允许该账本写入时，才应返回：
- `deployment_scope = isolated_test`
- `isolated_from_production = true`
- `ios_purchase_write_enabled = true`
- `instance_id` 为稳定唯一实例标识（不要与生产共用）
- `allowed_book_ids` 仅含此批次明确准许写入的测试账本 ID

该接口**应由独立服务环境配置和实际数据绑定保证**，不能在生产服务里硬编码这份示例 JSON，不能让它透传、代理或访问生产数据库。由测试服务端负责实施**服务端** allowlist、独立账号和权限校验、业务类型限制。客户端证明校验只是第二层保护。

测试服务器还需兼容现行协议：
- `POST /api/v1/auth/login`、`GET /api/v1/auth/me`、`POST /api/v1/devices/register`、`GET /api/v1/books`。
- `GET /api/v1/sync/pull?book_id=...&since=0&limit=200`：包含版本号、稳定 sync ID 与原始记录。
- `POST /api/v1/sync/push`：Android 的 `book_id/device_id/changes` JSON，返回 `accepted` 和 `conflicts`。
- 只有`purchase_order`/`purchase_item` 的 `UPSERT` 在白名单中允许。服务器应拒绝任何非测试账本写入，跨设备 ID 映射要按 Android 逻辑核查。

## iPhone 测试流程

1. **先备份** V0.2.0 已录入的本机草稿；不要卸载旧版。通过 TrollStore **覆盖安装** V0.2.1。
2. 在隔离服务器准备`iOS_TEST_` 名称、与草稿完全一致 ID 的独立账本，同步相同`sync_id` 的测试采购人/商品。
3. 在 iPhone 中切换到该账本，进入`更多 → 隔离测试采购同步`，填入独立测试 URL、测试账号密码、完整账本 ID，主动确认隔离。
4. 服务器无 attestation 或返回不符时，**应当看到拒绝提示**，不会触碰生产账本，这是预期的安全结果。
5. 若隔离证明成功，先点击某笔草稿“核验并准备”，检查冻结的测试数据，再独立确认上传。
6. 登录**连接同一隔离服务器**的 Android 测试端，核对采购单、采购人、商品、数量、总价、单位、`order_id` 映射、云端原始记录，以及是否影响利润分配。出现差异就停止下一笔写入。
7. 模拟断网、延时、重复点击和部分接受。测试 iOS 应保留本机草稿与`结果待核对`状态，并禁止自动重传。
8. 完成后再次查看正式 Android 账本，金额和凌晨收款/当日结算必须完全不变。

## 构建与版本

GitHub Actions → `Build TrollStore IPA (no Apple certificate)` → Artifacts → `TianXianFruit-iOS-v0.2.1-TrollStore`，解压 ZIP，用 TrollStore 安装。

iOS 仅在独立开发分支 PR #63，未合并 main；**不改 Android 当日结算 UI/逻辑、生产服务器或现有 APK 发布工作流。**

## 目前限制

- 如果你还没有独立 HTTPS 服务器、对应测试库和 attestation 接口，**无法进行真实采购上传**。这不是客户端错误，也不能绕过安全校验直写生产环境。
- 临时无法建立 staging 服务时，V0.2.0 本机草稿仍可继续录入、离线保存。V0.2.1 仅在隔离测试环境配置完成后才可发送。
- 这版只验证父子两记录的跨设备回读；没有完整 purchase_activity、批量采购、库存结转、同步后利润/结算重算或真实营业写入。以上需要基于测试服务端结果单独验证。
- 文件包含业务金额，不上传至公开 Releases 或分享测试账本内的私人数据。
