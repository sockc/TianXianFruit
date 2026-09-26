package com.tianxian.fruit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxian.fruit.sync.CloudSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CloudLoginGate(
    cloudSyncManager: CloudSyncManager,
    onLoggedIn: () -> Unit
) {
    var username by remember {
        mutableStateOf("")
    }
    var password by remember {
        mutableStateOf("")
    }
    var showAdvanced by remember {
        mutableStateOf(false)
    }
    var serverUrl by remember {
        mutableStateOf(
            cloudSyncManager.defaultBaseUrl()
        )
    }
    var loading by remember {
        mutableStateOf(false)
    }
    var message by remember {
        mutableStateOf("")
    }

    val scope =
        rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0B7A43),
                        Color(0xFF13A868)
                    )
                )
            )
            .padding(24.dp),
        contentAlignment =
            Alignment.Center
    ) {
        Card(
            modifier =
                Modifier.fillMaxWidth(),
            shape =
                RoundedCornerShape(22.dp)
        ) {
            Column(
                Modifier.padding(22.dp),
                verticalArrangement =
                    Arrangement.spacedBy(13.dp)
            ) {
                Text(
                    "🍇 天鲜账本",
                    fontSize = 27.sp,
                    fontWeight =
                        FontWeight.Bold
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    label = {
                        Text("用户名")
                    },
                    singleLine = true
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    label = {
                        Text("密码")
                    },
                    singleLine = true,
                    visualTransformation =
                        PasswordVisualTransformation()
                )

                TextButton(
                    onClick = {
                        showAdvanced =
                            !showAdvanced
                    },
                    contentPadding =
                        PaddingValues(
                            horizontal = 0.dp,
                            vertical = 2.dp
                        )
                ) {
                    Text(
                        if (showAdvanced) {
                            "收起高级设置"
                        } else {
                            "高级设置 · 更改服务器"
                        }
                    )
                }

                if (showAdvanced) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = {
                            serverUrl = it
                        },
                        modifier =
                            Modifier.fillMaxWidth(),
                        label = {
                            Text("服务器地址")
                        },
                        placeholder = {
                            Text(
                                CloudSyncManager
                                    .DEFAULT_BASE_URL
                            )
                        },
                        supportingText = {
                            Text(
                                "迁移或测试服务器时使用。登录成功后才会保存。"
                            )
                        },
                        singleLine = true
                    )

                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                serverUrl =
                                    CloudSyncManager
                                        .DEFAULT_BASE_URL
                            },
                            contentPadding =
                                PaddingValues(0.dp)
                        ) {
                            Text("恢复默认服务器")
                        }

                        Spacer(
                            Modifier.weight(1f)
                        )

                        Text(
                            if (
                                serverUrl
                                    .trim()
                                    .startsWith(
                                        "http://",
                                        ignoreCase = true
                                    )
                            ) {
                                "⚠ 正式版请使用 HTTPS"
                            } else {
                                "HTTPS"
                            },
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                if (
                                    serverUrl
                                        .trim()
                                        .startsWith(
                                            "http://",
                                            ignoreCase = true
                                        )
                                ) {
                                    Color(0xFFB26A00)
                                } else {
                                    Color.Gray
                                }
                        )
                    }
                } else if (
                    serverUrl
                        .trim()
                        .trimEnd('/') !=
                    CloudSyncManager
                        .DEFAULT_BASE_URL
                ) {
                    Text(
                        "当前使用自定义服务器：${serverUrl.trim()}",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color = Color(0xFFB26A00)
                    )
                }

                Button(
                    onClick = {
                        if (
                            loading ||
                            username.trim().isBlank() ||
                            password.isBlank()
                        ) {
                            return@Button
                        }

                        if (
                            serverUrl
                                .trim()
                                .startsWith(
                                    "http://",
                                    ignoreCase = true
                                )
                        ) {
                            showAdvanced = true
                            message =
                                "正式版为保护账号密码，仅支持 HTTPS 服务器"
                            return@Button
                        }

                        loading = true
                        message =
                            "正在登录…"

                        scope.launch {
                            val result =
                                withContext(
                                    Dispatchers.IO
                                ) {
                                    runCatching {
                                        cloudSyncManager.login(
                                            baseUrl =
                                                serverUrl,
                                            username =
                                                username.trim(),
                                            password =
                                                password
                                        )
                                    }
                                }

                            loading = false

                            result
                                .onSuccess {
                                    password = ""
                                    message = ""
                                    onLoggedIn()
                                }
                                .onFailure {
                                    message =
                                        it.message
                                            ?: "登录失败"
                                }
                        }
                    },
                    enabled = !loading,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier =
                                Modifier.size(17.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(
                            Modifier.width(8.dp)
                        )
                    }
                    Text("登录")
                }

                if (
                    message.isNotBlank()
                ) {
                    Text(
                        message,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            if (
                                message ==
                                "正在登录…"
                            ) {
                                Color.Gray
                            } else {
                                MaterialTheme
                                    .colorScheme
                                    .error
                            }
                    )
                }

                Text(
                    "账号由系统管理员统一创建。",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}
