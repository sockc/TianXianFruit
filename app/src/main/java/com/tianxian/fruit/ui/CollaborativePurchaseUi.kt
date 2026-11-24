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

private enum class CollaborationPendingFilter { ALL, MINE, UNASSIGNED }

@Composable
private fun CollaborationCompactMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF13A868))
    }
}

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


    // Filtering operates on local buyer names only. Cross-book invitations
    // require an authenticated server authorization scope and are not emulated here.
    var pendingFilter by remember(currentBook.id) {
        mutableStateOf(CollaborationPendingFilter.ALL)
    }
    var completedExpanded by remember(currentBook.id, date) { mutableStateOf(false) }
    var inlineCompletionId by remember(currentBook.id, date) { mutableStateOf<Long?>(null) }
    var showCollaborationInfo by remember { mutableStateOf(false) }
    val selfNames = listOf(session?.displayName.orEmpty(), session?.username.orEmpty())
        .map { it.trim() }.filter { it.isNotBlank() }
    val visiblePending = pendingItems.filter { item ->
        when (pendingFilter) {
            CollaborationPendingFilter.ALL -> true
            CollaborationPendingFilter.MINE ->
                selfNames.any { it.equals(item.buyerName, ignoreCase = true) }
            CollaborationPendingFilter.UNASSIGNED -> item.buyerName.isBlank()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CompactDateNavigator(
                    label = null,
                    date = date,
                    modifier = Modifier.weight(1f),
                    chineseDisplay = true,
                    showWeekday = true
                ) { date = it }
                TextButton(
                    onClick = { scope.launch { refreshCloud() } },
                    enabled = !syncing && session != null && currentBook.cloudEnabled
                ) {
                    Text(if (syncing) "同步中" else "同步", fontSize = 12.sp)
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3FAF6))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CollaborationCompactMetric(
                        "已采购", collaborationMoney(completedAmount), Modifier.weight(1f)
                    )
                    CollaborationCompactMetric(
                        "计划金额", collaborationMoney(plannedAmount), Modifier.weight(1f)
                    )
                    CollaborationCompactMetric(
                        "完成进度", "${completedItems.size}/${items.size}", Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("待采购", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${pendingItems.size} 项", color = Color(0xFF13A868))
                TextButton(onClick = { showCollaborationInfo = true }) {
                    Text("协作说明", fontSize = 12.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = pendingFilter == CollaborationPendingFilter.ALL,
                    onClick = { pendingFilter = CollaborationPendingFilter.ALL },
                    label = { Text("全部", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = pendingFilter == CollaborationPendingFilter.MINE,
                    onClick = { pendingFilter = CollaborationPendingFilter.MINE },
                    label = { Text("我的采购", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = pendingFilter == CollaborationPendingFilter.UNASSIGNED,
                    onClick = { pendingFilter = CollaborationPendingFilter.UNASSIGNED },
                    label = { Text("未分配", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (items.isEmpty()) {
            item {
                Text(
                    "这一天还没有采购计划，可在下方添加商品。",
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    color = Color.Gray
                )
            }
        } else if (pendingItems.isEmpty()) {
            item {
                Text(
                    "✓ 当天采购已全部完成",
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    color = Color(0xFF13A868),
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else if (visiblePending.isEmpty()) {
            item {
                Text(
                    if (pendingFilter == CollaborationPendingFilter.MINE) {
                        "没有匹配当前账号姓名的待采购商品，可切换“全部”查看。"
                    } else {
                        "暂无未分配采购人的商品。"
                    },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    color = Color.Gray
                )
            }
        } else {
            items(visiblePending, key = { "collab_pending_${it.id}" }) { item ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
                            inlineCompletionId = if (inlineCompletionId == item.id) null else item.id
                        },
                        onEditCompleted = {},
                        onRestoreCompleted = {},
                        onDeleteCompleted = {}
                    )
                    if (inlineCompletionId == item.id) {
                        CollaborationInlineCompletionEditor(
                            db = db,
                            item = item,
                            recorderUsername = session?.username.orEmpty(),
                            recorderDisplayName = session?.displayName.orEmpty(),
                            onCancel = { inlineCompletionId = null },
                            onCompleted = {
                                inlineCompletionId = null
                                message = it
                                onChanged()
                                scope.launch { refreshCloud() }
                            }
                        )
                    }
                }
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
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已采购（${completedItems.size}）",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        collaborationMoney(completedAmount),
                        color = Color(0xFF13A868),
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { completedExpanded = !completedExpanded }) {
                        Text(if (completedExpanded) "收起 ∧" else "展开 ∨")
                    }
                }
            }
            if (completedExpanded) {
                val completedByBuyer = completedItems.groupBy { it.buyerId to it.buyerName }
                completedByBuyer.entries.forEachIndexed { index, entry ->
                    item(key = "collab_completed_buyer_${entry.key.first}_$index") {
                        CollaborationCompletedBuyerCard(
                            buyerName = entry.key.second.ifBlank { "未指定采购人" },
                            items = entry.value,
                            canOpen = canEditCompleted || canDeleteCompleted,
                            onItemClick = { completedActionItem = it }
                        )
                    }
                }
            }
        }

        if (message.isNotBlank()) {
            item {
                Text(
                    message,
                    color = if (message.contains("失败")) MaterialTheme.colorScheme.error
                        else Color(0xFF13A868),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (showCollaborationInfo) {
        AlertDialog(
            onDismissRequest = { showCollaborationInfo = false },
            title = { Text("协同采购") },
            text = {
                Text(
                    "当前清单属于本账本，已有采购权限的账本成员可共同填写。" +
                    "跨账本账号邀请需要服务端建立独立采购授权，" +
                    "在权限隔离和防重复写入完成之前不会开放，以免外部人员看到经营、利润及结算数据。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showCollaborationInfo = false }) {
                    Text("知道了")
                }
            }
        )
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
    val completed = item.status == 1
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (completed) Color(0xFFF2F8F4) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item.fruitName,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    item.buyerName.ifBlank { "未分配采购人" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.buyerName.isBlank()) Color.Gray else Color(0xFF13A868)
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (completed) "实购 ${collaborationNumber(item.actualQuantity)}${item.unit}"
                    else "计划 ${collaborationNumber(item.quantity)}${item.unit}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (completed) collaborationMoney(item.actualAmount)
                    else "预计 ${collaborationMoney(item.estimatedAmount)}",
                    color = if (completed) Color(0xFF13A868) else Color.DarkGray,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            if (item.remark.isNotBlank()) {
                Text(
                    item.remark,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!completed && canEditPlan) {
                    TextButton(onClick = onEdit) { Text("修改计划", fontSize = 12.sp) }
                    TextButton(onClick = onDelete) { Text("移除", fontSize = 12.sp, color = Color.Gray) }
                }
                if (!completed && canComplete) {
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onComplete,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.height(36.dp)
                    ) { Text("填写实购", fontSize = 12.sp) }
                }
                if (completed && canEditCompleted) {
                    TextButton(onClick = onEditCompleted) { Text("修改", fontSize = 12.sp) }
                }
                if (completed && canDeleteCompleted) {
                    TextButton(onClick = onRestoreCompleted) { Text("恢复未完成", fontSize = 12.sp) }
                    TextButton(onClick = onDeleteCompleted) {
                        Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
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

    val initialQuantity =
        if (editingCompleted && item.actualQuantity > 0) {
            item.actualQuantity
        } else {
            item.quantity
        }
    val initialAmount =
        if (editingCompleted) {
            item.actualAmount
        } else {
            item.estimatedAmount
        }
    val initialUnitPrice =
        when {
            initialQuantity > 0 -> initialAmount / initialQuantity
            referenceUnitPrice != null -> referenceUnitPrice.coerceAtLeast(0.0)
            else -> 0.0
        }

    var quantity by remember(
        item.id,
        editingCompleted
    ) {
        mutableStateOf(
            collaborationNumber(initialQuantity)
        )
    }

    var unitWeightInput by remember(
        item.id,
        editingCompleted
    ) {
        mutableStateOf(
            collaborationNumber(item.unitWeightJin.coerceAtLeast(0.0))
        )
    }

    var unitPriceInput by remember(
        item.id,
        editingCompleted
    ) {
        mutableStateOf(
            collaborationNumber(initialUnitPrice)
        )
    }

    var amount by remember(
        item.id,
        editingCompleted
    ) {
        mutableStateOf(
            collaborationNumber(initialAmount)
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

    val unitWeightParsed =
        unitWeightInput.toDoubleOrNull()

    val unitWeightNumber =
        unitWeightParsed ?: 0.0

    val amountParsed =
        amount.toDoubleOrNull()

    val amountNumber =
        amountParsed ?: 0.0

    val unitPriceParsed =
        unitPriceInput.toDoubleOrNull()

    val unitPrice =
        unitPriceParsed ?: 0.0

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
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    CollaborationNumberField(
                        "实际数量(${item.unit})",
                        quantity,
                        { value ->
                            quantity = value
                            val q = value.toDoubleOrNull()
                            val p = unitPriceInput.toDoubleOrNull()
                            if (q != null && q > 0 && p != null && p >= 0) {
                                amount = collaborationNumber(q * p)
                            }
                        },
                        Modifier.weight(1f)
                    )

                    CollaborationNumberField(
                        "每件重量(斤/${item.unit})",
                        unitWeightInput,
                        { value -> unitWeightInput = value },
                        Modifier.weight(1f)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    CollaborationNumberField(
                        "实际单价",
                        unitPriceInput,
                        { value ->
                            unitPriceInput = value
                            val q = quantity.toDoubleOrNull()
                            val p = value.toDoubleOrNull()
                            if (q != null && q > 0 && p != null && p >= 0) {
                                amount = collaborationNumber(q * p)
                            }
                        },
                        Modifier.weight(1f)
                    )

                    CollaborationNumberField(
                        "实际总价",
                        amount,
                        { value ->
                            amount = value
                            val q = quantity.toDoubleOrNull()
                            val total = value.toDoubleOrNull()
                            if (q != null && q > 0 && total != null && total >= 0) {
                                unitPriceInput = collaborationNumber(total / q)
                            }
                        },
                        Modifier.weight(1f)
                    )
                }

                Text(
                    when {
                        rememberedUnitPrice != null ->
                            "计划总价 ${collaborationMoney(item.estimatedAmount)} · 参考单价 ${collaborationMoney(rememberedUnitPrice)}/${item.unit}"

                        else ->
                            "计划总价 ${collaborationMoney(item.estimatedAmount)}"
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
                        "可修改当前批次的数量、每件重量、单价和总价；价格双向联动。保存后同步更新当天采购与商品历史。"
                    } else {
                        "每件重量会跟随当前批次保存；实际单价和总价双向联动，不会反向修改其它历史批次。"
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
                        unitWeightParsed == null ||
                        unitWeightNumber < 0 ||
                        unitPriceParsed == null ||
                        unitPrice < 0 ||
                        amountParsed == null ||
                        amountNumber < 0
                    ) {
                        error =
                            "实际数量必须大于0；每件重量、实际单价和总价可为0但不能留空"
                    } else {
                        val result =
                            if (editingCompleted) {
                                db.updateCompletedCollaborationPlanItem(
                                    itemId = item.id,
                                    buyer = selectedBuyer,
                                    actualQuantity = quantityNumber,
                                    actualAmount = amountNumber,
                                    actualUnitWeightJin = unitWeightNumber,
                                    recorderUsername = recorderUsername,
                                    recorderDisplayName = recorderDisplayName
                                )
                            } else {
                                db.completeCollaborationPlanItem(
                                    itemId = item.id,
                                    buyer = selectedBuyer,
                                    actualQuantity = quantityNumber,
                                    actualAmount = amountNumber,
                                    actualUnitWeightJin = unitWeightNumber,
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
