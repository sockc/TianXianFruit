# 天鲜账本 iOS V0.1.3 — 天气、报表、长列表优化（只读）

适用 **iOS 15+**，TrollStore 优先验证 iPhone X / iOS 16.6。此项目与 Android `app/` 完全独立。当前版本不支持新增/修改/删除营业、采购、凌晨收款、资金结算或客户交易。

## V0.1.3 新增

1. **未来 7 天经营天气**：读取 Android 已使用的云端 `GET /api/v1/weather/overview`，按营业位置的已同步 `store` 经纬度获取预报；位置未配置坐标时不给出虚构天气。雨天判断采用 Android `WeatherRainRisk` 阈值（降水 >0.05mm、降水概率 ≥60%、雨天文字/天气代码）；明显降温使用相邻两天**最高气温下降 ≥5°C**。预报短于 7 天就标明实际天数，不推断具体营业小时的降雨。
2. **本机可选天气通知**：用户在“更多 → 经营天气”中主动授权后，可根据**当前预报**安排风险当天广州/上海时区 15:00 的设备本地通知。默认关闭，退出账号清除待通知。本功能没有后台持续天气拉取：App 不运行时天气改变，已安排的通知**不会实时调整**。
3. **经营报表导出**：在“更多 → 导出经营报表”按最近 7/30/90 天、位置筛选，以 iOS `UIGraphicsPDFRenderer` 导出多页 PDF，`UIGraphicsImageRenderer` 导出有长度保护的 PNG。绿色“天鲜果业”页头、日期/每日位置营业明细/每天合计、采购金额、已存利润、右下角生成时间。通过系统分享菜单保存，不上传文件。**采购金额只按采购日期计入全账本，不伪造某摊位的采购分摊**；利润直接读取已保存值，绝不重新计算当日结算。
4. **页面性能**：营业和采购历史按 14 个日期分段加载；库存快照每次载入 80 条；资金分配与转账列表每批 60 条。历史仍能通过“加载更多”查看，避免首次构建大量卡片。
5. **测试**：天气 JSON 字段、概率阈值、相邻日降温和缺失天数、位置经纬度校验、同日多摊位经营报表与全账本采购金额、PDF/PNG 实际生成和多页/图片长度限制的 Swift 回归用例。

## V0.1.3 保留的只读能力

- 登录与设备登记：`POST /api/v1/auth/login`、`POST /api/v1/devices/register`，不触碰业务账本；读取 `GET /api/v1/books`、`GET /api/v1/sync/pull`。
- 云端变更及删除墓碑保存在按 **账户 + 账本** 隔离的 SQLite3 缓存，完整事务成功后才更新游标；支持离线、增量和手动完整核对。
- 首页当日/近 7/30 日营业额、利润；营业和采购按日期、位置/采购人筛选；盘点快照库存只读。
- 客户分析通过独立 `customer-analytics/capabilities` 和 `/changes` **GET** API 读取微信、支付宝真实交易；按稳定位置 ID 筛选，未匹配不分摊；逐小时柱状图、同位置同星期历史参考。离线缓存及排除设置保留。
- 资金只读查看 `profit_distribution`、`settlement_transfer` 原始分配和已登记金额；不得操作结算、不得当成真实到账。

## 构建 / 下载 TrollStore IPA（无开发者证书）

GitHub Actions → `Build TrollStore IPA (no Apple certificate)` → 最新**成功**任务 → Artifacts → `TianXianFruit-iOS-v0.1.3-TrollStore`。解压得到 IPA，TrollStore 覆盖安装；升级测试不要先卸载旧版。

- 工作流 `.github/workflows/build-trollstore-ipa.yml`，XcodeGen + Xcode arm64 真机构建、`ldid -S` 伪签名。
- 模拟器测试 `.github/workflows/check-ios.yml`，静态安全检查 `ios/scripts/check_readonly.py`。
- `.github/workflows/build-ipa.yml` 仅作为私有仓库 Ad Hoc 签名备用，不需要 Apple 证书也能运行 TrollStore 工作流。
- App Bundle ID 不变：`com.tianxian.fruit.ios`；版本 `0.1.3`，构建号 4，最低系统 iOS 15。
- 所有产物为 GitHub Actions 工件，并非 App Store/TestFlight 或公开 Releases。

## 风险说明

- 天气来源和当前服务端 API 版本需在 iPhone 上实测；没有经纬度和网络时显示原因，不伪造“无雨”。
- 天气数据仅在内存中持有，不写入云端或 Android 业务表；没有实时后台天气监控，已安排的本机提醒可能过时。
- 报表仅代表 **最近一次已同步** 的数据；云端未完成同步或缓存过期时可能滞后。业务日期按 Asia/Shanghai，报表生成时间使用本机当前时间。
- 本地导出的 PDF/PNG 可能包含家庭财务信息，分享后由用户自行管理；不要把文件上传到公开 GitHub。
- 有效客流取决于 Android 实际导入并已同步的微信/支付宝账单，缺失日期不等于零顾客。
- 本分支为 PR #63 草稿，不合并 `main`：Android 原有工作流在 main push 时会触发 APK 发布。**不修改 Android 的结算逻辑/UI、APK 工作流和生产服务器。**

## iPhone X 验收

- 覆盖旧版后测试离线缓存及增量同步仍正常；日期和位置筛选不丢历史。
- 在位置管理已填经纬度时刷新天气，检查未来 7 天、雨天和降温阈值；拒绝通知权限时主界面仍能正常查看。
- 使用单位置和全部位置导出 PDF/PNG，比对 Android 按日营业额、采购金额和利润，检查第二页、最后一页页脚及生成时间。
- 超长图片导出正确拒绝并提示改用 PDF；营业/采购/库存/资金记录“加载更多”无重复。
