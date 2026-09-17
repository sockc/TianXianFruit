package com.tianxian.fruit.ui

import android.content.Context
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import java.security.SecureRandom
import java.time.LocalDate
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal class OperationSecurityManager(
    context: Context
) {
    private val prefs =
        context.applicationContext.getSharedPreferences(
            "tianxian_operation_security",
            Context.MODE_PRIVATE
        )

    fun hasPassword(): Boolean =
        !prefs.getString(KEY_PASSWORD_HASH, null).isNullOrBlank() &&
            !prefs.getString(KEY_PASSWORD_SALT, null).isNullOrBlank()

    fun biometricEnabled(): Boolean =
        prefs.getBoolean(KEY_BIOMETRIC_ENABLED, true)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_BIOMETRIC_ENABLED, enabled)
            .apply()
    }

    fun setPassword(password: String): Boolean {
        if (password.length < 4) return false

        val salt = ByteArray(16).also {
            SecureRandom().nextBytes(it)
        }
        val hash = derive(password, salt)

        prefs.edit()
            .putString(
                KEY_PASSWORD_SALT,
                Base64.encodeToString(salt, Base64.NO_WRAP)
            )
            .putString(
                KEY_PASSWORD_HASH,
                Base64.encodeToString(hash, Base64.NO_WRAP)
            )
            .apply()
        return true
    }

    fun verifyPassword(password: String): Boolean {
        val saltText =
            prefs.getString(KEY_PASSWORD_SALT, null)
                ?: return false
        val hashText =
            prefs.getString(KEY_PASSWORD_HASH, null)
                ?: return false

        return runCatching {
            val salt = Base64.decode(saltText, Base64.NO_WRAP)
            val expected = Base64.decode(hashText, Base64.NO_WRAP)
            val actual = derive(password, salt)
            java.security.MessageDigest.isEqual(expected, actual)
        }.getOrDefault(false)
    }

    fun changePassword(
        oldPassword: String,
        newPassword: String
    ): Boolean {
        if (!verifyPassword(oldPassword)) return false
        return setPassword(newPassword)
    }

    private fun derive(
        password: String,
        salt: ByteArray
    ): ByteArray {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt,
            120_000,
            256
        )
        return try {
            SecretKeyFactory
                .getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        private const val KEY_PASSWORD_SALT = "password_salt"
        private const val KEY_PASSWORD_HASH = "password_hash"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    }
}

internal data class PendingHistoricalAction(
    val date: String,
    val description: String,
    val action: () -> Unit
)

internal class HistorySecurityGate(
    val manager: OperationSecurityManager
) {
    var pending by mutableStateOf<PendingHistoricalAction?>(null)
        private set
    var requestVersion by mutableIntStateOf(0)
        private set
    var showPasswordDialog by mutableStateOf(false)
    var unlockedUntil by mutableLongStateOf(0L)
        private set

    fun run(
        date: String,
        description: String,
        action: () -> Unit
    ) {
        val today = LocalDate.now().toString()
        val historical = date.isNotBlank() && date < today
        if (!historical || System.currentTimeMillis() < unlockedUntil) {
            action()
            return
        }

        pending = PendingHistoricalAction(
            date = date,
            description = description,
            action = action
        )
        showPasswordDialog = false
        requestVersion++
    }

    fun grant() {
        val action = pending?.action
        unlockedUntil =
            System.currentTimeMillis() +
                AUTH_WINDOW_MILLIS
        pending = null
        showPasswordDialog = false
        action?.invoke()
    }

    fun cancel() {
        pending = null
        showPasswordDialog = false
    }

    fun requirePasswordFallback() {
        if (pending != null) {
            showPasswordDialog = true
        }
    }

    companion object {
        private const val AUTH_WINDOW_MILLIS = 3 * 60 * 1000L
    }
}

@Composable
internal fun HistorySecurityHost(
    gate: HistorySecurityGate
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val biometricManager = remember(context) {
        BiometricManager.from(context)
    }

    val prompt = remember(activity, gate) {
        activity?.let { host ->
            BiometricPrompt(
                host,
                androidx.core.content.ContextCompat.getMainExecutor(host),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        super.onAuthenticationSucceeded(result)
                        gate.grant()
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence
                    ) {
                        super.onAuthenticationError(errorCode, errString)
                        when (errorCode) {
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON ->
                                gate.requirePasswordFallback()
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_CANCELED ->
                                gate.cancel()
                            else ->
                                gate.requirePasswordFallback()
                        }
                    }
                }
            )
        }
    }

    LaunchedEffect(gate.requestVersion) {
        if (gate.requestVersion <= 0 || gate.pending == null) {
            return@LaunchedEffect
        }

        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK

        val canUseBiometric =
            gate.manager.biometricEnabled() &&
                prompt != null &&
                biometricManager.canAuthenticate(authenticators) ==
                    BiometricManager.BIOMETRIC_SUCCESS

        if (canUseBiometric) {
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("验证历史记录操作")
                    .setSubtitle(
                        gate.pending?.description
                            ?: "修改或删除历史记录"
                    )
                    .setAllowedAuthenticators(authenticators)
                    .setNegativeButtonText("使用操作密码")
                    .build()
            )
        } else {
            gate.requirePasswordFallback()
        }
    }

    if (gate.showPasswordDialog && gate.pending != null) {
        HistoricalPasswordDialog(
            manager = gate.manager,
            description = gate.pending?.description.orEmpty(),
            onDismiss = {
                gate.cancel()
            },
            onVerified = {
                gate.grant()
            }
        )
    }
}

@Composable
private fun HistoricalPasswordDialog(
    manager: OperationSecurityManager,
    description: String,
    onDismiss: () -> Unit,
    onVerified: () -> Unit
) {
    val firstSetup = !manager.hasPassword()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (firstSetup) {
                    "先设置操作密码"
                } else {
                    "输入操作密码"
                }
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (firstSetup) {
                        "历史记录修改和删除需要验证。请设置至少4位操作密码。"
                    } else {
                        description.ifBlank {
                            "验证后才能修改或删除历史记录。"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = ""
                    },
                    label = {
                        Text(
                            if (firstSetup) "新操作密码" else "操作密码"
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )

                if (firstSetup) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = {
                            confirm = it
                            error = ""
                        },
                        label = { Text("确认操作密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }

                if (error.isNotBlank()) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (firstSetup) {
                        when {
                            password.length < 4 ->
                                error = "操作密码至少4位"
                            password != confirm ->
                                error = "两次输入的密码不一致"
                            manager.setPassword(password) ->
                                onVerified()
                            else ->
                                error = "设置失败"
                        }
                    } else if (manager.verifyPassword(password)) {
                        onVerified()
                    } else {
                        error = "操作密码不正确"
                    }
                }
            ) {
                Text(if (firstSetup) "设置并继续" else "验证")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
internal fun SecuritySettingsContent(
    manager: OperationSecurityManager
) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var biometricEnabled by remember(version) {
        mutableStateOf(manager.biometricEnabled())
    }

    val biometricAvailable = remember(context, version) {
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        BiometricManager.from(context)
            .canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsSection("身份验证") {
            SettingsRow(
                icon = "🔐",
                title = "操作密码",
                trailing =
                    if (manager.hasPassword()) "已设置" else "未设置"
            ) {
                showPasswordDialog = true
            }

            SettingsDivider()

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("系统生物识别")
                    Text(
                        if (biometricAvailable) {
                            "历史操作优先使用系统指纹 / 面容验证"
                        } else {
                            "当前设备未检测到可用的系统生物识别"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = androidx.compose.ui.graphics.Color.Gray
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = biometricEnabled && biometricAvailable,
                    enabled = biometricAvailable,
                    onCheckedChange = {
                        biometricEnabled = it
                        manager.setBiometricEnabled(it)
                        version++
                    }
                )
            }
        }

        SettingsSection("历史记录保护") {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("当天记录", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(
                    "修改、删除不需要验证。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("以往记录", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(
                    "修改、删除强制验证；一次验证后3分钟内连续操作无需重复验证。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (showPasswordDialog) {
        ChangeOperationPasswordDialog(
            manager = manager,
            onDismiss = {
                showPasswordDialog = false
            },
            onSaved = {
                showPasswordDialog = false
                version++
            }
        )
    }
}

@Composable
private fun ChangeOperationPasswordDialog(
    manager: OperationSecurityManager,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val changing = manager.hasPassword()
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (changing) "修改操作密码" else "设置操作密码")
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (changing) {
                    OutlinedTextField(
                        value = oldPassword,
                        onValueChange = {
                            oldPassword = it
                            error = ""
                        },
                        label = { Text("当前操作密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = newPassword,
                    onValueChange = {
                        newPassword = it
                        error = ""
                    },
                    label = { Text("新操作密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        error = ""
                    },
                    label = { Text("确认新密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )

                if (error.isNotBlank()) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        newPassword.length < 4 ->
                            error = "操作密码至少4位"
                        newPassword != confirmPassword ->
                            error = "两次输入的新密码不一致"
                        changing && !manager.verifyPassword(oldPassword) ->
                            error = "当前操作密码不正确"
                        changing && !manager.changePassword(oldPassword, newPassword) ->
                            error = "修改失败"
                        !changing && !manager.setPassword(newPassword) ->
                            error = "设置失败"
                        else ->
                            onSaved()
                    }
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
