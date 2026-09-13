package com.tianxian.fruit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxian.fruit.data.AppDatabase
import com.tianxian.fruit.data.PurchaseDuplicateRecord
import com.tianxian.fruit.data.PurchaseOrderDetail
import com.tianxian.fruit.data.PurchaseTypes
import com.tianxian.fruit.sync.CloudSyncManager
import com.tianxian.fruit.sync.LedgerBook
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private val ActivityGreen =
    Color(
        0xFF13A868
    )

private enum class PurchaseActivityFilter(
    val label: String
) {
    ALL("全部"),
    TEMPORARY("临时采购"),
    MINE("我的采购")
}

@Composable
internal fun PurchaseTypeSelector(
    purchaseType: String,
    onChange: (String) -> Unit
) {
    Column(
        verticalArrangement =
            Arrangement.spacedBy(
                6.dp
            )
    ) {
        Text(
            "采购类型",
            style =
                MaterialTheme
                    .typography
                    .bodySmall,
            color = Color.Gray
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(
                    8.dp
                )
        ) {
            FilterChip(
                selected =
                    purchaseType ==
                        PurchaseTypes.PLANNED,
                onClick = {
                    onChange(
                        PurchaseTypes
                            .PLANNED
                    )
                },
                label = {
                    Text(
                        "计划采购"
                    )
                },
                modifier =
                    Modifier.weight(
                        1f
                    )
            )

            FilterChip(
                selected =
                    purchaseType ==
                        PurchaseTypes.TEMPORARY,
                onClick = {
                    onChange(
                        PurchaseTypes
                            .TEMPORARY
                    )
                },
                label = {
                    Text(
                        "⚡ 临时采购"
                    )
                },
                modifier =
                    Modifier.weight(
                        1f
                    )
            )
        }
    }
}

@Composable
internal fun PurchaseDuplicateDialog(
    rows: List<PurchaseDuplicateRecord>,
    onCancel: () -> Unit,
    onContinue: () -> Unit
) {
    if (rows.isEmpty()) {
        return
    }

    AlertDialog(
        onDismissRequest =
            onCancel,
        title = {
            Text(
                "⚠ 今日已有相同商品采购"
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
                    "下面这些商品今天已经有人拿过。请确认对方采购情况后再决定是否继续。"
                )

                rows.take(
                    8
                ).forEach {
                    row ->
                    Card(
                        colors =
                            CardDefaults
                                .cardColors(
                                    containerColor =
                                        Color(
                                            0xFFFFF8E8
                                        )
                                )
                    ) {
                        Column(
                            Modifier.padding(
                                10.dp
                            )
                        ) {
                            Text(
                                row.fruitName,
                                fontWeight =
                                    FontWeight.Bold
                            )

                            Text(
                                "${row.buyerName} · " +
                                    "${formatActivityTime(row.createdAt)} · " +
                                    PurchaseTypes.label(
                                        row.purchaseType
                                    ),
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color =
                                    Color.Gray
                            )

                            Text(
                                "${cleanActivityNumber(row.quantity)}${row.unit} × " +
                                    "${activityMoney(row.unitPrice)}/${row.unit} · " +
                                    "合计 ${activityMoney(row.totalCost)}",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall
                            )
                        }
                    }
                }

                if (
                    rows.size > 8
                ) {
                    Text(
                        "还有 ${rows.size - 8} 条相同商品采购记录未展开。",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color = Color.Gray
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick =
                    onContinue
            ) {
                Text(
                    "仍然采购"
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick =
                    onCancel
            ) {
                Text(
                    "取消"
                )
            }
        }
    )
}

@Composable
internal fun PurchaseActivityContent(
    db: AppDatabase,
    dataVersion: Int,
    currentBook: LedgerBook,
    cloudSyncManager:
        CloudSyncManager
) {
    val session =
        cloudSyncManager
            .session()

    var date by remember {
        mutableStateOf(
            LocalDate
                .now()
                .toString()
        )
    }

    var filter by remember {
        mutableStateOf(
            PurchaseActivityFilter.ALL
        )
    }

    var remoteVersion by remember {
        mutableIntStateOf(
            0
        )
    }

    var syncText by remember {
        mutableStateOf(
            ""
        )
    }

    var syncing by remember {
        mutableStateOf(
            false
        )
    }

    val manualScope =
        rememberCoroutineScope()

    suspend fun refreshCloud() {
        if (
            session == null ||
            !currentBook.cloudEnabled ||
            currentBook.permission ==
            "REVOKED" ||
            syncing
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

        result
            .onSuccess {
                value ->
                syncText =
                    if (
                        value.downloaded >
                        0
                    ) {
                        "刚刚同步到 ${value.downloaded} 条新变化"
                    } else {
                        "已是最新"
                    }

                remoteVersion++
            }
            .onFailure {
                error ->
                syncText =
                    "刷新失败：" +
                        (
                            error.message
                                ?: "未知错误"
                            )
            }

        syncing = false
    }

    LaunchedEffect(
        currentBook.id,
        currentBook.cloudEnabled
    ) {
        if (
            session != null &&
            currentBook.cloudEnabled &&
            currentBook.permission !=
            "REVOKED"
        ) {
            while (true) {
                delay(
                    15_000L
                )

                refreshCloud()
            }
        }
    }

    val orders =
        remember(
            dataVersion,
            remoteVersion,
            date
        ) {
            db.getPurchaseOrdersForDate(
                date
            )
        }

    val total =
        orders.sumOf {
            it.order.totalCost
        }

    val plannedTotal =
        orders
            .filter {
                it.activity
                    ?.purchaseType !=
                    PurchaseTypes
                        .TEMPORARY
            }
            .sumOf {
                it.order.totalCost
            }

    val temporaryTotal =
        orders
            .filter {
                it.activity
                    ?.purchaseType ==
                    PurchaseTypes
                        .TEMPORARY
            }
            .sumOf {
                it.order.totalCost
            }

    val buyerTotals =
        orders
            .groupBy {
                it.order.buyerName
            }
            .mapValues {
                entry ->
                entry.value
                    .sumOf {
                        it.order.totalCost
                    }
            }
            .toList()
            .sortedByDescending {
                it.second
            }

    val visibleOrders =
        orders.filter {
            detail ->
            when (filter) {
                PurchaseActivityFilter.ALL ->
                    true

                PurchaseActivityFilter.TEMPORARY ->
                    detail.activity
                        ?.purchaseType ==
                        PurchaseTypes
                            .TEMPORARY

                PurchaseActivityFilter.MINE -> {
                    val username =
                        session
                            ?.username
                            .orEmpty()

                    val displayName =
                        session
                            ?.displayName
                            .orEmpty()

                    val recorder =
                        detail.activity
                            ?.recorderUsername
                            .orEmpty()

                    val recorderDisplay =
                        detail.activity
                            ?.recorderDisplayName
                            .orEmpty()

                    recorder.equals(
                        username,
                        ignoreCase = true
                    ) ||
                        recorderDisplay ==
                        displayName
                }
            }
        }

    LazyColumn(
        Modifier.fillMaxSize(),
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
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically,
                horizontalArrangement =
                    Arrangement.spacedBy(
                        8.dp
                    )
            ) {
                CompactDateNavigator(
                    label = null,
                    date = date,
                    modifier =
                        Modifier.weight(
                            1f
                        ),
                    chineseDisplay = true,
                    showWeekday = true
                ) {
                    date = it
                }

                OutlinedButton(
                    onClick = {
                        manualScope.launch {
                            refreshCloud()
                        }
                    },
                    enabled =
                        !syncing &&
                        session != null &&
                        currentBook.cloudEnabled &&
                        currentBook.permission !=
                        "REVOKED"
                ) {
                    if (syncing) {
                        CircularProgressIndicator(
                            Modifier.size(
                                15.dp
                            ),
                            strokeWidth =
                                2.dp
                        )
                    } else {
                        Text(
                            "刷新"
                        )
                    }
                }
            }
        }

        item {
            Card(
                colors =
                    CardDefaults
                        .cardColors(
                            containerColor =
                                Color(
                                    0xFFF4FAF6
                                )
                        )
            ) {
                Column(
                    Modifier.padding(
                        14.dp
                    ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            8.dp
                        )
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment =
                            Alignment.Bottom
                    ) {
                        Column(
                            Modifier.weight(
                                1f
                            )
                        ) {
                            Text(
                                "当日已采购",
                                color =
                                    Color.Gray
                            )
                            Text(
                                activityMoney(
                                    total
                                ),
                                fontSize =
                                    28.sp,
                                fontWeight =
                                    FontWeight.Bold,
                                color =
                                    ActivityGreen
                            )
                        }

                        Text(
                            "${orders.size} 张进货单",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                Color.Gray
                        )
                    }

                    HorizontalDivider()

                    SummaryLine(
                        "计划采购",
                        activityMoney(
                            plannedTotal
                        )
                    )

                    SummaryLine(
                        "临时采购",
                        activityMoney(
                            temporaryTotal
                        )
                    )

                    buyerTotals.forEach {
                        row ->
                        SummaryLine(
                            row.first,
                            activityMoney(
                                row.second
                            )
                        )
                    }

                    if (
                        syncText
                            .isNotBlank()
                    ) {
                        Text(
                            syncText,
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                if (
                                    syncText
                                        .startsWith(
                                            "刷新失败"
                                        )
                                ) {
                                    MaterialTheme
                                        .colorScheme
                                        .error
                                } else {
                                    Color.Gray
                                }
                        )
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(
                        6.dp
                    )
            ) {
                PurchaseActivityFilter
                    .entries
                    .forEach {
                        item ->
                        FilterChip(
                            selected =
                                filter ==
                                    item,
                            onClick = {
                                filter =
                                    item
                            },
                            label = {
                                Text(
                                    item.label
                                )
                            },
                            modifier =
                                Modifier.weight(
                                    1f
                                )
                        )
                    }
            }
        }

        if (
            visibleOrders
                .isEmpty()
        ) {
            item {
                Card(
                    colors =
                        CardDefaults
                            .cardColors(
                                containerColor =
                                    Color(
                                        0xFFF7F7F7
                                    )
                            )
                ) {
                    Text(
                        "当前日期没有符合条件的采购动态。",
                        modifier =
                            Modifier.padding(
                                16.dp
                            ),
                        color =
                            Color.Gray
                    )
                }
            }
        }

        items(
            visibleOrders,
            key = {
                "purchase_activity_${it.order.id}"
            }
        ) {
            detail ->
            PurchaseActivityCard(
                detail
            )
        }

        item {
            Text(
                "采购动态属于当前账本。普通账号只有被加入该云端账本后才能同步查看；VIEWER 可查看，OWNER / EDITOR 可录入。SUPERADMIN 保留系统级跨账本管理权限。",
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun PurchaseActivityCard(
    detail: PurchaseOrderDetail
) {
    val temporary =
        detail.activity
            ?.purchaseType ==
            PurchaseTypes
                .TEMPORARY

    Card(
        Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(
                13.dp
            ),
            verticalArrangement =
                Arrangement.spacedBy(
                    7.dp
                )
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Column(
                    Modifier.weight(
                        1f
                    )
                ) {
                    Text(
                        detail.order
                            .buyerName,
                        fontWeight =
                            FontWeight.Bold,
                        fontSize =
                            16.sp
                    )

                    Text(
                        formatActivityTime(
                            detail.order
                                .createdAt
                        ) +
                            " · " +
                            if (temporary) {
                                "⚡ 临时采购"
                            } else {
                                "计划采购"
                            },
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            if (temporary) {
                                Color(
                                    0xFFE07B00
                                )
                            } else {
                                Color.Gray
                            }
                    )
                }

                Text(
                    activityMoney(
                        detail.order
                            .totalCost
                    ),
                    fontWeight =
                        FontWeight.Bold,
                    color =
                        ActivityGreen
                )
            }

            detail.items
                .forEach {
                    item ->
                    Text(
                        "${item.fruitName}  " +
                            "${cleanActivityNumber(item.quantity)}${item.unit} × " +
                            "${activityMoney(item.unitPrice)}/${item.unit}  ·  " +
                            activityMoney(
                                item.totalCost
                            ),
                        style =
                            MaterialTheme
                                .typography
                                .bodyMedium
                    )
                }

            val recorder =
                detail.activity
                    ?.let {
                        row ->
                        row.recorderDisplayName
                            .ifBlank {
                                row.recorderUsername
                            }
                    }
                    .orEmpty()

            if (
                recorder
                    .isNotBlank() &&
                recorder !=
                detail.order.buyerName
            ) {
                Text(
                    "录入：$recorder",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color = Color.Gray
                )
            }

            if (
                detail.order
                    .remark
                    .isNotBlank()
            ) {
                Text(
                    "备注：${detail.order.remark}",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color =
                        Color.DarkGray
                )
            }
        }
    }
}

@Composable
private fun SummaryLine(
    label: String,
    value: String
) {
    Row(
        Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            Modifier.weight(
                1f
            ),
            color = Color.Gray
        )

        Text(
            value,
            fontWeight =
                FontWeight.SemiBold
        )
    }
}

private fun formatActivityTime(
    epoch: Long
): String {
    if (epoch <= 0L) {
        return "时间未知"
    }

    return runCatching {
        Instant
            .ofEpochMilli(
                epoch
            )
            .atZone(
                ZoneId
                    .systemDefault()
            )
            .format(
                DateTimeFormatter
                    .ofPattern(
                        "HH:mm",
                        Locale.CHINA
                    )
            )
    }.getOrDefault(
        "时间未知"
    )
}

private fun activityMoney(
    value: Double
): String =
    "¥" +
        String.format(
            Locale.CHINA,
            "%.2f",
            value
        )
            .trimEnd(
                '0'
            )
            .trimEnd(
                '.'
            )

private fun cleanActivityNumber(
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
            .trimEnd(
                '0'
            )
            .trimEnd(
                '.'
            )
    }
