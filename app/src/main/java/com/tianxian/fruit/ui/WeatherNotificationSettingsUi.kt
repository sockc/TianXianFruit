package com.tianxian.fruit.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tianxian.fruit.data.AppDatabase
import com.tianxian.fruit.sync.LedgerBook
import com.tianxian.fruit.weather.WeatherNotificationScheduler
import com.tianxian.fruit.weather.WeatherNotificationSettingsManager
import kotlin.math.roundToInt

@Composable
fun WeatherNotificationSettingsContent(
    db: AppDatabase,
    dataVersion: Int,
    currentBook: LedgerBook
) {
    val context =
        LocalContext.current
    val manager =
        remember(
            context
        ) {
            WeatherNotificationSettingsManager(
                context
            )
        }
    val stores =
        remember(
            dataVersion
        ) {
            db.getStores()
                .filter {
                    it.latitude != null &&
                        it.longitude != null
                }
        }
    var settings by
        remember(
            currentBook.id
        ) {
            mutableStateOf(
                manager.load(
                    currentBook.id
                )
            )
        }
    var message by
        remember {
            mutableStateOf(
                ""
            )
        }
    var storeMenu by
        remember {
            mutableStateOf(
                false
            )
        }
    var permissionGranted by
        remember {
            mutableStateOf(
                notificationsAllowed(
                    context
                )
            )
        }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts
                .RequestPermission()
        ) {
            granted ->
            permissionGranted =
                (
                    granted ||
                        Build.VERSION.SDK_INT <
                        33
                    ) &&
                    NotificationManagerCompat
                        .from(
                            context
                        )
                        .areNotificationsEnabled()
            if (
                permissionGranted
            ) {
                settings =
                    settings.copy(
                        enabled = true
                    )
                message =
                    "通知权限已开启，保存设置后开始天气提醒"
            } else {
                settings =
                    settings.copy(
                        enabled = false
                    )
                message =
                    "系统通知权限未开启，天气提醒暂不能发送"
            }
        }

    fun requestPermission() {
        val runtimeGranted =
            runtimeNotificationPermissionGranted(
                context
            )

        when {
            !runtimeGranted &&
                Build.VERSION.SDK_INT >=
                33 -> {
                permissionLauncher.launch(
                    Manifest.permission
                        .POST_NOTIFICATIONS
                )
            }

            !NotificationManagerCompat
                .from(
                    context
                )
                .areNotificationsEnabled() -> {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    ).apply {
                        putExtra(
                            Settings.EXTRA_APP_PACKAGE,
                            context.packageName
                        )
                    }
                )
                message =
                    "系统已关闭天鲜账本通知，请在系统页面打开通知后返回"
            }

            else -> {
                permissionGranted = true
                settings =
                    settings.copy(
                        enabled = true
                    )
            }
        }
    }

    fun save() {
        if (
            settings.enabled &&
            !notificationsAllowed(
                context
            )
        ) {
            requestPermission()
            message =
                "请先允许系统通知权限，再保存天气提醒"
            return
        }

        manager.save(
            currentBook.id,
            settings
        )
        WeatherNotificationScheduler
            .refresh(
                context
            )
        if (
            settings.enabled
        ) {
            WeatherNotificationScheduler
                .checkNow(
                    context
                )
        }
        message =
            if (
                settings.enabled
            ) {
                "天气通知已保存；系统会定时检查，风险升级时可再次提醒"
            } else {
                "天气通知已关闭"
            }
    }

    LazyColumn(
        Modifier
            .fillMaxSize(),
        contentPadding =
            PaddingValues(
                horizontal = 14.dp,
                vertical = 10.dp
            ),
        verticalArrangement =
            Arrangement.spacedBy(
                10.dp
            )
    ) {
        item {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            Color(
                                0xFFF1F8F4
                            )
                    ),
                shape =
                    RoundedCornerShape(
                        16.dp
                    )
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            14.dp
                        ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            8.dp
                        )
                ) {
                    WeatherSwitchRow(
                        title =
                            "天气通知",
                        subtitle =
                            if (
                                permissionGranted
                            ) {
                                "系统通知权限已开启"
                            } else {
                                "需要开启系统通知权限"
                            },
                        checked =
                            settings.enabled,
                        onChecked = {
                            enabled ->
                            if (
                                enabled &&
                                !permissionGranted
                            ) {
                                requestPermission()
                            } else {
                                settings =
                                    settings.copy(
                                        enabled =
                                            enabled
                                    )
                            }
                        }
                    )

                    Text(
                        "每小时后台检查一次；同类风险当天只提醒一次，风险明显升级时才再次提醒。",
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                        color =
                            Color.DarkGray
                    )

                    if (
                        !permissionGranted
                    ) {
                        OutlinedButton(
                            onClick = {
                                requestPermission()
                            },
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "开启系统通知权限"
                            )
                        }
                    }
                }
            }
        }

        item {
            WeatherSettingsCard(
                title =
                    "天气通知位置"
            ) {
                Text(
                    "只选一个位置，避免多个摊位重复通知。选“自动”时按当天营业记录、星期固定位置和历史安排自动判断。",
                    style =
                        MaterialTheme.typography
                            .bodySmall,
                    color =
                        Color.Gray
                )

                Box(
                    Modifier.fillMaxWidth()
                ) {
                    val selectedName =
                        if (
                            settings.storeId <=
                            0L
                        ) {
                            "自动选择（推荐）"
                        } else {
                            stores.firstOrNull {
                                it.id ==
                                    settings.storeId
                            }?.name
                                ?: "自动选择（推荐）"
                        }

                    OutlinedButton(
                        onClick = {
                            storeMenu = true
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            selectedName
                        )
                    }

                    DropdownMenu(
                        expanded =
                            storeMenu,
                        onDismissRequest = {
                            storeMenu =
                                false
                        }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "自动选择（按当天安排）"
                                )
                            },
                            onClick = {
                                settings =
                                    settings.copy(
                                        storeId =
                                            0L
                                    )
                                storeMenu =
                                    false
                            }
                        )
                        stores.forEach {
                            store ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        store.name
                                    )
                                },
                                onClick = {
                                    settings =
                                        settings.copy(
                                            storeId =
                                                store.id
                                        )
                                    storeMenu =
                                        false
                                }
                            )
                        }
                    }
                }

                if (
                    stores.isEmpty()
                ) {
                    Text(
                        "目前没有已绑定经纬度的位置，请先到位置管理设置经纬度。",
                        color =
                            MaterialTheme.colorScheme
                                .error,
                        style =
                            MaterialTheme.typography
                                .bodySmall
                    )
                }
            }
        }

        item {
            WeatherSettingsCard(
                title =
                    "采购天气"
            ) {
                WeatherSwitchRow(
                    title =
                        "采购前提醒",
                    subtitle =
                        "在采购前约2小时开始检查低温、风雨和温差",
                    checked =
                        settings.procurementEnabled,
                    onChecked = {
                        settings =
                            settings.copy(
                                procurementEnabled =
                                    it
                            )
                    }
                )

                if (
                    settings.procurementEnabled
                ) {
                    HorizontalDivider()

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                8.dp
                            )
                    ) {
                        TimeSettingButton(
                            label =
                                "开始",
                            value =
                                settings.procurementStartTime,
                            modifier =
                                Modifier.weight(
                                    1f
                                )
                        ) {
                            value ->
                            settings =
                                settings.copy(
                                    procurementStartTime =
                                        value
                                )
                        }
                        TimeSettingButton(
                            label =
                                "结束",
                            value =
                                settings.procurementEndTime,
                            modifier =
                                Modifier.weight(
                                    1f
                                )
                        ) {
                            value ->
                            settings =
                                settings.copy(
                                    procurementEndTime =
                                        value
                                )
                        }
                    }
                }
            }
        }

        item {
            WeatherSettingsCard(
                title =
                    "营业天气"
            ) {
                WeatherSwitchRow(
                    title =
                        "开摊前提醒",
                    subtitle =
                        "自动读取通知位置保存的营业时间，提前约3小时检查",
                    checked =
                        settings.businessEnabled,
                    onChecked = {
                        settings =
                            settings.copy(
                                businessEnabled =
                                    it
                            )
                    }
                )
            }
        }

        item {
            WeatherSettingsCard(
                title =
                    "提醒条件"
            ) {
                WeatherThresholdRow(
                    title =
                        "低温",
                    subtitle =
                        "采购/营业时段最低温达到阈值",
                    checked =
                        settings.coldEnabled,
                    value =
                        settings.coldThresholdC,
                    valueRange =
                        5f..20f,
                    unit =
                        "℃",
                    onChecked = {
                        settings =
                            settings.copy(
                                coldEnabled =
                                    it
                            )
                    },
                    onValue = {
                        settings =
                            settings.copy(
                                coldThresholdC =
                                    it
                            )
                    }
                )

                SettingsSmallDivider()

                WeatherThresholdRow(
                    title =
                        "早晚温差",
                    subtitle =
                        "全天最高与最低温差达到阈值",
                    checked =
                        settings.temperatureSwingEnabled,
                    value =
                        settings.temperatureSwingThresholdC,
                    valueRange =
                        4f..15f,
                    unit =
                        "℃",
                    onChecked = {
                        settings =
                            settings.copy(
                                temperatureSwingEnabled =
                                    it
                            )
                    },
                    onValue = {
                        settings =
                            settings.copy(
                                temperatureSwingThresholdC =
                                    it
                            )
                    }
                )

                SettingsSmallDivider()

                WeatherThresholdRow(
                    title =
                        "降雨",
                    subtitle =
                        "逐小时最高降雨概率达到阈值",
                    checked =
                        settings.rainEnabled,
                    value =
                        settings.rainProbabilityThreshold,
                    valueRange =
                        20f..90f,
                    unit =
                        "%",
                    onChecked = {
                        settings =
                            settings.copy(
                                rainEnabled =
                                    it
                            )
                    },
                    onValue = {
                        settings =
                            settings.copy(
                                rainProbabilityThreshold =
                                    (it / 5.0)
                                        .roundToInt() *
                                        5.0
                            )
                    }
                )

                SettingsSmallDivider()

                WeatherThresholdRow(
                    title =
                        "大风",
                    subtitle =
                        "逐小时最大风速达到阈值",
                    checked =
                        settings.windEnabled,
                    value =
                        settings.windSpeedThresholdKmh,
                    valueRange =
                        10f..50f,
                    unit =
                        "km/h",
                    onChecked = {
                        settings =
                            settings.copy(
                                windEnabled =
                                    it
                            )
                    },
                    onValue = {
                        settings =
                            settings.copy(
                                windSpeedThresholdKmh =
                                    it
                            )
                    }
                )

                SettingsSmallDivider()

                WeatherThresholdRow(
                    title =
                        "快速降温",
                    subtitle =
                        "未来6小时内降温达到阈值",
                    checked =
                        settings.rapidCoolingEnabled,
                    value =
                        settings.rapidCoolingThresholdC,
                    valueRange =
                        3f..10f,
                    unit =
                        "℃",
                    onChecked = {
                        settings =
                            settings.copy(
                                rapidCoolingEnabled =
                                    it
                            )
                    },
                    onValue = {
                        settings =
                            settings.copy(
                                rapidCoolingThresholdC =
                                    it
                            )
                    }
                )

                SettingsSmallDivider()

                WeatherSwitchRow(
                    title =
                        "天气预警",
                    subtitle =
                        "暴雨、大风、寒潮、强对流等预警使用高优先级通知",
                    checked =
                        settings.alertEnabled,
                    onChecked = {
                        settings =
                            settings.copy(
                                alertEnabled =
                                    it
                            )
                    }
                )
            }
        }

        item {
            WeatherSettingsCard(
                title =
                    "通知策略"
            ) {
                WeatherSwitchRow(
                    title =
                        "仅异常天气通知",
                    subtitle =
                        if (
                            settings.onlyAbnormal
                        ) {
                            "天气正常时不打扰"
                        } else {
                            "即使天气正常，也会发送采购/营业天气简报"
                        },
                    checked =
                        settings.onlyAbnormal,
                    onChecked = {
                        settings =
                            settings.copy(
                                onlyAbnormal =
                                    it
                            )
                    }
                )
            }
        }

        item {
            Button(
                onClick = {
                    save()
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            46.dp
                        )
            ) {
                Text(
                    "保存天气通知设置"
                )
            }
        }

        if (
            message.isNotBlank()
        ) {
            item {
                Text(
                    message,
                    color =
                        if (
                            message.contains(
                                "未开启"
                            ) ||
                            message.contains(
                                "请先"
                            )
                        ) {
                            MaterialTheme.colorScheme
                                .error
                        } else {
                            Color(
                                0xFF138A5B
                            )
                        },
                    style =
                        MaterialTheme.typography
                            .bodySmall
                )
            }
        }

        item {
            Spacer(
                Modifier.height(
                    12.dp
                )
            )
        }
    }
}

@Composable
private fun WeatherSettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape =
            RoundedCornerShape(
                16.dp
            ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color.White
            )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    14.dp
                ),
            verticalArrangement =
                Arrangement.spacedBy(
                    10.dp
                )
        ) {
            Text(
                title,
                fontWeight =
                    FontWeight.Bold,
                style =
                    MaterialTheme.typography
                        .titleMedium
            )
            content()
        }
    }
}

@Composable
private fun WeatherSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically,
        horizontalArrangement =
            Arrangement.spacedBy(
                10.dp
            )
    ) {
        Column(
            Modifier.weight(
                1f
            ),
            verticalArrangement =
                Arrangement.spacedBy(
                    2.dp
                )
        ) {
            Text(
                title,
                fontWeight =
                    FontWeight.SemiBold
            )
            Text(
                subtitle,
                style =
                    MaterialTheme.typography
                        .bodySmall,
                color =
                    Color.Gray
            )
        }
        Switch(
            checked =
                checked,
            onCheckedChange =
                onChecked
        )
    }
}

@Composable
private fun WeatherThresholdRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    value: Double,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String,
    onChecked: (Boolean) -> Unit,
    onValue: (Double) -> Unit
) {
    Column(
        verticalArrangement =
            Arrangement.spacedBy(
                4.dp
            )
    ) {
        WeatherSwitchRow(
            title =
                title,
            subtitle =
                subtitle,
            checked =
                checked,
            onChecked =
                onChecked
        )

        if (
            checked
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Slider(
                    value =
                        value.toFloat()
                            .coerceIn(
                                valueRange.start,
                                valueRange.endInclusive
                            ),
                    onValueChange = {
                        onValue(
                            it.roundToInt()
                                .toDouble()
                        )
                    },
                    valueRange =
                        valueRange,
                    modifier =
                        Modifier.weight(
                            1f
                        )
                )
                Spacer(
                    Modifier.width(
                        8.dp
                    )
                )
                Text(
                    "${value.roundToInt()}$unit",
                    modifier =
                        Modifier.width(
                            62.dp
                        ),
                    fontWeight =
                        FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun TimeSettingButton(
    label: String,
    value: String,
    modifier: Modifier =
        Modifier,
    onValue: (String) -> Unit
) {
    val context =
        LocalContext.current

    OutlinedButton(
        onClick = {
            val parts =
                value.split(
                    ":"
                )
            val hour =
                parts.getOrNull(
                    0
                )
                    ?.toIntOrNull()
                    ?: 6
            val minute =
                parts.getOrNull(
                    1
                )
                    ?.toIntOrNull()
                    ?: 0
            TimePickerDialog(
                context,
                {
                    _,
                    selectedHour,
                    selectedMinute ->
                    onValue(
                        "%02d:%02d".format(
                            selectedHour,
                            selectedMinute
                        )
                    )
                },
                hour.coerceIn(
                    0,
                    23
                ),
                minute.coerceIn(
                    0,
                    59
                ),
                true
            ).show()
        },
        modifier =
            modifier
    ) {
        Text(
            "$label  $value"
        )
    }
}

@Composable
private fun SettingsSmallDivider() {
    HorizontalDivider(
        color =
            Color(
                0xFFEAECEF
            )
    )
}

private fun runtimeNotificationPermissionGranted(
    context: android.content.Context
): Boolean =
    Build.VERSION.SDK_INT <
        33 ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission
                .POST_NOTIFICATIONS
        ) ==
        PackageManager.PERMISSION_GRANTED

private fun notificationsAllowed(
    context: android.content.Context
): Boolean =
    runtimeNotificationPermissionGranted(
        context
    ) &&
        NotificationManagerCompat
            .from(
                context
            )
            .areNotificationsEnabled()
