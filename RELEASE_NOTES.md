## 天鲜账本 V1.3.6

### 新增
- APP 启动后每 12 小时后台检查一次 GitHub 最新 Release。
- 检测到新版本时弹窗显示当前版本、最新版本和更新说明。
- 点击“前往 GitHub 下载”直接打开官方 GitHub Release 页面。
- “更多”页面新增手动“检查更新”。
- GitHub Actions 支持自动编译、签名并上传 Release APK。

### GitHub 发布
- 推送 `v1.3.6` Tag 会自动发布 Release。
- 或在 Actions 中手动运行 **Build and Release APK**，勾选“同时发布 GitHub Release”；工作流会自动创建对应版本 Tag。

### 保留
- V1.3.5 SUPERADMIN、云端账本回收站、设备管理与强制退出。
- V1.3.4 首页快速切账本和同设备多账号。
- V1.3.3 自动同步与冲突处理。
