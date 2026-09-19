package com.tianxian.fruit.ui

import androidx.compose.runtime.mutableIntStateOf

/**
 * 仅用于通知当前 Compose 界面：云端自动同步已经完成，
 * 需要重新读取本地数据库。它不触发新的云同步。
 */
object SyncUiRefreshBus {
    val version = mutableIntStateOf(0)
}
