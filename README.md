# 天鲜账本

用于水果经营的 Android 账本：采购、库存盘点、营业收款、合伙人资金结算、统计、天气和云同步。

当前 Git 分支待发布候选版本：**V1.4.7.134**，versionCode **172**（以 `app/build.gradle` 为准）。目前最近正式发布版本仍为 **V1.4.7.133**，versionCode **171**；正式 APK 必须经自动测试、签名验证和 GitHub Release 发布；跨设备并发冲突仍需持续监测。

## 经营口径

- 实际利润和资金结算使用正式营业/采购账目。
- 预估经营利润使用结转库存、采购、剩余库存及损耗成本；缺失成本必须明确标记。
- 库存单位保留箱/筐/件，允许小数；每件重量用于辅助分析。
- 单品零价格不表示零成本，读取最近有效采购价；整单总价不能分摊为单品价格。
- 批量利润结算的正负利润抵扣口径必须以已确认的正式业务规则及回归测试为准；不能仅凭文档修改结算算法。

## 构建

JDK 17、Gradle 8.13、Android SDK 36。签名密钥不保存在仓库。

```bash
python3 -m unittest discover -s tests -v
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

正式发布工作流使用 GitHub Secrets `SIGNING_KEY_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`，构建并验证签名后发布 APK。

## 检查

凌晨收款审计采用只读检查，不自动改写正式营业额、利润分配或已结算资金。离线多设备冲突仍需要同步后的人工核对，不能把提示机制等同于服务器端强制互斥。


PR 执行业务边界单元测试、SQLite 同步回归测试、Android Lint 和 Debug 构建。正式 Release 发布前再次执行业务检查。

- Kotlin 测试：`app/src/test/java/com/tianxian/fruit/data/StabilityPoliciesTest.kt`
- SQLite 测试：`tests/test_sync_sql.py`
- 发布说明：[RELEASE_NOTES.md](RELEASE_NOTES.md)
- 服务器兼容：[SERVER_UPGRADE_REQUIRED.txt](SERVER_UPGRADE_REQUIRED.txt)
- 服务器备份管理器：[server-tools/backup-manager](server-tools/backup-manager)

## 代码范围

此仓库包含 Android 客户端和 TXB 服务器管理脚本，不包含完整同步服务器及 Web 客户端。服务器脚本复用现有 TXB 工具链，不改变备份格式。
