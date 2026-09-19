package com.tianxian.fruit.ui

import androidx.compose.runtime.mutableIntStateOf

/**
 * 云端自动同步完成后的轻量 UI 刷新信号。
 *
 * 只递增 Compose 状态，通知当前页面重新读取本地数据库；
 * 不会主动发起新的云同步，因此不会形成同步循环。
 */
object SyncUiRefreshBus {
    val version = mutableIntStateOf(0)
}
