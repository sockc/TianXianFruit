# 天鲜账本 iOS V0.1.0（只读试验版）

此目录是与 Android `app/` **独立** 的 SwiftUI 工程。开发目标是家庭自用（iPhone/iPad，iOS 15+）以及 TrollStore 免开发者证书测试 IPA；Ad Hoc 方案保留备用。

## 当前完成范围

- 采用现有生产同步 API 的 **读取协议**：登录、当前用户、账本列表、`GET /api/v1/sync/pull`。
- 登录时为兼容现有设备鉴权，使用 `POST /api/v1/auth/login` 和 `POST /api/v1/devices/register`；仅写入会话/设备登记信息，**无任何业务数据写入接口**。
- `platform=ios` 和独立设备标识。令牌/设备标识存储在设备 Keychain，密码不落盘。
- 同步事件依赖服务端稳定 `sync_id`、`row_version`、`next_cursor`；只在全部页完成后替换内存视图，错误时保留此前完整结果。
- 只读显示云端正式营业记录的 `revenue`、`profit` 及采购订单的 `total_cost`；不执行利润估算、资金结算和库存重算。
- **没有**本地业务写入队列、`/sync/push`、账单导入、数据更正、凌晨收款编辑、收款删除。客户分析与库存详情等在后续只读阶段扩展。

## 未验证与风险边界

- 当前 GitHub 仓库没有运行中的 TianXian-Server 服务端源码；`platform=ios`、设备登记和权限是否在生产服务器完全兼容，**必须先使用测试账号和测试账本联调**，不能声称已验证。
- 当前版本读取快照仅驻留内存；退出进程需重新 GET 全量历史。**不要**将“只读同步成功”理解成已实现离线缓存。
- 金额为服务器记录的存储值；不计算跨日库存成本、分配、资金轧差，不对统计一致性作生产承诺。
- iOS 与 Android 共享账本前，应完成数据核对、跨位置/跨午夜冲突回归。不要在此版本加入 `POST /api/v1/sync/push`。
- iOS 工程在本仓库仅做源码、静态保护与 GitHub macOS CI 校验；尚未在实际 iPhone 上验证。

## 本机构建（Mac）

需要 Xcode（iOS 17 SDK 或更高）和 XcodeGen：

```bash
brew install xcodegen
cd ios
xcodegen generate
xcodebuild -project TianXianFruit.xcodeproj -scheme TianXianFruit \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

## Ad Hoc IPA

`.github/workflows/build-ipa.yml` 是手动触发的签名工作流，且**仅允许私有仓库**构建签名工件。当前 `sockc/TianXianFruit` 为公开仓库；不要在公开仓库中添加分发证书/描述文件/签名 Secret 并上传 IPA 工件。

需准备 Apple Developer Program、已登记设备 UDID、Apple Distribution `.p12` + 密码、Ad Hoc Provisioning Profile 和 Team ID；将这些凭据仅放进**私有仓库**的 GitHub Actions Secrets：

- `IOS_P12_BASE64`、`IOS_P12_PASSWORD`
- `IOS_PROFILE_BASE64`、`IOS_TEAM_ID`
- `IOS_KEYCHAIN_PASSWORD`

Bundle ID 暂定 `com.tianxian.fruit.ios`，必须与 Apple 后台 App ID 和描述文件完全一致；同一 iOS 版本使用独立的 `ios-v*` 发布标签，绝不沿用 Android `v*`。

## Android 隔离原则

所有功能代码只放在 `ios/`；CI 仅在 iOS 目录或 iOS 工作流变更时触发。当前分支不修改 `app/`、现有 Android 构建和服务器工具。**尚未合并到 main**：现有 `build-apk.yml` 对每次 main push 均会构建/尝试发布 APK，因此在合并 iOS-only PR 前应另行增加安全的路径过滤，并单独验证不会改变 Android 发布语义。

## 巨魔（TrollStore）测试 IPA：无 Apple 开发者证书

- 工作流：`.github/workflows/build-trollstore-ipa.yml`，在 iOS 功能分支 push 自动构建，也可在工作流进入默认分支后手动触发。
- macOS + XcodeGen 构建 `generic/platform=iOS` 真机 arm64 App，`CODE_SIGNING_ALLOWED=NO`，使用 Homebrew `ldid -S` **伪签名**，打包成 `Payload/TianXianFruit.app` 的 IPA。
- 不需要 Apple Developer Program、P12、Provisioning Profile、UDID；该 IPA 不受 Apple 标准安装路径支持，**只供已能正常运行 TrollStore 的兼容设备使用**。
- 官方兼容范围：iOS 14.0 beta 2～16.6.1、16.7 RC (20H18)、17.0；正常 iOS 16.7.x 和 iOS 17.0.1+ 不支持（基于目前官方 CoreTrust 漏洞）。**本 App 最低版本 iOS 15.0**，因此实际支持交集是 iOS 15.0～16.6.1、16.7 RC、17.0。
- 下载路径：Actions → `Build TrollStore IPA (no Apple certificate)` → 成功运行 → Artifacts → `TianXianFruit-iOS-v0.1.0-TrollStore` → 解压得到 `.ipa`。
- 手机端：先确认 TrollStore 可正常运行，在 TrollStore 设置中安装 ldid（如安装器提示），再从“文件”打开 IPA 并分享到 TrollStore 安装。
- 保持系统沙盒运行，不添加 root/越狱能力或私有特权 entitlement；此 IPA 没有任何业务写入接口。
- 只在**测试账号及测试账本**验证 iOS 平台登录与设备登记。尚未验证生产服务端接受 iOS，切勿直接认定已可登录生产账本。
- 对于不兼容 TrollStore 的设备，需使用常规免费账号短期签名或后续 Ad Hoc/TestFlight，不能通过此 IPA 绕过 iOS 签名机制。

### 注意

iOS 的 Apple 付费签名工作流仍保留在 `.github/workflows/build-ipa.yml`，但**无需配置也不会阻碍巨魔 IPA**。不要因为免签名测试而更改 Android 发布工作流或生产服务器。
