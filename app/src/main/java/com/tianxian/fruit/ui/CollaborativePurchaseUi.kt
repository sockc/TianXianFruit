package com.tianxian.fruit.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxian.fruit.data.AppDatabase
import com.tianxian.fruit.data.FruitOption
import com.tianxian.fruit.data.PartnerOption
import com.tianxian.fruit.data.PurchasePlanItemRecord
import com.tianxian.fruit.sync.BookPermissions
import com.tianxian.fruit.sync.CloudSyncManager
import com.tianxian.fruit.sync.LedgerBook
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CollaborativePurchaseContent(
    db: AppDatabase,
    dataVersion: Int,
    currentBook: LedgerBook,
    cloudSyncManager: CloudSyncManager,
    onChanged: () -> Unit
) {
    val session =
        cloudSyncManager.session()

    val systemRole =
        session?.systemRole
            .orEmpty()

    val canView =
        BookPermissions.has(
            currentBook,
            systemRole,
            BookPermissions.PURCHASE_ACTIVITY_VIEW
        ) ||
            BookPermissions.has(
                currentBook,
                systemRole,
                BookPermissions.PURCHASE_VIEW
            )

    val canEditPlan =
        BookPermissions.has(
            currentBook,
            systemRole,
            BookPermissions.PURCHASE_PLAN_EDIT
        )

    val canComplete =
        BookPermissions.has(
            currentBook,
            systemRole,
            BookPermissions.PURCHASE_CREATE
        ) &&
            BookPermissions.has(
                currentBook,
                systemRole,
                BookPermissions.PURCHASE_PLAN_EDIT
            )

    if (!canView) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment =
                Alignment.Center
        ) {
            Text(
                "当前账号没有查看协作采购的权限。",
                color = Color.Gray
            )
        }
        return
    }

    var date by remember {
        mutableStateOf(
            LocalDate.now()
                .toString()
        )
    }

    var refreshVersion by remember {
        mutableIntStateOf(0)
    }

    var syncing by remember {
        mutableStateOf(false)
    }

    var message by remember {
        mutableStateOf("")
    }

    var editingItem by remember {
        mutableStateOf<PurchasePlanItemRecord?>(
            null
        )
    }

    var showAdd by remember {
        mutableStateOf(false)
    }

    var completingItem by remember {
        mutableStateOf<PurchasePlanItemRecord?>(
            null
        )
    }

    val scope =
        rememberCoroutineScope()

    suspend fun refreshCloud() {
        if (
            syncing ||
            session == null ||
            !currentBook.cloudEnabled ||
            currentBook.permission == "REVOKED"
        ) {
            return
        }

        syncing = true

        val result =
            withContext(
                Dispatchers.IO
            ) {
                runCatching {
                    cloudSyncManager
                        .syncCurrentBook(
                            db,
                            currentBook
                        )
                }
            }

        syncing = false

        result
            .onSuccess {
                refreshVersion++
                onChanged()
            }
            .onFailure {
                message =
                    "刷新失败：" +
                        (
                            it.message
                                ?: "未知错误"
                            )
            }
    }

    LaunchedEffect(
        currentBook.id,
        currentBook.cloudEnabled
    ) {
        if (
            session != null &&
            currentBook.cloudEnabled &&
            currentBook.permission != "REVOKED"
        ) {
            while (true) {
                delay(15_000L)
                refreshCloud()
            }
        }
    }

    val plan =
        remember(
            dataVersion,
            refreshVersion,
            date
        ) {
            db.getPurchasePlan(date)
        }

    // Legacy purchase-plan status=2 means cancelled. V1.4.2 keeps the
    // collaboration workflow intentionally simple, so cancelled legacy rows
    // stay hidden instead of reappearing as unfinished purchases.
    val items =
        plan?.items
            .orEmpty()
            .filter {
                it.status != 2
            }

    val plannedAmount =
        items.sumOf {
            it.estimatedAmount
        }

    val completedItems =
        items.filter {
            it.status == 1
        }

    val pendingItems =
        items.filter {
            it.status == 0
        }

    val completedAmount =
        completedItems.sumOf {
            it.actualAmount
        }

    val completedFruitCount =
        completedItems
            .map { it.fruitId }
            .distinct()
            .size

    val pendingFruitCount =
        pendingItems
            .map { it.fruitId }
            .distinct()
            .size

    LazyColumn(
        modifier =
            Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                horizontal = 12.dp,
                vertical = 8.dp
            ),
        verticalArrangement =
            Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically,
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                CompactDateNavigator(
                    label = null,
                    date = date,
                    modifier =
                        Modifier.weight(1f),
                    chineseDisplay = true,
                    showWeekday = true
                ) {
                    date = it
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            refreshCloud()
                        }
                    },
                    enabled = !syncing
                ) {
                    if (syncing) {
                        CircularProgressIndicator(
                            modifier =
                                Modifier.size(15.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("刷新")
                    }
                }
            }
        }

        item {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(7.dp)
            ) {
                Row(
                    horizontalArrangement =
                        Arrangement.spacedBy(7.dp)
                ) {
                    CollaborationMetric(
                        "计划金额",
                        collaborationMoney(
                            plannedAmount
                        ),
                        Modifier.weight(1f)
                    )

                    CollaborationMetric(
                        "已完成金额",
                        collaborationMoney(
                            completedAmount
                        ),
                        Modifier.weight(1f)
                    )
                }

                Row(
                    horizontalArrangement =
                        Arrangement.spacedBy(7.dp)
                ) {
                    CollaborationMetric(
                        "已采购",
                        "$completedFruitCount 种",
                        Modifier.weight(1f)
                    )

                    CollaborationMetric(
                        "剩余",
                        "$pendingFruitCount 种",
                        Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Text(
                    "采购计划",
                    fontWeight =
                        FontWeight.Bold,
                    style =
                        MaterialTheme
                            .typography
                            .titleMedium,
                    modifier =
                        Modifier.weight(1f)
                )

                Text(
                    "${completedItems.size}/${items.size}",
                    color =
                        Color(0xFF13A868),
                    fontWeight =
                        FontWeight.SemiBold
                )
            }
        }

        if (items.isEmpty()) {
            item {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                Color(0xFFF7F8FA)
                        )
                ) {
                    Text(
                        "这一天还没有采购计划。",
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                        color = Color.Gray
                    )
                }
            }
        } else {
            items(
                items,
                key = {
                    "collab_plan_${it.id}"
                }
            ) {
                item ->
                CollaborationPlanRow(
                    item = item,
                    canEditPlan =
                        canEditPlan,
                    canComplete =
                        canComplete,
                    onEdit = {
                        editingItem = item
                    },
                    onDelete = {
                        if (
                            db.deleteCollaborationPlanItem(
                                item.id
                            )
                        ) {
                            message =
                                "${item.fruitName} 已从采购计划移除"
                            onChanged()
                        } else {
                            message =
                                "只有未完成项目可以移除"
                        }
                    },
                    onComplete = {
                        completingItem =
                            item
                    }
                )
            }
        }

        if (canEditPlan) {
            item {
                OutlinedButton(
                    onClick = {
                        showAdd = true
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                ) {
                    Text("＋ 添加水果")
                }
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
                            message.startsWith(
                                "失败"
                            ) ||
                            message.contains(
                                "失败"
                            )
                        ) {
                            MaterialTheme
                                .colorScheme
                                .error
                        } else {
                            Color(0xFF13A868)
                        },
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )
            }
        }

        item {
            Text(
                "只有点击“完成采购”并确认实际数量、金额后，才会生成正式进货记录。",
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color = Color.Gray
            )
        }
    }

    if (
        showAdd ||
        editingItem != null
    ) {
        CollaborationPlanEditDialog(
            db = db,
            date = date,
            editing =
                editingItem,
            onDismiss = {
                showAdd = false
                editingItem = null
            },
            onSaved = {
                showAdd = false
                editingItem = null
                message = it
                onChanged()
            }
        )
    }

    completingItem?.let {
        item ->
        CollaborationCompleteDialog(
            db = db,
            item = item,
            recorderUsername =
                session?.username
                    .orEmpty(),
            recorderDisplayName =
                session?.displayName
                    .orEmpty(),
            onDismiss = {
                completingItem = null
            },
            onCompleted = {
                completingItem = null
                message = it
                onChanged()
                scope.launch {
                    refreshCloud()
                }
            }
        )
    }
}

@Composable
private fun CollaborationMetric(
    label: String,
    value: String,
    modifier: Modifier
) {
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color(0xFFF3FAF6)
            )
    ) {
        Column(
            Modifier.padding(
                horizontal = 12.dp,
                vertical = 9.dp
            )
        ) {
            Text(
                label,
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color = Color.Gray
            )

            Text(
                value,
                fontSize = 21.sp,
                fontWeight =
                    FontWeight.Bold,
                color =
                    Color(0xFF13A868)
            )
        }
    }
}

@Composable
private fun CollaborationPlanRow(
    item: PurchasePlanItemRecord,
    canEditPlan: Boolean,
    canComplete: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onComplete: () -> Unit
) {
    val completed =
        item.status == 1

    Card(
        modifier =
            Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (completed) {
                        Color(0xFFF2F8F4)
                    } else {
                        Color.White
                    }
            )
    ) {
        Row(
            Modifier.padding(
                horizontal = 11.dp,
                vertical = 8.dp
            ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            Text(
                if (completed) {
                    "✓"
                } else {
                    "○"
                },
                color =
                    if (completed) {
                        Color(0xFF13A868)
                    } else {
                        Color.Gray
                    },
                fontSize = 20.sp,
                fontWeight =
                    FontWeight.Bold,
                modifier =
                    Modifier.width(28.dp)
            )

            Column(
                Modifier.weight(1f)
            ) {
                Text(
                    item.fruitName,
                    fontWeight =
                        FontWeight.SemiBold
                )

                if (completed) {
                    Text(
                        "实际 ${collaborationNumber(item.actualQuantity)}${item.unit}" +
                            " · ${collaborationMoney(item.actualAmount)}" +
                            if (
                                item.buyerName.isNotBlank()
                            ) {
                                " · ${item.buyerName}"
                            } else {
                                ""
                            },
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            Color.DarkGray
                    )

                    if (
                        item.completedAt > 0L
                    ) {
                        Text(
                            "完成 ${collaborationTime(item.completedAt)}" +
                                if (
                                    item.completedByDisplayName
                                        .isNotBlank() &&
                                    item.completedByDisplayName !=
                                    item.buyerName
                                ) {
                                    " · 录入 ${item.completedByDisplayName}"
                                } else {
                                    ""
                                },
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,
                            color = Color.Gray
                        )
                    }
                } else {
                    Text(
                        "计划 ${collaborationNumber(item.quantity)}${item.unit}" +
                            if (
                                item.estimatedAmount > 0
                            ) {
                                " · 预计 ${collaborationMoney(item.estimatedAmount)}"
                            } else {
                                " · 预计金额未填"
                            },
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color = Color.Gray
                    )
                }

                if (
                    item.remark.isNotBlank()
                ) {
                    Text(
                        item.remark,
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                        color = Color.Gray
                    )
                }
            }

            if (!completed) {
                if (canEditPlan) {
                    TextButton(
                        onClick =
                            onEdit,
                        contentPadding =
                            PaddingValues(
                                horizontal = 7.dp
                            )
                    ) {
                        Text("修改")
                    }
                }

                if (canComplete) {
                    Button(
                        onClick =
                            onComplete,
                        contentPadding =
                            PaddingValues(
                                horizontal = 10.dp,
                                vertical = 2.dp
                            ),
                        modifier =
                            Modifier.height(36.dp)
                    ) {
                        Text(
                            "完成采购",
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        if (
            !completed &&
            canEditPlan
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        end = 8.dp,
                        bottom = 2.dp
                    ),
                horizontalArrangement =
                    Arrangement.End
            ) {
                TextButton(
                    onClick =
                        onDelete,
                    contentPadding =
                        PaddingValues(
                            horizontal = 8.dp
                        )
                ) {
                    Text(
                        "移除",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CollaborationPlanEditDialog(
    db: AppDatabase,
    date: String,
    editing: PurchasePlanItemRecord?,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
    val fruits =
        remember {
            db.getFruits()
        }

    var fruitId by remember(
        editing?.id
    ) {
        mutableStateOf(
            editing?.fruitId
        )
    }

    var fruitMenu by remember {
        mutableStateOf(false)
    }

    var quantity by remember(
        editing?.id
    ) {
        mutableStateOf(
            editing
                ?.quantity
                ?.let {
                    collaborationNumber(
                        it
                    )
                }
                .orEmpty()
        )
    }

    var unit by remember(
        editing?.id
    ) {
        mutableStateOf(
            editing?.unit
                ?: "件"
        )
    }

    var unitMenu by remember {
        mutableStateOf(false)
    }

    var estimatedAmount by remember(
        editing?.id
    ) {
        mutableStateOf(
            editing
                ?.estimatedAmount
                ?.takeIf {
                    it > 0
                }
                ?.let {
                    collaborationNumber(
                        it
                    )
                }
                .orEmpty()
        )
    }

    var remark by remember(
        editing?.id
    ) {
        mutableStateOf(
            editing?.remark
                .orEmpty()
        )
    }

    var error by remember {
        mutableStateOf("")
    }

    val fruit =
        fruits.firstOrNull {
            it.id ==
                fruitId
        }

    AlertDialog(
        onDismissRequest =
            onDismiss,
        title = {
            Text(
                if (
                    editing == null
                ) {
                    "添加采购水果"
                } else {
                    "修改采购计划"
                }
            )
        },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        8.dp
                    )
            ) {
                Box {
                    OutlinedButton(
                        onClick = {
                            fruitMenu =
                                true
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            fruit?.name
                                ?: "选择水果"
                        )
                    }

                    DropdownMenu(
                        expanded =
                            fruitMenu,
                        onDismissRequest = {
                            fruitMenu =
                                false
                        }
                    ) {
                        fruits.forEach {
                            f ->
                            DropdownMenuItem(
                                text = {
                                    Text(f.name)
                                },
                                onClick = {
                                    fruitId =
                                        f.id
                                    unit =
                                        f.defaultUnit
                                    fruitMenu =
                                        false
                                }
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            7.dp
                        )
                ) {
                    CollaborationNumberField(
                        "计划数量",
                        quantity,
                        {
                            quantity = it
                        },
                        Modifier.weight(1f)
                    )

                    Box(
                        Modifier.width(92.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                unitMenu =
                                    true
                            },
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {
                            Text(unit)
                        }

                        DropdownMenu(
                            expanded =
                                unitMenu,
                            onDismissRequest = {
                                unitMenu =
                                    false
                            }
                        ) {
                            listOf(
                                "斤",
                                "筐",
                                "箱",
                                "件"
                            ).forEach {
                                value ->
                                DropdownMenuItem(
                                    text = {
                                        Text(value)
                                    },
                                    onClick = {
                                        unit =
                                            value
                                        unitMenu =
                                            false
                                    }
                                )
                            }
                        }
                    }
                }

                CollaborationNumberField(
                    "计划金额",
                    estimatedAmount,
                    {
                        estimatedAmount =
                            it
                    },
                    Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = remark,
                    onValueChange = {
                        remark = it
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    label = {
                        Text("备注（可选）")
                    },
                    maxLines = 2
                )

                if (
                    error.isNotBlank()
                ) {
                    Text(
                        error,
                        color =
                            MaterialTheme
                                .colorScheme
                                .error,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selectedFruit =
                        fruit
                    val qty =
                        quantity
                            .toDoubleOrNull()
                            ?: 0.0
                    val estimate =
                        estimatedAmount
                            .toDoubleOrNull()
                            ?: 0.0

                    if (
                        selectedFruit == null
                    ) {
                        error =
                            "请选择水果"
                    } else if (
                        qty <= 0
                    ) {
                        error =
                            "计划数量必须大于0"
                    } else {
                        val id =
                            db.upsertCollaborationPlanItem(
                                date = date,
                                itemId =
                                    editing?.id,
                                fruit =
                                    selectedFruit,
                                quantity =
                                    qty,
                                unit = unit,
                                estimatedAmount =
                                    estimate,
                                remark =
                                    remark
                            )

                        if (
                            id > 0
                        ) {
                            onSaved(
                                if (
                                    editing == null
                                ) {
                                    "${selectedFruit.name} 已加入采购计划"
                                } else {
                                    "${selectedFruit.name} 计划已修改"
                                }
                            )
                        } else {
                            error =
                                "保存失败，请检查内容"
                        }
                    }
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(
                onClick =
                    onDismiss
            ) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun CollaborationCompleteDialog(
    db: AppDatabase,
    item: PurchasePlanItemRecord,
    recorderUsername: String,
    recorderDisplayName: String,
    onDismiss: () -> Unit,
    onCompleted: (String) -> Unit
) {
    val partners =
        remember {
            db.getPartners()
        }

    var buyerId by remember(
        item.id
    ) {
        mutableStateOf(
            partners.firstOrNull()
                ?.id
        )
    }

    var buyerMenu by remember {
        mutableStateOf(false)
    }

    var quantity by remember(
        item.id
    ) {
        mutableStateOf(
            collaborationNumber(
                item.quantity
            )
        )
    }

    var amount by remember(
        item.id
    ) {
        mutableStateOf(
            item.estimatedAmount
                .takeIf {
                    it > 0
                }
                ?.let {
                    collaborationNumber(
                        it
                    )
                }
                .orEmpty()
        )
    }

    var error by remember {
        mutableStateOf("")
    }

    val buyer =
        partners.firstOrNull {
            it.id ==
                buyerId
        }

    val quantityNumber =
        quantity.toDoubleOrNull()
            ?: 0.0

    val amountNumber =
        amount.toDoubleOrNull()
            ?: 0.0

    val unitPrice =
        if (
            quantityNumber > 0 &&
            amountNumber > 0
        ) {
            amountNumber /
                quantityNumber
        } else {
            0.0
        }

    AlertDialog(
        onDismissRequest =
            onDismiss,
        title = {
            Text("完成采购")
        },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        8.dp
                    )
            ) {
                Text(
                    item.fruitName,
                    fontWeight =
                        FontWeight.Bold,
                    fontSize = 18.sp
                )

                Row(
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            7.dp
                        )
                ) {
                    CollaborationNumberField(
                        "实际数量",
                        quantity,
                        {
                            quantity = it
                        },
                        Modifier.weight(1f)
                    )

                    CollaborationNumberField(
                        "实际总价",
                        amount,
                        {
                            amount = it
                        },
                        Modifier.weight(1f)
                    )
                }

                Text(
                    if (
                        unitPrice > 0
                    ) {
                        "单价 ${collaborationMoney(unitPrice)}/${item.unit}"
                    } else {
                        "单价 —"
                    },
                    color = Color.Gray
                )

                Box {
                    OutlinedButton(
                        onClick = {
                            buyerMenu =
                                true
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "进货人：" +
                                (
                                    buyer?.name
                                        ?: "请选择"
                                    )
                        )
                    }

                    DropdownMenu(
                        expanded =
                            buyerMenu,
                        onDismissRequest = {
                            buyerMenu =
                                false
                        }
                    ) {
                        partners.forEach {
                            partner ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        partner.name
                                    )
                                },
                                onClick = {
                                    buyerId =
                                        partner.id
                                    buyerMenu =
                                        false
                                }
                            )
                        }
                    }
                }

                Text(
                    "确认后会直接生成正式进货记录，并计入当天进货金额。",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color = Color.Gray
                )

                if (
                    error.isNotBlank()
                ) {
                    Text(
                        error,
                        color =
                            MaterialTheme
                                .colorScheme
                                .error,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selectedBuyer =
                        buyer

                    if (
                        selectedBuyer == null
                    ) {
                        error =
                            "请选择进货人"
                    } else if (
                        quantityNumber <= 0 ||
                        amountNumber <= 0
                    ) {
                        error =
                            "实际数量和总价必须大于0"
                    } else {
                        val result =
                            db.completeCollaborationPlanItem(
                                itemId =
                                    item.id,
                                buyer =
                                    selectedBuyer,
                                actualQuantity =
                                    quantityNumber,
                                actualAmount =
                                    amountNumber,
                                recorderUsername =
                                    recorderUsername,
                                recorderDisplayName =
                                    recorderDisplayName
                            )

                        if (
                            result.success
                        ) {
                            onCompleted(
                                result.message
                            )
                        } else {
                            error =
                                result.message
                        }
                    }
                }
            ) {
                Text("确认完成")
            }
        },
        dismissButton = {
            TextButton(
                onClick =
                    onDismiss
            ) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun CollaborationNumberField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = {
            if (
                it.matches(
                    Regex(
                        "^\\d*(\\.\\d{0,2})?$"
                    )
                )
            ) {
                onValue(it)
            }
        },
        modifier = modifier,
        label = {
            Text(label)
        },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    KeyboardType.Decimal
            )
    )
}

private fun collaborationMoney(
    value: Double
): String =
    "¥" +
        String.format(
            Locale.CHINA,
            "%.2f",
            value
        )
            .trimEnd('0')
            .trimEnd('.')

private fun collaborationNumber(
    value: Double
): String =
    if (
        kotlin.math.abs(
            value -
                value.toLong()
        ) <
        0.000001
    ) {
        value.toLong()
            .toString()
    } else {
        String.format(
            Locale.CHINA,
            "%.2f",
            value
        )
            .trimEnd('0')
            .trimEnd('.')
    }

private fun collaborationTime(
    epoch: Long
): String =
    runCatching {
        Instant
            .ofEpochMilli(
                epoch
            )
            .atZone(
                ZoneId.systemDefault()
            )
            .format(
                DateTimeFormatter
                    .ofPattern(
                        "HH:mm",
                        Locale.CHINA
                    )
            )
    }.getOrDefault(
        ""
    )
