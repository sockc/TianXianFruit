# 天鲜果业经营助手 V1.0.0

Android 包名：`com.tianxian.fruit`

## V1 已可用功能

- 首页：今日营业额、利润、客户、进货、当前摊位
- 固定水果库，并支持自定义水果
- 进货：按斤/筐/箱/件，自动计算单价
- 历史水果进价查询
- 摊位/位置保存，可重复选择
- 每日营业：微信、支付宝、现金、费用、库存估值、新客、老客
- 自动利润：营业额 + 收摊库存 - 开摊库存 - 当日进货 - 日常费用
- 历史营业/进货记录
- 按摆摊位置统计营业额、利润、客户排行榜
- JSON 数据备份导出
- SQLite 本地数据库
- 为后期云同步预留 sync_id / sync_status / updated_at

## GitHub Actions

仓库必须配置 4 个 Actions Secrets：

- `SIGNING_KEY_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

配置后打开 Actions -> Build Signed APK -> Run workflow。

构建产物：`TianXianFruit-v1.0.0.apk`

> 不要把 `.jks` 签名文件或签名密码提交到公开仓库。
