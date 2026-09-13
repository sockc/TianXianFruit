package com.tianxian.fruit.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tianxian.fruit.sync.CloudAdminDeviceInfo
import com.tianxian.fruit.sync.CloudAdminUserInfo
import com.tianxian.fruit.sync.CloudSyncManager
import com.tianxian.fruit.sync.CloudSystemAuditInfo

@Composable
fun SystemAdminContent(
    cloudSyncManager: CloudSyncManager
) {
    var users by remember { mutableStateOf<List<CloudAdminUserInfo>>(emptyList()) }
    var devices by remember { mutableStateOf<List<CloudAdminDeviceInfo>>(emptyList()) }
    var audits by remember { mutableStateOf<List<CloudSystemAuditInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var actionUser by remember { mutableStateOf<CloudAdminUserInfo?>(null) }
    var actionDevice by remember { mutableStateOf<CloudAdminDeviceInfo?>(null) }
    var renameUser by remember { mutableStateOf<CloudAdminUserInfo?>(null) }
    var resetPasswordUser by remember { mutableStateOf<CloudAdminUserInfo?>(null) }
    var deleteUser by remember { mutableStateOf<CloudAdminUserInfo?>(null) }

    fun runAdminTask(
        busyText: String,
        block: () -> Triple<List<CloudAdminUserInfo>, List<CloudAdminDeviceInfo>, List<CloudSystemAuditInfo>>
    ) {
        if (loading) return
        loading = true
        message = busyText
        Thread {
            val result = runCatching { block() }
            Handler(Looper.getMainLooper()).post {
                loading = false
                result.onSuccess {
                    users = it.first
                    devices = it.second
                    audits = it.third
                    message = "用户 ${users.size} · 设备 ${devices.size}"
                }.onFailure {
                    message = it.message ?: "读取失败"
                }
            }
        }.start()
    }

    fun reload() {
        runAdminTask("正在读取系统管理数据…") {
            Triple(
                cloudSyncManager.listAdminUsers(),
                cloudSyncManager.listAdminDevices(),
                cloudSyncManager.listSystemAudit(100)
            )
        }
    }

    LaunchedEffect(Unit) {
        reload()
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("用户") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("设备") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("审计") })
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { reload() }, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text("刷新")
            }
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (message.contains("失败")) MaterialTheme.colorScheme.error else Color.Gray
            )
        }

        when (tab) {
            0 -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(users, key = { it.id }) { user ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("${user.displayName}（${user.username}）", fontWeight = FontWeight.Bold)
                            Text(
                                "${user.systemRole} · ${if (user.active) "正常" else "已停用"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (user.systemRole == "SUPERADMIN") MaterialTheme.colorScheme.primary
                                else if (user.active) Color.Gray else MaterialTheme.colorScheme.error
                            )
                            Text(
                                "拥有账本 ${user.ownedBookCount} · 参与 ${user.memberBookCount} · 设备 ${user.deviceCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                            if (user.systemRole != "SUPERADMIN") {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { renameUser = user }, enabled = !loading) { Text("改名") }
                                    TextButton(onClick = { resetPasswordUser = user }, enabled = !loading) { Text("重置密码") }
                                    TextButton(onClick = { actionUser = user }, enabled = !loading) {
                                        Text(if (user.active) "停用" else "启用")
                                    }
                                    TextButton(
                                        onClick = {
                                            if (loading) return@TextButton
                                            loading = true
                                            message = "正在强制退出…"
                                            Thread {
                                                val result = runCatching { cloudSyncManager.forceLogoutAllDevices(user.id) }
                                                Handler(Looper.getMainLooper()).post {
                                                    loading = false
                                                    message = result.fold(
                                                        onSuccess = { "已强制退出 ${user.username} 的全部登录" },
                                                        onFailure = { it.message ?: "操作失败" }
                                                    )
                                                }
                                            }.start()
                                        },
                                        enabled = !loading
                                    ) { Text("退出全部") }
                                    TextButton(onClick = { deleteUser = user }, enabled = !loading) {
                                        Text("删除账号", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            1 -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(devices, key = { it.deviceId }) { device ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(device.name.ifBlank { "Android设备" }, fontWeight = FontWeight.Bold)
                            Text(
                                "${device.displayName}（${device.username}） · ${device.appVersion}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                            Text(
                                "最后活动：${device.lastSeenAt.replace("T", " ").take(19)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                            Text(
                                if (device.revoked) "已删除 / 登录失效" else "正常",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (device.revoked) MaterialTheme.colorScheme.error else Color.Gray
                            )
                            if (!device.revoked) {
                                TextButton(onClick = { actionDevice = device }, enabled = !loading) {
                                    Text("删除设备并强制退出")
                                }
                            }
                        }
                    }
                }
            }

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(audits, key = { it.id }) { row ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text("${row.actorUsername} · ${adminActionLabel(row.action)}", fontWeight = FontWeight.Bold)
                            Text(
                                "${row.targetType} · ${row.targetId.take(16)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                            Text(
                                row.createdAt.replace("T", " ").take(19),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }

    renameUser?.let { user ->
        var newName by remember(user.id) { mutableStateOf(user.displayName) }
        AlertDialog(
            onDismissRequest = { renameUser = null },
            title = { Text("修改用户名称") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("显示名称") }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    val value = newName.trim()
                    renameUser = null
                    if (value.isBlank() || loading) return@Button
                    loading = true
                    Thread {
                        val result = runCatching {
                            cloudSyncManager.updateAdminUserDisplayName(user.id, value)
                            cloudSyncManager.listAdminUsers()
                        }
                        Handler(Looper.getMainLooper()).post {
                            loading = false
                            result.onSuccess { users = it; message = "用户名称已修改" }
                                .onFailure { message = it.message ?: "操作失败" }
                        }
                    }.start()
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameUser = null }) { Text("取消") } }
        )
    }

    resetPasswordUser?.let { user ->
        var newPassword by remember(user.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { resetPasswordUser = null },
            title = { Text("重置密码") },
            text = { OutlinedTextField(newPassword, { newPassword = it }, label = { Text("新密码（至少8位）") }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    val value = newPassword
                    resetPasswordUser = null
                    if (value.length < 8 || loading) return@Button
                    loading = true
                    Thread {
                        val result = runCatching { cloudSyncManager.resetAdminUserPassword(user.id, value) }
                        Handler(Looper.getMainLooper()).post {
                            loading = false
                            message = result.fold(
                                onSuccess = { "${user.username} 密码已重置，原登录全部失效" },
                                onFailure = { it.message ?: "操作失败" }
                            )
                        }
                    }.start()
                }) { Text("重置") }
            },
            dismissButton = { TextButton(onClick = { resetPasswordUser = null }) { Text("取消") } }
        )
    }

    deleteUser?.let { user ->
        AlertDialog(
            onDismissRequest = { deleteUser = null },
            title = { Text("删除账号") },
            text = { Text("删除 ${user.username}？V1.3.5 使用安全软删除：账号立即停用、全部登录失效，但其账本和审计不会被物理删除。") },
            confirmButton = {
                Button(onClick = {
                    deleteUser = null
                    if (loading) return@Button
                    loading = true
                    Thread {
                        val result = runCatching {
                            cloudSyncManager.deleteAdminUser(user.id)
                            cloudSyncManager.listAdminUsers()
                        }
                        Handler(Looper.getMainLooper()).post {
                            loading = false
                            result.onSuccess { users = it; message = "账号已删除（软删除）" }
                                .onFailure { message = it.message ?: "操作失败" }
                        }
                    }.start()
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { deleteUser = null }) { Text("取消") } }
        )
    }

    actionUser?.let { user ->
        AlertDialog(
            onDismissRequest = { actionUser = null },
            title = { Text(if (user.active) "停用账号" else "启用账号") },
            text = {
                Text(
                    if (user.active) "停用 ${user.username}？该账号现有登录会立即失效，但账本数据不会删除。"
                    else "重新启用 ${user.username}？"
                )
            },
            confirmButton = {
                Button(onClick = {
                    val newActive = !user.active
                    actionUser = null
                    if (loading) return@Button
                    loading = true
                    Thread {
                        val result = runCatching {
                            cloudSyncManager.setAdminUserActive(user.id, newActive)
                            cloudSyncManager.listAdminUsers()
                        }
                        Handler(Looper.getMainLooper()).post {
                            loading = false
                            result.onSuccess {
                                users = it
                                message = if (newActive) "账号已启用" else "账号已停用"
                            }.onFailure { message = it.message ?: "操作失败" }
                        }
                    }.start()
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { actionUser = null }) { Text("取消") } }
        )
    }

    actionDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { actionDevice = null },
            title = { Text("删除设备") },
            text = { Text("移除 ${device.username} 的“${device.name}”？V1.3.5 设备令牌会立即失效；以后重新登录可以重新注册该设备。") },
            confirmButton = {
                Button(onClick = {
                    actionDevice = null
                    if (loading) return@Button
                    loading = true
                    Thread {
                        val result = runCatching {
                            cloudSyncManager.revokeAdminDevice(device.deviceId)
                            cloudSyncManager.listAdminDevices()
                        }
                        Handler(Looper.getMainLooper()).post {
                            loading = false
                            result.onSuccess {
                                devices = it
                                message = "设备已删除并强制退出"
                            }.onFailure { message = it.message ?: "操作失败" }
                        }
                    }.start()
                }) { Text("删除并退出") }
            },
            dismissButton = { TextButton(onClick = { actionDevice = null }) { Text("取消") } }
        )
    }
}

private fun adminActionLabel(action: String): String = when (action) {
    "BOOK_CREATE" -> "新建账本"
    "BOOK_RENAME" -> "账本改名"
    "BOOK_DELETE_TO_TRASH" -> "删除账本"
    "BOOK_RESTORE" -> "恢复账本"
    "BOOK_PURGE_DATA" -> "永久清空账本"
    "BOOK_TRANSFER_OWNER" -> "转移所有权"
    "USER_UPDATE" -> "修改用户"
    "USER_DISABLE" -> "停用用户"
    "USER_FORCE_LOGOUT_ALL" -> "强制退出全部设备"
    "USER_RESET_PASSWORD" -> "重置密码"
    "DEVICE_REVOKE" -> "删除设备"
    else -> action
}
