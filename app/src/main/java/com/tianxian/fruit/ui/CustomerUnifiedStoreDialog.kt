package com.tianxian.fruit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tianxian.fruit.data.AppDatabase
import com.tianxian.fruit.data.StoreOption

/**
 * One decision per business date (all payment providers), not 127 individual edits.
 * This choice affects analytics only; it never changes imported receipts or
 * official daily takings and can always be undone.
 */
@Composable
internal fun CustomerUnifiedStoreDialog(
    db: AppDatabase,
    date: String,
    unmatchedPayments: Int,
    defaultStoreId: Long,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    // Use only stores with a saved, non-deleted business session on this date.
    // The store configuration list alone is not evidence that a stall opened.
    val stores = remember(date) {
        val operatedIds = db.getCustomerTrafficSessions().asSequence()
            .filter { it.date == date }
            .map { it.storeId }.toSet()
        db.getStores().filter { it.id in operatedIds && it.syncId.isNotBlank() }
    }
    val assignedId = remember(date) { db.getCustomerStoreDayAssignments()[date] }
    var choice by remember(date, assignedId) {
        mutableStateOf(
            stores.firstOrNull { it.syncId == assignedId }?.syncId
                ?: stores.firstOrNull { it.id == defaultStoreId }?.syncId
                ?: stores.firstOrNull()?.syncId.orEmpty()
        )
    }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("统一指定营业位置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("$date · 尚有 $unmatchedPayments 笔未匹配经营收款",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(7.dp))
                Text("将这一天微信、支付宝未匹配的收款统一计入所选位置；已有明确位置的交易不改动。",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                if (stores.isEmpty()) {
                    Text("当天没有已保存的营业位置。请先到营业表保存当天的经营记录，之后才能指定。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                stores.forEach { store: StoreOption ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { choice = store.syncId }
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = choice == store.syncId, onClick = { choice = store.syncId })
                        Text(store.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!assignedId.isNullOrBlank() && stores.none { it.syncId == assignedId }) {
                    Text("原指定位置不在当天营业列表中，可重新选择或撤销。",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                if (assignedId != null && assignedId.isNotBlank()) {
                    TextButton(onClick = { choice = "" }) { Text("撤销本日统一归属") }
                }
                if (error) {
                    Text("保存失败，请确认营业位置有效且设备存储正常。",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Text("本操作仅为人为统一归类，不代表实际收款发生在此摊。保存后执行账本同步即可同步到其他设备。",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (db.setCustomerStoreDayAssignment(date, choice)) onSaved()
                else error = true
            }, enabled = (choice.isNotBlank() && stores.any { it.syncId == choice }) ||
                (!assignedId.isNullOrBlank() && choice.isBlank())) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
