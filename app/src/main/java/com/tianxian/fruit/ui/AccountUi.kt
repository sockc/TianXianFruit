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

                Button(
                    onClick = {
                        if (
                            loading ||
                            username.trim().isBlank() ||
                            password.isBlank()
                        ) {
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
                                                cloudSyncManager
                                                    .defaultBaseUrl(),
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
