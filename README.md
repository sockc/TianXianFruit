# 天鲜账本 V1.2.0 Phase 3

本版真正接入采购计划 UI，并修复系统状态栏显示。

## 本阶段完成

### 首页采购
- 首页快捷操作增加“采购计划”
- 首页新增“明日采购”卡片
- 显示：
  - 暂无计划
  - X 种水果待采购
  - 已采购
  - 已取消
- 点击直接进入采购计划页面

### 采购计划
- 默认日期：明天
- 自主选择水果
- 填写数量
- 单位支持：斤 / 筐 / 箱 / 件
- 单项备注
- 整单备注
- 支持编辑、移除、保存
- 状态：待采购 / 已采购 / 已取消
- 支持删除采购计划
- 最近采购计划可回看
- 采购计划不参与营业额、进货成本、利润计算

### 水果商品管理
- 更多 → 水果商品管理
- 新增
- 编辑名称
- 编辑默认单位
- 删除=停用
- 恢复
- 停用商品不再出现在新采购/新进货列表
- 历史进货、历史价格、历史采购仍保留原名称

### 系统状态栏
- MainActivity 强制 setDecorFitsSystemWindows(true)
- 状态栏背景为白色
- 状态栏图标使用深色
- Compose Scaffold 显式使用 safeDrawing insets

### 首页 Header
仍保持“天鲜果业”，未改为“天鲜账本”。

## 数据库
DB_VERSION: 5

新增：
- purchase_plan
- purchase_plan_item

JSON 备份已包含采购计划表。

## APK 版本
- versionCode: 11
- versionName: 1.2.0

## 签名
继续使用原来的 GitHub Secrets 和 keystore：
- SIGNING_KEY_BASE64
- KEYSTORE_PASSWORD
- KEY_ALIAS

不要更换签名。
