# 天鲜账本 iOS V0.1.1 · 独立只读测试版

支持 iOS 15+（以 iPhone X / iOS 16.6 / TrollStore 作为首要真机验收设备）。

## 与 Android 的隔离

- 开发分支 `feature/ios-readonly-v0.1.0`（后续可以重命名）；**不修改 Android 源码、当日结算、现有 APK 工作流、正式服务端**。
- 当前 `ios/` 仅是云端事件只读镜像。**不包含** `/api/v1/sync/push`、资金结算、凌晨金额修改、采购/营业新增、客户账单写入等业务 mutation。
- iOS `POST /api/v1/auth/login` 和 `POST /api/v1/devices/register` 只用于登录与设备登记，使用 `platform=ios`，不修改正式业务账本。

## V0.1.1 已写的功能

- 本地 SQLite3 只读事件缓存：按 **云账号+账本** 隔离，使用 iOS Application Support 和数据保护；保存正式同步事件、行版本、删除墓碑和服务端游标。
- 首次云端读取 `GET /api/v1/sync/pull?since=0&limit=200`；之后从本地持久化 cursor **增量下载**。只有所有分页完成并且本地 SQL 事务成功，才替换内存视图/提交游标；中途断网不清旧缓存。
- APP 冷启动时验证原登录账号后恢复本地数据；网络故障时（不是 401/403）使用**上次已获授权**的离线账本数据并标注缓存授权；账号无效会拒绝显示缓存。退出登录删除当前账号的 Keychain 会话和本地缓存。
- 首页显示今日、近7日、近30日云端已存营业额，今日正式利润，7天分日概览。日期按经营所在 `Asia/Shanghai` 时区。
- 营业/采购全部历史：近7天、近30天、全部日期切换，按位置或采购人筛选，按经营日分组，同日多位置逐条展示及同日合计；使用懒加载滚动。
- 库存只读：`inventory_snapshot` 的**最近一次实际盘点日期**及剩余/损耗数量，支持历史查看与商品搜索。未盘点不视为零库存，不跨日推算或合并单位。
- “更多”包含增量同步和手动完整核对功能。完整核对直至成功才原子替换本地事件，不在网络故障时覆盖旧数据。
- 账户 Keychain 仅保存 token、独立 device ID、已验证的云账本元信息；不保存密码。

## 数据定义与限制

直接引用 Android 正式同步表 `store_daily_record` 的 `revenue/profit`，`purchase_order` 的 `total_cost`，`inventory_snapshot` 的 `remaining_quantity/loss_quantity`。不调用 Android 当日结算代码，也不对利润、库存成本进行另一套计算。

⚠️ 目前不包含完整的 FastAPI/TianXian-Server 源码。iOS V0.1.0 已由用户在 TrollStore 真机安装并读取成功，但 V0.1.1 的离线/增量/库存功能仍须使用**测试账本**与 Android 对照：同步总额、记录数、删除重传、游标回放、换账本、断网重试。任何差异都不能认定是服务器真实利润变化。

数据安全边界：
- 应用退出后本地缓存只恢复**最后一次已经同步成功**的服务器数据，断网时可能滞后；有明确时间标记。
- 未主动执行完整同步时，若服务器备份导致 cursor 回退，旧本地 cursor 可能不能发现回退，请选择“重新完整核对”。未来需要服务端 server_identity 来自动识别。
- 大账本的首次全量拉取仍可能较耗时；本版通过增量优化重复打开与后续同步，尚未实现分页落盘增量索引。
- iOS 本地没有客户账单明细同步，不能从营业总额推算微信/支付宝客流。
- 若登录授权已被撤销但设备离线，上一次授权过的本地缓存仍可只读查看，联网验证后会清除会话；这不是实时权限授权。
- 取消账号会删除缓存（服务端账本不会受到影响）。

## 测试及构建

检查：`.github/workflows/check-ios.yml`（模拟器编译和 Swift unit tests）、`ios/scripts/check_readonly.py`（只读 API 保护）。
巨魔构建：`.github/workflows/build-trollstore-ipa.yml`（无开发证书，macOS 真机 arm64 构建、`ldid -S`、打包 IPA，上传 Actions Artifacts）。当前不上传公开 GitHub Releases；测试包在对应成功工作流运行的 Artifacts 里取得。

在 GitHub Actions 找到 `Build TrollStore IPA (no Apple certificate)`，展开最新**绿色成功**的任务，在 Artifacts 下载 `TianXianFruit-iOS-v0.1.1-TrollStore` ZIP，解压得到同名 IPA，在 TrollStore 里安装。只支持 TrollStore 可运行的 iOS 设备（iOS 15～16.6.1、16.7 RC、17.0，具体以 TrollStore 官方兼容要求为准）。

保留 `.github/workflows/build-ipa.yml` 作为未来的 Apple Ad Hoc 签名选项，它在公开仓库会拒绝生成带家庭签名信息的公开工件；无需准备证书即可构建巨魔 IPA。

`ios/project.yml` XcodeGen，最低系统 iOS 15，`MARKETING_VERSION=0.1.1`。推荐先将已安装的 V0.1.0 覆盖升级并检查账号和缓存，切勿卸载旧版后立刻测试升级保留行为。

## 版本号与发布

iOS 独立版本序列 `0.1.1`，不变更 Android `V1.4.7.129`。现有 Android `main` 推送会触发 APK 发布工作流：合并 iOS PR 前需要增加 iOS-only 路径过滤或另行校验，当前不合并 `main`。
