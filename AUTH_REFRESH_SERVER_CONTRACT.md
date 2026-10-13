# 天鲜账本服务端：自动续期接口兼容契约

V1.4.7.89 的 Android 已实现**可选** refresh token 客户端支持，但本仓库不包含部署中的 FastAPI/其他服务器应用源码，不能把该 APK 当作服务器自动续期已经启用的证明。

## 现有接口（必须保持兼容）

`POST /api/v1/auth/login` 接收 username、password、device_id、device_name、platform、app_version。
已存在的响应 `{"access_token":"..."}` 必须继续有效。
新增刷新凭证时可以增加 `refresh_token` 字段（可选），不得改变已有登录客户端的行为。

## 建议新增的服务端接口

`POST /api/v1/auth/refresh`

请求：`{"refresh_token":"<opaque rotating secret>"}`

成功响应：`{"access_token":"<new access token>","refresh_token":"<new rotating refresh token>"}`

- 刷新令牌必须为高熵随机值；服务端仅保存不可逆哈希，并绑定 user_id、device_id、会话 ID、签发与过期时间。
- **每次成功刷新必须轮换 refresh token**，旧令牌立即作废，重放应吊销相关会话。多设备分别持有自己的刷新凭证。
- 禁用账号、修改密码、主动退出或“退出所有设备”必须使相关 refresh token 立即失效；不得让刷新接口绕过设备吊销。
- 登录态验证拒绝时返回 401；无操作权限返回 403；刷新接口不存在的旧版服务端可返回 404/405。
- 客户端仅在有 refresh token 且业务 API 返回 401 时请求刷新；成功后仅重试那次遭拒绝的 API 请求一次，避免重复上传。
- 客户端使用 Android Keystore AES-GCM 保存 refresh token，不保存明文账号密码。若密钥失效，则要求用户在同一账号下重新验证密码。
- 服务端新增速率限制、审计日志、TTL（示例：access token 15～60分钟、refresh token 7～30天），且建议为刷新凭证明确撤销机制。
- 部署前增加登录→401→刷新→继续上传、令牌轮换、设备注销、密码更改、角色撤销、并发刷新和重放检测的测试。
- **不得为了延长会话而直接把现有 JWT 有效期设置为永久。**

## 对旧版服务器的降级行为

若登录响应不包含 `refresh_token`，APP不会尝试凭保存的密码自动登录；同步出现 401 时显示「登录已失效，请到更多 → 云端账号 → 重新验证登录」。已有本地待上传队列、账本和同步游标不清空。用户用原账号密码重新认证后继续同步，不应退出登录或删除数据。

**本文件仅定义兼容契约；未修改、部署或验证实际服务器。**
