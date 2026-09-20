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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.tianxian.fruit.sync.CloudSyncManager
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    fun sameDayDeleteVerification(): Boolean =
        prefs.getBoolean(KEY_VERIFY_TODAY_DELETE, false)

    fun setSameDayDeleteVerification(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VERIFY_TODAY_DELETE, enabled).apply()
    }

    fun historicalModifyVerification(): Boolean =
        prefs.getBoolean(KEY_VERIFY_HISTORY_MODIFY, true)

    fun setHistoricalModifyVerification(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VERIFY_HISTORY_MODIFY, enabled).apply()
    }

    fun historicalDeleteVerification(): Boolean =
        prefs.getBoolean(KEY_VERIFY_HISTORY_DELETE, true)

    fun setHistoricalDeleteVerification(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VERIFY_HISTORY_DELETE, enabled).apply()
    }

    fun profitDistributionVerification(): Boolean =
        prefs.getBoolean(KEY_VERIFY_PROFIT_DISTRIBUTION, true)

    fun setProfitDistributionVerification(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VERIFY_PROFIT_DISTRIBUTION, enabled).apply()
    }

    fun shouldVerify(
        date: String,
        description: String
    ): Boolean {
        val today = LocalDate.now().toString()
        val isDelete = description.contains("删除")
        val isProfitDistribution = description.contains("利润分配")
        val forcedLockedOperation =
            description.contains("已结算") ||
                description.contains("已采购记录") ||
                description.contains("已完成采购") ||
                description.contains("撤销结清")

        if (forcedLockedOperation) return true
        if (isProfitDistribution && profitDistributionVerification()) return true

        val historical = date.isNotBlank() && date < today
        return when {
            historical && isDelete -> historicalDeleteVerification()
            historical -> historicalModifyVerification()
            date == today && isDelete -> sameDayDeleteVerification()
            else -> false
        }
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
        private const val KEY_VERIFY_TODAY_DELETE = "verify_today_delete"
        private const val KEY_VERIFY_HISTORY_MODIFY = "verify_history_modify"
        private const val KEY_VERIFY_HISTORY_DELETE = "verify_history_delete"
        private const val KEY_VERIFY_PROFIT_DISTRIBUTION = "verify_profit_distribution"
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
        val verificationRequired =
            manager.shouldVerify(
                date = date,
                description = description
            )
        if (!verificationRequired || System.currentTimeMillis() < unlockedUntil) {
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
                    .setTitle("验证敏感操作")
                    .setSubtitle(
                        gate.pending?.description
                            ?: "请验证后继续"
                    )
                    .setAllowedAuthenticators(authenticators)
                    .setNegativeButtonText("使用验证密码")
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
                    "先设置验证密码"
                } else {
                    "输入验证密码"
                }
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (firstSetup) {
                        "敏感操作需要验证。请设置至少4位验证密码。"
                    } else {
                        description.ifBlank {
                            "验证后才能继续此操作。"
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
                            if (firstSetup) "新验证密码" else "验证密码"
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
                        label = { Text("确认验证密码") },
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
                                error = "验证密码至少4位"
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
                        error = "验证密码不正确"
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
    manager: OperationSecurityManager,
    cloudSyncManager: CloudSyncManager
) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }

    var biometricEnabled by remember(version) {
        mutableStateOf(manager.biometricEnabled())
    }
    var verifyTodayDelete by remember(version) {
        mutableStateOf(manager.sameDayDeleteVerification())
    }
    var verifyHistoryModify by remember(version) {
        mutableStateOf(manager.historicalModifyVerification())
    }
    var verifyHistoryDelete by remember(version) {
        mutableStateOf(manager.historicalDeleteVerification())
    }
    var verifyProfitDistribution by remember(version) {
        mutableStateOf(manager.profitDistributionVerification())
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
                title = if (manager.hasPassword()) "修改验证密码" else "设置验证密码",
                trailing = if (manager.hasPassword()) "已设置" else "未设置"
            ) {
                showPasswordDialog = true
            }

            if (manager.hasPassword()) {
                SettingsDivider()
                SettingsRow(
                    icon = "↻",
                    title = "忘记验证密码",
                    trailing = "使用登录密码重置"
                ) {
                    showForgotPasswordDialog = true
                }
            }

            SettingsDivider()

            SecurityToggleRow(
                title = "系统生物识别",
                description =
                    if (biometricAvailable) {
                        "验证时优先使用系统指纹 / 面容，失败或取消后可改用验证密码"
                    } else {
                        "当前设备未检测到可用的系统生物识别"
                    },
                checked = biometricEnabled && biometricAvailable,
                enabled = biometricAvailable,
                onCheckedChange = {
                    biometricEnabled = it
                    manager.setBiometricEnabled(it)
                    version++
                }
            )
        }

        SettingsSection("记录验证规则") {
            SecurityToggleRow(
                title = "当天记录删除验证",
                description = "开启后，删除当天采购、营业等记录也需要验证。",
                checked = verifyTodayDelete,
                onCheckedChange = {
                    verifyTodayDelete = it
                    manager.setSameDayDeleteVerification(it)
                    version++
                }
            )
            SettingsDivider()
            SecurityToggleRow(
                title = "历史记录修改验证",
                description = "控制以往日期记录的修改操作。",
                checked = verifyHistoryModify,
                onCheckedChange = {
                    verifyHistoryModify = it
                    manager.setHistoricalModifyVerification(it)
                    version++
                }
            )
            SettingsDivider()
            SecurityToggleRow(
                title = "历史记录删除验证",
                description = "控制以往日期记录的删除操作。",
                checked = verifyHistoryDelete,
                onCheckedChange = {
                    verifyHistoryDelete = it
                    manager.setHistoricalDeleteVerification(it)
                    version++
                }
            )
            SettingsDivider()
            SecurityToggleRow(
                title = "利润分配修改 / 删除验证",
                description = "修改利润分配规则、已保存分配或删除利润分配时需要验证。",
                checked = verifyProfitDistribution,
                onCheckedChange = {
                    verifyProfitDistribution = it
                    manager.setProfitDistributionVerification(it)
                    version++
                }
            )
        }

        Text(
            "已结算或已完成采购等锁定结果属于高风险数据，相关修改 / 删除始终要求验证。一次验证成功后 3 分钟内可连续进行受保护操作。",
            style = MaterialTheme.typography.bodySmall,
            color = androidx.compose.ui.graphics.Color.Gray,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
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

    if (showForgotPasswordDialog) {
        ForgotOperationPasswordDialog(
            manager = manager,
            cloudSyncManager = cloudSyncManager,
            onDismiss = {
                showForgotPasswordDialog = false
            },
            onSaved = {
                showForgotPasswordDialog = false
                version++
            }
        )
    }
}

@Composable
private fun SecurityToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color.Gray
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
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
            Text(if (changing) "修改验证密码" else "设置验证密码")
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
                        label = { Text("当前验证密码") },
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
                    label = { Text("新验证密码") },
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
                            error = "验证密码至少4位"
                        newPassword != confirmPassword ->
                            error = "两次输入的新密码不一致"
                        changing && !manager.verifyPassword(oldPassword) ->
                            error = "当前验证密码不正确"
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

@Composable
private fun ForgotOperationPasswordDialog(
    manager: OperationSecurityManager,
    cloudSyncManager: CloudSyncManager,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var loginPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = {
            if (!loading) onDismiss()
        },
        title = { Text("重置验证密码") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "请输入当前登录账号的登录密码。APP 会重新向服务器验证身份，验证通过后才能重置验证密码。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = loginPassword,
                    onValueChange = {
                        loginPassword = it
                        error = ""
                    },
                    label = { Text("登录密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !loading
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = {
                        newPassword = it
                        error = ""
                    },
                    label = { Text("新验证密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !loading
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        error = ""
                    },
                    label = { Text("确认新验证密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !loading
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
                enabled = !loading,
                onClick = {
                    when {
                        loginPassword.isBlank() ->
                            error = "请输入登录密码"
                        newPassword.length < 4 ->
                            error = "验证密码至少4位"
                        newPassword != confirmPassword ->
                            error = "两次输入的新密码不一致"
                        else -> {
                            loading = true
                            error = ""
                            scope.launch {
                                val verified =
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            cloudSyncManager.verifyCurrentAccountPassword(
                                                loginPassword
                                            )
                                        }.getOrDefault(false)
                                    }
                                loading = false
                                if (!verified) {
                                    error = "登录密码验证失败"
                                } else if (!manager.setPassword(newPassword)) {
                                    error = "验证密码重置失败"
                                } else {
                                    onSaved()
                                }
                            }
                        }
                    }
                }
            ) {
                Text(if (loading) "验证中…" else "验证并重置")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !loading,
                onClick = onDismiss
            ) {
                Text("取消")
            }
        }
    )
}
