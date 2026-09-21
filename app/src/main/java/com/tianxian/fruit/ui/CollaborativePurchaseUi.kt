package com.tianxian.fruit.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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

    val canEditCompleted =
        BookPermissions.has(
            currentBook,
            systemRole,
            BookPermissions.PURCHASE_EDIT
        ) &&
            BookPermissions.has(
                currentBook,
                systemRole,
                BookPermissions.PURCHASE_PLAN_EDIT
            )

    val canDeleteCompleted =
        BookPermissions.has(
            currentBook,
            systemRole,
            BookPermissions.PURCHASE_DELETE
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

    var editingCompletedPurchase by remember {
        mutableStateOf(false)
    }

    var restoringItem by remember {
        mutableStateOf<PurchasePlanItemRecord?>(null)
    }

    var deletingCompletedItem by remember {
        mutableStateOf<PurchasePlanItemRecord?>(null)
    }

    var completedActionItem by remember {
        mutableStateOf<PurchasePlanItemRecord?>(null)
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "待采购",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${pendingItems.size} 种",
                    color = Color(0xFF13A868),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (items.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F8FA))
                ) {
                    Text(
                        "这一天还没有采购计划。",
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        color = Color.Gray
                    )
                }
            }
        } else if (pendingItems.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F8F4))
                ) {
                    Text(
                        "✓ 当天采购已全部完成",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        color = Color(0xFF13A868),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            items(
                pendingItems,
                key = { "collab_pending_${it.id}" }
            ) { item ->
                CollaborationPlanRow(
                    item = item,
                    canEditPlan = canEditPlan,
                    canComplete = canComplete,
                    canEditCompleted = false,
                    canDeleteCompleted = false,
                    onEdit = { editingItem = item },
                    onDelete = {
                        if (db.deleteCollaborationPlanItem(item.id)) {
                            message = "${item.fruitName} 已从采购计划移除"
                            onChanged()
                        } else {
                            message = "只有未完成项目可以移除"
                        }
                    },
                    onComplete = {
                        editingCompletedPurchase = false
                        completingItem = item
                    },
                    onEditCompleted = {},
                    onRestoreCompleted = {},
                    onDeleteCompleted = {}
                )
            }
        }

        if (canEditPlan) {
            item {
                OutlinedButton(
                    onClick = { showAdd = true },
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                ) {
                    Text("＋ 添加商品")
                }
            }
        }

        if (completedItems.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已完成采购",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${completedItems.size} 种 · ${collaborationMoney(completedAmount)}",
                        color = Color(0xFF13A868),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            val completedByBuyer =
                completedItems.groupBy { item -> item.buyerId to item.buyerName }

            completedByBuyer.entries.forEachIndexed { index, entry ->
                item(
                    key = "collab_completed_buyer_${entry.key.first}_$index"
                ) {
                    CollaborationCompletedBuyerCard(
                        buyerName = entry.key.second.ifBlank { "未指定采购人" },
                        items = entry.value,
                        canOpen = canEditCompleted || canDeleteCompleted,
                        onItemClick = { completedActionItem = it }
                    )
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
                "只有点击“完成采购”并确认实际数量、金额后，才会生成正式采购记录。",
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color = Color.Gray
            )
        }
    }

    completedActionItem?.let { item ->
        AlertDialog(
            onDismissRequest = { completedActionItem = null },
            title = { Text(item.fruitName) },
            text = {
                Text(
                    "${collaborationNumber(item.actualQuantity)}${item.unit} · " +
                        "${collaborationMoney(item.actualAmount)}" +
                        if (item.buyerName.isNotBlank()) " · ${item.buyerName}" else ""
                )
            },
            confirmButton = {
                Row {
                    if (canEditCompleted) {
                        TextButton(
                            onClick = {
                                completedActionItem = null
                                editingCompletedPurchase = true
                                completingItem = item
                            }
                        ) { Text("修改") }
                    }
                    if (canDeleteCompleted) {
                        TextButton(
                            onClick = {
                                completedActionItem = null
                                restoringItem = item
                            }
                        ) { Text("恢复未完成") }
                        TextButton(
                            onClick = {
                                completedActionItem = null
                                deletingCompletedItem = item
                            }
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { completedActionItem = null }) {
                    Text("关闭")
                }
            }
        )
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
            editingCompleted = editingCompletedPurchase,
            recorderUsername =
                session?.username
                    .orEmpty(),
            recorderDisplayName =
                session?.displayName
                    .orEmpty(),
            onDismiss = {
                completingItem = null
                editingCompletedPurchase = false
            },
            onCompleted = {
                completingItem = null
                editingCompletedPurchase = false
                message = it
                onChanged()
                scope.launch {
                    refreshCloud()
                }
            }
        )
    }

    restoringItem?.let { item ->
        CollaborationActionConfirmDialog(
            title = "恢复为未完成？",
            message = "${item.fruitName} 将重新回到待采购状态，对应正式采购记录会同步移除。",
            confirmText = "恢复未完成",
            onDismiss = { restoringItem = null },
            onConfirm = {
                val result = db.restoreCompletedCollaborationPlanItem(item.id)
                restoringItem = null
                message = result.message
                if (result.success) {
                    onChanged()
                    scope.launch { refreshCloud() }
                }
            }
        )
    }

    deletingCompletedItem?.let { item ->
        CollaborationActionConfirmDialog(
            title = "删除已完成采购？",
            message = "${item.fruitName} 的协作记录和对应正式采购记录都会删除，并重新计算当天进货金额。",
            confirmText = "确认删除",
            destructive = true,
            onDismiss = { deletingCompletedItem = null },
            onConfirm = {
                val result = db.deleteCompletedCollaborationPlanItem(item.id)
                deletingCompletedItem = null
                message = result.message
                if (result.success) {
                    onChanged()
                    scope.launch { refreshCloud() }
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
private fun CollaborationCompletedBuyerCard(
    buyerName: String,
    items: List<PurchasePlanItemRecord>,
    canOpen: Boolean,
    onItemClick: (PurchasePlanItemRecord) -> Unit
) {
    val total = items.sumOf { it.actualAmount }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F8F4))
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buyerName,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${items.size} 种 · ${collaborationMoney(total)}",
                    color = Color(0xFF13A868),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            items.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canOpen) { onItemClick(item) }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "✓",
                        color = Color(0xFF13A868),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(22.dp)
                    )
                    Text(
                        item.fruitName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "${collaborationNumber(item.actualQuantity)}${item.unit} · " +
                            collaborationMoney(item.actualAmount) +
                            if (canOpen) "  ›" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                }
            }
        }
    }
}

@Composable
private fun CollaborationPlanRow(
    item: PurchasePlanItemRecord,
    canEditPlan: Boolean,
    canComplete: Boolean,
    canEditCompleted: Boolean,
    canDeleteCompleted: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onComplete: () -> Unit,
    onEditCompleted: () -> Unit,
    onRestoreCompleted: () -> Unit,
    onDeleteCompleted: () -> Unit
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
                            } +
                            if (item.buyerName.isNotBlank()) {
                                " · ${item.buyerName}"
                            } else {
                                " · 未指定采购人"
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

        if (completed && (canEditCompleted || canDeleteCompleted)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (canEditCompleted) {
                    TextButton(
                        onClick = onEditCompleted,
                        contentPadding = PaddingValues(horizontal = 7.dp)
                    ) {
                        Text("修改", fontSize = 12.sp)
                    }
                }

                if (canDeleteCompleted) {
                    TextButton(
                        onClick = onRestoreCompleted,
                        contentPadding = PaddingValues(horizontal = 7.dp)
                    ) {
                        Text("恢复未完成", fontSize = 12.sp)
                    }

                    TextButton(
                        onClick = onDeleteCompleted,
                        contentPadding = PaddingValues(horizontal = 7.dp)
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
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
                ?: fruits.firstOrNull { it.name == "总价" }?.id
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
                    "添加采购商品"
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
                                ?: "总价"
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
                            "请选择商品"
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
                onClick = onDismiss
            ) {
                Text("取消")
            }
        }
    )
}

@Composable
internal fun CollaborationCompleteDialog(
    db: AppDatabase,
    item: PurchasePlanItemRecord,
    editingCompleted: Boolean,
    recorderUsername: String,
    recorderDisplayName: String,
    onDismiss: () -> Unit,
    onCompleted: (String) -> Unit,
    batchMode: Boolean = false,
    onSkip: (() -> Unit)? = null,
    referenceUnitPrice: Double? = null
) {
    val partners =
        remember {
            db.getPartners()
        }

    var buyerId by remember(
        item.id
    ) {
        mutableStateOf(
            item.buyerId
                .takeIf { it > 0L }
                ?: partners.firstOrNull()?.id
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
                if (editingCompleted && item.actualQuantity > 0) {
                    item.actualQuantity
                } else {
                    item.quantity
                }
            )
        )
    }

    var amount by remember(
        item.id
    ) {
        mutableStateOf(
            if (editingCompleted) {
                collaborationNumber(item.actualAmount)
            } else {
                ""
            }
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

    val amountParsed =
        amount.toDoubleOrNull()

    val amountNumber =
        amountParsed ?: 0.0

    val unitPrice =
        if (
            quantityNumber > 0 &&
            amountParsed != null &&
            amountNumber >= 0
        ) {
            amountNumber /
                quantityNumber
        } else {
            0.0
        }

    val rememberedUnitPrice =
        referenceUnitPrice
            ?: if (
                item.quantity > 0 &&
                item.estimatedAmount > 0
            ) {
                item.estimatedAmount / item.quantity
            } else {
                null
            }

    AlertDialog(
        onDismissRequest =
            onDismiss,
        title = {
            Text(
                if (editingCompleted) {
                    "修改已完成采购"
                } else {
                    "完成采购"
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
                    when {
                        amountParsed != null && quantityNumber > 0 ->
                            "单价 ${collaborationMoney(unitPrice)}/${item.unit}"

                        rememberedUnitPrice != null ->
                            "参考单价 ${collaborationMoney(rememberedUnitPrice)}/${item.unit} · 总价请按实际确认"

                        else ->
                            "单价 — · 总价请按实际确认"
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
                            "采购人：" +
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
                    if (editingCompleted) {
                        "保存后会同步修改对应正式采购记录和当天进货金额。"
                    } else {
                        "计划单价只作参考；实际总价不会自动带入，确认后才计入当天进货。"
                    },
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
                            "请选择采购人"
                    } else if (
                        quantityNumber <= 0 ||
                        amountParsed == null ||
                        amountNumber < 0
                    ) {
                        error =
                            "实际数量必须大于0，总价可填写0但不能留空"
                    } else {
                        val result =
                            if (editingCompleted) {
                                db.updateCompletedCollaborationPlanItem(
                                    itemId = item.id,
                                    buyer = selectedBuyer,
                                    actualQuantity = quantityNumber,
                                    actualAmount = amountNumber,
                                    recorderUsername = recorderUsername,
                                    recorderDisplayName = recorderDisplayName
                                )
                            } else {
                                db.completeCollaborationPlanItem(
                                    itemId = item.id,
                                    buyer = selectedBuyer,
                                    actualQuantity = quantityNumber,
                                    actualAmount = amountNumber,
                                    recorderUsername = recorderUsername,
                                    recorderDisplayName = recorderDisplayName
                                )
                            }

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
                Text(
                    when {
                        editingCompleted -> "保存修改"
                        batchMode -> "确认并下一个"
                        else -> "确认完成"
                    }
                )
            }
        },
        dismissButton = {
            Row {
                if (batchMode && onSkip != null) {
                    TextButton(
                        onClick = onSkip
                    ) {
                        Text("跳过")
                    }
                }
                TextButton(
                    onClick = onDismiss
                ) {
                    Text(if (batchMode) "结束" else "取消")
                }
            }
        }
    )
}

@Composable
private fun CollaborationActionConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmText,
                    color =
                        if (destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            BrandActionGreen
                        }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private val BrandActionGreen = Color(0xFF13A868)

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
