package com.tianxian.fruit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tianxian.fruit.data.AppDatabase
import com.tianxian.fruit.data.PurchasePlanItemRecord
import java.util.Locale

/**
 * Inline completion of a purchase-plan row. No formal purchase is written
 * while the user types: only explicit confirmation commits through the
 * existing AppDatabase transaction, preserving purchase/inventory semantics.
 */
@Composable
internal fun CollaborationInlineCompletionEditor(
    db: AppDatabase,
    item: PurchasePlanItemRecord,
    recorderUsername: String,
    recorderDisplayName: String,
    onCancel: () -> Unit,
    onCompleted: (String) -> Unit
) {
    val partners = remember(db) { db.getPartners() }
    var buyerId by remember(item.id) {
        mutableStateOf(item.buyerId.takeIf { it > 0L } ?: partners.firstOrNull()?.id)
    }
    var buyerMenu by remember(item.id) { mutableStateOf(false) }
    var quantity by remember(item.id) { mutableStateOf(compactPurchaseNumber(item.quantity)) }
    var price by remember(item.id) {
        mutableStateOf(compactPurchaseNumber(
            if (item.quantity > 0) item.estimatedAmount / item.quantity else 0.0
        ))
    }
    var amount by remember(item.id) { mutableStateOf(compactPurchaseNumber(item.estimatedAmount)) }
    var weight by remember(item.id) {
        mutableStateOf(compactPurchaseNumber(item.unitWeightJin.coerceAtLeast(0.0)))
    }
    var error by remember(item.id) { mutableStateOf("") }
    val buyer = partners.firstOrNull { it.id == buyerId }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("填写实购 · ${item.fruitName}", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                CompactPurchaseNumberField(
                    "数量（${item.unit}）", quantity,
                    {
                        quantity = it
                        val q = it.toDoubleOrNull()
                        val p = price.toDoubleOrNull()
                        if (q != null && q > 0 && p != null) {
                            amount = compactPurchaseNumber(q * p)
                        }
                    }, Modifier.weight(1f)
                )
                CompactPurchaseNumberField(
                    "每件重量（斤）", weight, { weight = it }, Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                CompactPurchaseNumberField(
                    "单价（元）", price,
                    {
                        price = it
                        val q = quantity.toDoubleOrNull()
                        val p = it.toDoubleOrNull()
                        if (q != null && q > 0 && p != null) {
                            amount = compactPurchaseNumber(q * p)
                        }
                    }, Modifier.weight(1f)
                )
                CompactPurchaseNumberField(
                    "总价（元）", amount,
                    {
                        amount = it
                        val q = quantity.toDoubleOrNull()
                        val a = it.toDoubleOrNull()
                        if (q != null && q > 0 && a != null) {
                            price = compactPurchaseNumber(a / q)
                        }
                    }, Modifier.weight(1f)
                )
            }
            Box {
                OutlinedButton(
                    onClick = { buyerMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("采购人：${buyer?.name ?: "请选择"}")
                }
                DropdownMenu(
                    expanded = buyerMenu,
                    onDismissRequest = { buyerMenu = false }
                ) {
                    partners.forEach { partner ->
                        DropdownMenuItem(
                            text = { Text(partner.name) },
                            onClick = {
                                buyerId = partner.id
                                buyerMenu = false
                            }
                        )
                    }
                }
            }
            if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "数量、单价、总价双向联动；确认后才生成正式采购记录。",
                style = MaterialTheme.typography.labelSmall
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel) { Text("取消") }
                Spacer(Modifier.width(5.dp))
                Button(
                    onClick = {
                        val q = quantity.toDoubleOrNull()
                        val a = amount.toDoubleOrNull()
                        val p = price.toDoubleOrNull()
                        val w = weight.toDoubleOrNull()
                        when {
                            buyer == null -> error = "请选择采购人"
                            q == null || q <= 0 ||
                                a == null || a < 0 ||
                                p == null || p < 0 ||
                                w == null || w < 0 -> {
                                error = "数量需大于0，单价、总价和重量不能留空或为负数"
                            }
                            else -> {
                                val result = db.completeCollaborationPlanItem(
                                    itemId = item.id,
                                    buyer = buyer,
                                    actualQuantity = q,
                                    actualAmount = a,
                                    actualUnitWeightJin = w,
                                    recorderUsername = recorderUsername,
                                    recorderDisplayName = recorderDisplayName
                                )
                                if (result.success) onCompleted(result.message)
                                else error = result.message
                            }
                        }
                    }
                ) {
                    Text("确认完成")
                }
            }
        }
    }
}

@Composable
private fun CompactPurchaseNumberField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = {
            if (it.matches(Regex("^\\d*(\\.\\d{0,2})?$"))) onValue(it)
        },
        modifier = modifier,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

private fun compactPurchaseNumber(value: Double): String =
    String.format(Locale.CHINA, "%.2f", value).trimEnd('0').trimEnd('.')
