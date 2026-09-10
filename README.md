# 天鲜果业经营助手 V1.1.0

包名：`com.tianxian.fruit`
数据库版本：2

## V1.1 主要变化

- 进货改为“进货单 + 多条商品明细”，一次可批量加入多个水果。
- 每张进货单记录：日期、进货合伙人、供货摊位、商品明细、总金额。
- 进货历史支持删除（软删除，保留未来云同步所需状态）。
- 摆摊位置支持删除，删除位置不会破坏历史记录。
- 新增合伙人表，当前利润规则按 4 位合伙人设计。
- 同一天支持多个摊位分别录入营业数据。
- 微信 / 支付宝 / 现金可分别指定实际收款合伙人。
- “每日总账结算”独立汇总：各摊营业额、各自进货、各自实际收款、总费用、总利润。
- “利润分配”使用独立数据库表和独立页面，不与营业/收款记录混在一起。
- 当前利润规则：1 人拿总利润 33%；剩余 67% 给另外 3 人按 `1:2:2` 分配。
- 历史页面支持删除进货单和营业记录。
- 统计页保留营业额 / 利润 / 客户排行榜和水果历史进价。
- JSON 备份增加 partner、purchase_order、purchase_item、store_daily_record、profit_distribution。
- 保留 sync_id / sync_status / updated_at，为后续云端同步预留。

## 从 V1.0 升级

数据库版本从 1 升到 2，应用会尝试迁移旧进货记录和旧营业记录，不主动删除 V1 数据。
包名保持 `com.tianxian.fruit`；只要继续使用原固定签名，可直接覆盖安装。

## GitHub Actions

仍使用 3 个 Repository Secrets：

- `SIGNING_KEY_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`

workflow 会生成：`TianXianFruit-v1.1.0.apk`

> 固定签名 keystore 不要上传到公开仓库。
