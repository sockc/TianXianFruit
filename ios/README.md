# 天鲜账本 iOS V0.8.5 · Build 54 — 小屏幕首页与 App 图标

在 Android↔iOS 测试账本的营业、采购双向修改与同步已由使用者实机核对一致后，继续优化 iPhone X 日常使用体验。**本次客户端更改不修改后端 V1.0.32、Android 当日资金结算算法或生产白名单。**

## 首页与状态栏
- 修复根视图保留系统 NavigationView 导航栏而又绘制自定义账本栏造成的顶部长留白：隐藏根级系统导航栏；顶部安全区、内容区统一动态系统背景。
- 修复旧版 `TXTheme.header` 固定浅绿、深色模式下发白；背景适应浅/深色外观。
- 账本栏由 43pt 缩至 36pt，测试账本小徽签不再单独占行；标题、日常操作卡片、首页间距适当缩小。
- **不侵入系统状态栏/刘海区域**；安全区仍由 iOS 控制。

## iOS App 图标
- 新增原创「水果 + 记账清单」绿色图标，使用 AppKit 生成 1024px 主版以及 iPhone/iPad 多尺寸 AppIcon。
- `ios/Assets.xcassets/AppIcon.appiconset/Contents.json` 配置所有图标槽位；iOS target 使用 `ASSETCATALOG_COMPILER_APPICON_NAME=AppIcon`。
- GitHub Actions 构建在 XcodeGen 之前运行 `swift ios/scripts/generate_app_icon.swift`，确保 IPA 包内存在真实 PNG 图标，不依赖开发者手动拷贝文件。

## 营业与采购
- 营业日期、位置和开收摊时间直接紧邻展示，去掉额外时间展开卡；保持实际 HH:mm 必填、安全跨午夜和原同步校验。
- 单收款人不显示重复的逐人总计卡；多人模式仍可逐人输入微信、支付宝、现金及小计。
- 采购录入顶部将采购人/采购类型并排，简化提示和间距，保留多商品连续录入、真实单价和采购草稿保护。
- 商品和单位切换时防止沿用旧单价；历史成本只提供需用户主动填入的建议，不能自动覆盖真实值。

## 结算与安全
- 结算标题卡紧凑化；继续只读显示已保存的日结、利润分配、资金轧差和转账记录。
- **不改变任何 Android 结算逻辑、转账方案、资金余额计算。**
- 关闭正式账本写入白名单的服务端状态不变；本版不自动开通正式结算写入。

## 构建与安装
- 目标：`TianXianFruit-iOS-v0.8.5-Build54-TrollStore.ipa`，iPhone X/iOS 16.6 可通过 TrollStore **覆盖安装**；切勿卸载原 App 以免丢失未同步草稿。
- 验收：macOS Swift 图标生成检查、模拟器编译与 XCTest、无证书设备 IPA 构建与 AppIcon 资源核验。
