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

/**
 * Existing book partners, not transaction payer identity.
 * One exported bill file maps to one account owner; when the export combines
 * several owners, leave it unassigned rather than guessing.
 */
@Composable
internal fun CustomerBillCollectorPicker(
    options: List<Pair<String, String>>,
    current: String,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var selected by remember(title, current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())) {
                Text("选择这份微信/支付宝账单的收款账号所属人员，不是付款顾客。",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                (listOf("" to "未指定收款人") + options).forEach { (id, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = id }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == id, onClick = { selected = id })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("若导出文件包含多个收款账号，请保留「未指定」，不可把一份混合账单全部归给某人。",
                    color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
