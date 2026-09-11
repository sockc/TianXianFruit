package com.tianxian.fruit.ui

import android.app.DatePickerDialog
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxian.fruit.data.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val BrandGreen = Color(0xFF13A868)
private val SoftGreen = Color(0xFFE9F8F0)
private val SoftOrange = Color(0xFFFFF3E3)
private val SoftBlue = Color(0xFFEAF3FF)
private val SoftPurple = Color(0xFFF3ECFF)

enum class AppPage(val title: String, val emoji: String) {
    HOME("首页", "🏠"),
    PURCHASE("进货", "📦"),
    SESSION("营业", "📝"),
    SETTLEMENT("结算", "🧾"),
    MORE("更多", "☰"),
    PLAN("采购", "🛒")
}

private enum class MorePage { MENU, DATA_CENTER, HISTORY, STATS, PARTNERS, STORES, PROFIT, FRUITS }

private enum class HistoryTimeFilter(val label: String) {
    ALL("全部时间"),
    TODAY("今天"),
    YESTERDAY("昨天"),
    LAST_7("近7天"),
    LAST_30("近30天"),
    THIS_MONTH("本月"),
    LAST_MONTH("上月"),
    CUSTOM("自定义")
}

private enum class SettlementView(val label: String) {
    DAY("当日结算"),
    BATCH("批量结算"),
    STATS("结算统计")
}

private data class PurchaseDraftRow(
    val rowId: Long,
    val fruitId: Long? = null,
    val fruitNameSnapshot: String = "",
    val unit: String = "件",
    val quantity: String = "",
    val totalCost: String = ""
) {
    val isBlank: Boolean
        get() = fruitId == null && quantity.isBlank() && totalCost.isBlank()
}


@Composable
fun TianXianApp(db: AppDatabase) {
    var page by remember { mutableStateOf(AppPage.HOME) }
    var moreTarget by remember { mutableStateOf(MorePage.MENU) }
    var dataVersion by remember { mutableIntStateOf(0) }

    BackHandler(enabled = page != AppPage.HOME) { page = AppPage.HOME }

    MaterialTheme(colorScheme = lightColorScheme(primary = BrandGreen, secondary = BrandGreen)) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                NavigationBar {
                    AppPage.entries.filter { it != AppPage.PLAN }.forEach { item ->
                        NavigationBarItem(
                            selected = page == item,
                            onClick = {
                                if (item == AppPage.MORE) moreTarget = MorePage.MENU
                                page = item
                            },
                            icon = { Text(item.emoji) },
                            label = { Text(item.title) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (page) {
                    AppPage.HOME -> HomeScreen(
                        db = db,
                        dataVersion = dataVersion,
                        onPurchase = { page = AppPage.PURCHASE },
                        onPlan = { page = AppPage.PLAN },
                        onSession = { page = AppPage.SESSION },
                        onStores = { moreTarget = MorePage.STORES; page = AppPage.MORE },
                        onHistory = { moreTarget = MorePage.HISTORY; page = AppPage.MORE },
                        onStats = { moreTarget = MorePage.STATS; page = AppPage.MORE },
                        onFruits = { moreTarget = MorePage.FRUITS; page = AppPage.MORE },
                        onMore = { moreTarget = MorePage.MENU; page = AppPage.MORE }
                    )
                    AppPage.PURCHASE -> PurchaseScreen(db, dataVersion) { dataVersion++ }
                    AppPage.PLAN -> PurchasePlanScreen(db, dataVersion) { dataVersion++ }
                    AppPage.SESSION -> SessionScreen(db, dataVersion) { dataVersion++ }
                    AppPage.SETTLEMENT -> SettlementScreen(db, dataVersion) { dataVersion++ }
                    AppPage.MORE -> MoreScreen(
                        db = db,
                        dataVersion = dataVersion,
                        initialSub = moreTarget,
                        onPlan = { page = AppPage.PLAN },
                        onChanged = { dataVersion++ }
                    )
                }
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier, color: Color = SoftGreen, sub: String? = null) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
        }
    }
}

@Composable
private fun HomeScreen(
    db: AppDatabase,
    dataVersion: Int,
    onPurchase: () -> Unit,
    onPlan: () -> Unit,
    onSession: () -> Unit,
    onStores: () -> Unit,
    onHistory: () -> Unit,
    onStats: () -> Unit,
    onFruits: () -> Unit,
    onMore: () -> Unit
) {
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    val selectedDateString = selectedDate.toString()

    val summary = remember(dataVersion, selectedDateString) {
        db.getDailySummary(selectedDateString)
    }
    val records = remember(dataVersion, selectedDateString) {
        db.getDailyRecords(selectedDateString)
    }

    val nextDate = selectedDate.plusDays(1)
    val nextDateString = nextDate.toString()
    val nextPlan = remember(dataVersion, nextDateString) {
        db.getPurchasePlan(nextDateString)
    }

    val trend = remember(dataVersion, selectedDateString) {
        (6 downTo 0).map { offset ->
            val d = selectedDate.minusDays(offset.toLong())
            d to db.getDailySummary(d.toString()).revenue
        }
    }

    val monthRange = remember(selectedDate) {
        selectedDate.withDayOfMonth(1).toString() to
            selectedDate.withDayOfMonth(selectedDate.lengthOfMonth()).toString()
    }
    val rankings = remember(dataVersion, monthRange) {
        db.getRankings(monthRange.first, monthRange.second)
            .sortedByDescending { it.revenue }
    }

    val locationText =
        if (records.isEmpty()) {
            "当日未记录摊位"
        } else {
            records.joinToString("、") { it.storeName }.take(30)
        }

    val isToday = selectedDate == LocalDate.now()
    val nextPlanTitle =
        if (isToday) {
            "明日采购"
        } else {
            "${nextDate.monthValue}月${nextDate.dayOfMonth}日采购"
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF0A6E3A),
                                Color(0xFF148E51),
                                Color(0xFF0B5E34)
                            )
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Column(Modifier.align(Alignment.CenterStart)) {
                    Text(
                        "天鲜果业",
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "每一天努力，收获更甜的生活",
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 13.sp
                    )
                }

                Column(
                    Modifier.align(Alignment.BottomEnd),
                    horizontalAlignment = Alignment.End
                ) {
                    Text("🍇🍊🍓", fontSize = 24.sp)
                    Text(
                        "新鲜水果 · 从这里开始！",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp
                    )
                }

                TextButton(
                    onClick = onMore,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text("⚙", color = Color.White, fontSize = 24.sp)
                }
            }
        }

        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .offset(y = (-12).dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    )
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CompactDateNavigator(
                            label = null,
                            date = selectedDateString,
                            modifier = Modifier.fillMaxWidth(),
                            chineseDisplay = true,
                            showWeekday = true
                        ) {
                            selectedDate =
                                runCatching { LocalDate.parse(it) }
                                    .getOrElse { LocalDate.now() }
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("📍", fontSize = 16.sp)
                            Spacer(Modifier.width(5.dp))
                            Text(
                                locationText,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (records.isEmpty()) Color.Gray else Color.DarkGray
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            DashboardTile(
                                "💰",
                                "营业额",
                                money(summary.revenue),
                                SoftOrange,
                                Modifier.weight(1f)
                            )
                            DashboardTile(
                                "📈",
                                "利润",
                                money(summary.profit),
                                SoftGreen,
                                Modifier.weight(1f)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            DashboardTile(
                                "🛒",
                                "进货成本",
                                money(summary.purchaseCost),
                                SoftBlue,
                                Modifier.weight(1f)
                            )
                            DashboardTile(
                                "📦",
                                "剩余库存价值",
                                money(summary.closingStockValue),
                                Color(0xFFFFF7D9),
                                Modifier.weight(1f)
                            )
                        }

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFF7F9FC)
                            )
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "👥 客户",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${summary.customers} 人",
                                    fontWeight = FontWeight.Bold,
                                    color = BrandGreen
                                )
                                if (records.isNotEmpty()) {
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "新 ${records.sumOf { it.newCustomer }}  ·  " +
                                            "老 ${records.sumOf { it.oldCustomer }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }

                        Text("快捷操作", fontWeight = FontWeight.Bold)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuickActionTile(
                                "🛒",
                                "新增进货",
                                onPurchase,
                                Modifier.weight(1f),
                                Color(0xFFEAF8F0)
                            )
                            QuickActionTile(
                                "🏪",
                                "记录营业",
                                onSession,
                                Modifier.weight(1f),
                                Color(0xFFEAF3FF)
                            )
                            QuickActionTile(
                                "🛒",
                                "采购计划",
                                onPlan,
                                Modifier.weight(1f),
                                Color(0xFFFFEFE5)
                            )
                            QuickActionTile(
                                "🧾",
                                "查看历史",
                                onHistory,
                                Modifier.weight(1f),
                                Color(0xFFF2ECFF)
                            )
                        }

                        Card(
                            onClick = onPlan,
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFF4FAF6)
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 13.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🛒", fontSize = 22.sp)
                                Spacer(Modifier.width(9.dp))

                                Column(Modifier.weight(1f)) {
                                    Text(
                                        nextPlanTitle,
                                        fontWeight = FontWeight.Bold
                                    )

                                    val planText =
                                        if (nextPlan == null) {
                                            "暂无计划，点击添加"
                                        } else {
                                            val pendingCount =
                                                nextPlan.items.count { it.status == 0 }
                                            val purchasedCount =
                                                nextPlan.items.count { it.status == 1 }
                                            val cancelledCount =
                                                nextPlan.items.count { it.status == 2 }

                                            when {
                                                nextPlan.items.isEmpty() ->
                                                    "暂无商品"

                                                pendingCount > 0 ->
                                                    "待采购 $pendingCount · " +
                                                        "已采购 $purchasedCount · " +
                                                        "取消 $cancelledCount"

                                                purchasedCount > 0 ->
                                                    "已完成 $purchasedCount 项" +
                                                        if (cancelledCount > 0) {
                                                            " · 取消 $cancelledCount"
                                                        } else {
                                                            ""
                                                        }

                                                else ->
                                                    "已全部取消"
                                            }
                                        }

                                    Text(
                                        planText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }

                                Text(
                                    "›",
                                    color = BrandGreen,
                                    fontSize = 24.sp
                                )
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFCFCFD)
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "最近7天营业额趋势",
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = onStats) {
                                        Text("查看详情 ›")
                                    }
                                }

                                RevenueTrendChart(trend)

                                Row(Modifier.fillMaxWidth()) {
                                    trend.forEach { (d, revenue) ->
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                "${d.monthValue}/${d.dayOfMonth}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.Gray,
                                                fontSize = 9.sp,
                                                maxLines = 1
                                            )
                                            Text(
                                                fmt(revenue),
                                                fontWeight = FontWeight.SemiBold,
                                                color = BrandGreen,
                                                fontSize = 9.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Text("常用功能", fontWeight = FontWeight.Bold)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            UtilityTile(
                                "📦",
                                "商品管理",
                                onFruits,
                                Modifier.weight(1f)
                            )
                            UtilityTile(
                                "📍",
                                "摊位管理",
                                onStores,
                                Modifier.weight(1f)
                            )
                            UtilityTile(
                                "📊",
                                "经营分析",
                                onStats,
                                Modifier.weight(1f)
                            )
                            UtilityTile(
                                "☁",
                                "数据备份",
                                onStats,
                                Modifier.weight(1f)
                            )
                        }

                        if (rankings.isNotEmpty()) {
                            Text(
                                "${selectedDate.monthValue}月营业额前三",
                                fontWeight = FontWeight.Bold
                            )

                            rankings.take(3).forEachIndexed { index, r ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${index + 1}.",
                                        Modifier.width(28.dp),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        r.storeName,
                                        Modifier.weight(1f)
                                    )
                                    Text(
                                        money(r.revenue),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardTile(icon: String, title: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = color), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 21.sp)
            Spacer(Modifier.width(9.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun QuickActionTile(icon: String, title: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = color), shape = RoundedCornerShape(14.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(icon, fontSize = 22.sp)
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun UtilityTile(icon: String, title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)), shape = RoundedCornerShape(12.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 9.dp, horizontal = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(icon, fontSize = 20.sp)
            Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun RevenueTrendChart(values: List<Pair<LocalDate, Double>>) {
    val maxValue = (values.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 5.dp, vertical = 6.dp)) {
        val left = 8f
        val right = size.width - 8f
        val top = 8f
        val bottom = size.height - 8f

        repeat(3) { i ->
            val y = top + (bottom - top) * i / 2f
            drawLine(Color(0xFFE7E9ED), Offset(left, y), Offset(right, y), strokeWidth = 1.2f)
        }

        if (values.size > 1) {
            val pts = values.mapIndexed { index, item ->
                val x = left + (right - left) * index / (values.size - 1).toFloat()
                val y = bottom - ((item.second / maxValue).toFloat() * (bottom - top))
                Offset(x, y)
            }
            pts.zipWithNext().forEach { (a, b) ->
                drawLine(BrandGreen, a, b, strokeWidth = 4f)
            }
            pts.forEach { p ->
                drawCircle(Color.White, radius = 7f, center = p)
                drawCircle(BrandGreen, radius = 4.5f, center = p)
            }
        }
    }
}

private fun chineseWeekday(date: LocalDate): String = when (date.dayOfWeek.value) {
    1 -> "星期一"; 2 -> "星期二"; 3 -> "星期三"; 4 -> "星期四"
    5 -> "星期五"; 6 -> "星期六"; else -> "星期日"
}

@Composable
private fun PurchasePlanScreen(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    val fruits = remember(dataVersion) { db.getFruits() }

    var fruitId by remember { mutableStateOf<Long?>(null) }
    val fruit = fruits.firstOrNull { it.id == fruitId }
    var fruitMenu by remember { mutableStateOf(false) }
    var unit by remember { mutableStateOf("件") }
    var unitMenu by remember { mutableStateOf(false) }
    var quantity by remember { mutableStateOf("") }
    var itemRemark by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var addFruitDialog by remember { mutableStateOf(false) }
    var deletePlanConfirm by remember { mutableStateOf(false) }

    val draft = remember { mutableStateListOf<PurchasePlanLineInput>() }
    val saved = remember(dataVersion, date) { db.getPurchasePlan(date) }

    LaunchedEffect(dataVersion, date) {
        val current = db.getPurchasePlan(date)
        draft.clear()
        if (current != null) {
            note = current.plan.note
            current.items.forEach { item ->
                val f = fruits.firstOrNull { it.id == item.fruitId }
                    ?: FruitOption(item.fruitId, item.fruitName, item.unit)
                draft.add(
                    PurchasePlanLineInput(
                        fruit = f,
                        quantity = item.quantity,
                        unit = item.unit,
                        remark = item.remark,
                        status = item.status
                    )
                )
            }
        } else {
            note = ""
        }
        fruitId = null
        quantity = ""
        itemRemark = ""
        unit = "件"
        message = ""
    }

    val q = quantity.toDoubleOrNull() ?: 0.0

    val statusSource = saved?.items
    val totalCount = statusSource?.size ?: draft.size
    val pendingCount = statusSource?.count { it.status == 0 } ?: draft.count { it.status == 0 }
    val purchasedCount = statusSource?.count { it.status == 1 } ?: draft.count { it.status == 1 }
    val cancelledCount = statusSource?.count { it.status == 2 } ?: draft.count { it.status == 2 }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PageHeader("采购计划", "每种水果独立管理：待采购、已采购、取消")
        }

        item {
            CompactDateSelector("采购日期", date, Modifier.fillMaxWidth()) { date = it }
        }

        if (totalCount > 0) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F8F6))) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("总计 $totalCount 项", fontWeight = FontWeight.Bold)
                        Text("待采购 $pendingCount", color = Color(0xFF9A6A00))
                        Text("已采购 $purchasedCount", color = BrandGreen)
                        Text("取消 $cancelledCount", color = Color.Gray)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    CompactSelectButton("水果", fruit?.name ?: "请选择水果", Modifier.fillMaxWidth()) {
                        fruitMenu = true
                    }
                    DropdownMenu(expanded = fruitMenu, onDismissRequest = { fruitMenu = false }) {
                        fruits.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f.name) },
                                onClick = {
                                    fruitId = f.id
                                    unit = "件"
                                    fruitMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("＋新增水果") },
                            onClick = {
                                fruitMenu = false
                                addFruitDialog = true
                            }
                        )
                    }
                }

                Box(Modifier.width(82.dp)) {
                    CompactSelectButton("单位", unit, Modifier.fillMaxWidth()) { unitMenu = true }
                    DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                        listOf("斤", "筐", "箱", "件").forEach { u ->
                            DropdownMenuItem(
                                text = { Text(u) },
                                onClick = {
                                    unit = u
                                    unitMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                CompactNumberField("计划数量", quantity, { quantity = it }, Modifier.weight(0.7f))
                CompactTextField("单项备注（可选）", itemRemark, { itemRemark = it }, Modifier.weight(1.3f))
            }
        }

        item {
            Button(
                onClick = {
                    if (fruit == null || q <= 0) {
                        message = "请选择水果并填写正确数量"
                    } else {
                        val existingIndex =
                            draft.indexOfFirst { it.fruit.id == fruit.id && it.unit == unit }
                        val oldStatus =
                            if (existingIndex >= 0) draft[existingIndex].status else 0
                        val newLine = PurchasePlanLineInput(
                            fruit = fruit,
                            quantity = q,
                            unit = unit,
                            remark = itemRemark.trim(),
                            status = oldStatus
                        )
                        if (existingIndex >= 0) {
                            draft[existingIndex] = newLine
                            message = "已更新 ${fruit.name}"
                        } else {
                            draft.add(newLine)
                            message = "已加入 ${fruit.name}"
                        }
                        fruitId = null
                        quantity = ""
                        itemRemark = ""
                        unit = "件"
                    }
                },
                modifier = Modifier.fillMaxWidth().height(42.dp)
            ) {
                Text("＋ 添加采购商品")
            }
        }

        if (draft.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("采购清单", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${draft.size} 项", color = BrandGreen)
                }
            }

            items(draft.indices.toList()) { index ->
                val line = draft[index]
                val savedItem = saved?.items?.firstOrNull {
                    it.fruitId == line.fruit.id && it.unit == line.unit
                }
                val effectiveStatus = savedItem?.status ?: line.status

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(11.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(line.fruit.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${fmt(line.quantity)}${line.unit}" +
                                        if (line.remark.isBlank()) "" else " · ${line.remark}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                            Text(
                                when (effectiveStatus) {
                                    1 -> "已采购"
                                    2 -> "已取消"
                                    else -> "待采购"
                                },
                                color = when (effectiveStatus) {
                                    1 -> BrandGreen
                                    2 -> Color.Gray
                                    else -> Color(0xFF9A6A00)
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    fruitId = line.fruit.id
                                    unit = line.unit
                                    quantity = cleanNumber(line.quantity)
                                    itemRemark = line.remark
                                }
                            ) { Text("修改") }

                            if (savedItem != null) {
                                when (effectiveStatus) {
                                    0 -> {
                                        TextButton(
                                            onClick = {
                                                db.updatePurchasePlanItemStatus(savedItem.id, 1)
                                                message = "${line.fruit.name} 已完成采购"
                                                onChanged()
                                            }
                                        ) { Text("完成") }
                                        TextButton(
                                            onClick = {
                                                db.updatePurchasePlanItemStatus(savedItem.id, 2)
                                                message = "${line.fruit.name} 已取消"
                                                onChanged()
                                            }
                                        ) { Text("取消") }
                                    }
                                    1, 2 -> {
                                        TextButton(
                                            onClick = {
                                                db.updatePurchasePlanItemStatus(savedItem.id, 0)
                                                message = "${line.fruit.name} 已恢复待采购"
                                                onChanged()
                                            }
                                        ) { Text("待采购") }
                                    }
                                }
                            }

                            TextButton(onClick = { draft.removeAt(index) }) { Text("移除") }
                        }
                    }
                }
            }

            item {
                CompactTextField("整单备注（可选）", note, { note = it }, Modifier.fillMaxWidth())

                Button(
                    onClick = {
                        if (draft.isEmpty()) {
                            message = "采购清单不能为空"
                        } else {
                            val id = db.savePurchasePlan(date, draft.toList(), note)
                            if (id > 0) {
                                message = "采购计划已保存"
                                onChanged()
                            } else {
                                message = "采购计划保存失败"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    Text(if (saved == null) "保存采购计划" else "保存采购计划修改")
                }
            }
        } else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F8FA))) {
                    Text(
                        "暂未添加采购商品。选择水果和数量后添加。",
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        color = Color.Gray
                    )
                }
            }
        }

        if (saved != null) {
            item {
                TextButton(
                    onClick = { deletePlanConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除这张采购计划")
                }
            }
        }

        if (message.isNotBlank()) {
            item { Text(message, color = BrandGreen, style = MaterialTheme.typography.bodySmall) }
        }

        item {
            HorizontalDivider()
            Text(
                "最近采购计划",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        val recent = db.getRecentPurchasePlans(12)
        if (recent.isEmpty()) {
            item { Text("暂无历史采购计划", color = Color.Gray) }
        } else {
            items(recent, key = { it.plan.id }) { detail ->
                val p = detail.items.count { it.status == 0 }
                val done = detail.items.count { it.status == 1 }
                val cancel = detail.items.count { it.status == 2 }

                Card(
                    onClick = { date = detail.plan.planDate },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(detail.plan.planDate, fontWeight = FontWeight.Bold)
                            Text(
                                detail.items.joinToString("、", limit = 3, truncated = "…") {
                                    "${it.fruitName}${fmt(it.quantity)}${it.unit}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Text(
                            if (p > 0) "待 $p · 已 $done · 取消 $cancel"
                            else if (done > 0) "已完成 $done"
                            else "已取消",
                            color = BrandGreen,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    if (addFruitDialog) {
        AddFruitDialog(
            onDismiss = { addFruitDialog = false },
            onSave = { name, defaultUnit ->
                val id = db.addFruit(name, defaultUnit)
                addFruitDialog = false
                if (id > 0) fruitId = id
                onChanged()
            }
        )
    }

    if (deletePlanConfirm && saved != null) {
        ConfirmDelete(
            "删除 ${saved.plan.planDate} 的采购计划？不会影响实际进货记录。",
            { deletePlanConfirm = false }
        ) {
            db.deletePurchasePlan(saved.plan.id)
            draft.clear()
            note = ""
            deletePlanConfirm = false
            message = "采购计划已删除"
            onChanged()
        }
    }
}

@Composable
private fun PurchaseScreen(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    val focusManager = LocalFocusManager.current
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val fruits = remember(dataVersion) { db.getFruits() }
    val partners = remember(dataVersion) { db.getPartners() }

    var buyerId by remember { mutableStateOf<Long?>(null) }
    var historicalBuyerName by remember { mutableStateOf("") }
    var buyerMenu by remember { mutableStateOf(false) }
    var remark by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    var addFruitDialog by remember { mutableStateOf(false) }
    var pendingFruitRowId by remember { mutableStateOf<Long?>(null) }

    var nextRowId by remember {
        mutableLongStateOf(System.currentTimeMillis())
    }
    val rows = remember {
        mutableStateListOf(
            PurchaseDraftRow(rowId = 1L)
        )
    }

    var editingOrderId by remember { mutableStateOf<Long?>(null) }
    var deleteOrder by remember { mutableStateOf<PurchaseOrderDetail?>(null) }

    val history = remember(dataVersion) { db.getPurchaseOrders(50) }

    val activeBuyer = partners.firstOrNull { it.id == buyerId }
    val historicalBuyer =
        if (editingOrderId != null && buyerId != null && activeBuyer == null) {
            PartnerOption(
                buyerId!!,
                historicalBuyerName.ifBlank {
                    db.getPartnerByIdIncludingDeleted(buyerId!!)?.name ?: "已删除合伙人"
                }
            )
        } else null

    val buyer =
        activeBuyer ?: historicalBuyer ?: if (editingOrderId == null) partners.firstOrNull() else null

    val buyerDisplayName = when {
        activeBuyer != null -> activeBuyer.name
        historicalBuyer != null -> "${historicalBuyer.name}（已删除）"
        else -> "请先添加"
    }

    val planForDate = remember(dataVersion, date) { db.getPurchasePlan(date) }
    val purchasedPlanItems = planForDate?.items?.filter { it.status == 1 }.orEmpty()

    LaunchedEffect(dataVersion, partners.size) {
        if (buyerId == null && partners.isNotEmpty()) {
            buyerId = partners.first().id
        }
    }

    fun newBlankRow(): PurchaseDraftRow =
        PurchaseDraftRow(rowId = nextRowId++)

    fun resetRowsToOneBlank() {
        rows.clear()
        rows.add(newBlankRow())
    }

    fun updateRow(rowId: Long, updated: PurchaseDraftRow) {
        val index = rows.indexOfFirst { it.rowId == rowId }
        if (index >= 0) rows[index] = updated
    }

    fun meaningfulRows(): List<PurchaseDraftRow> =
        rows.filterNot { it.isBlank }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PageHeader(
                "批量进货",
                "填写一种水果后点“添加商品”，下方继续增加同样的录入框"
            )
        }

        if (editingOrderId != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7D9))) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "正在编辑进货单 #$editingOrderId",
                            Modifier.weight(1f),
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = {
                                editingOrderId = null
                                historicalBuyerName = ""
                                remark = ""
                                date = LocalDate.now().toString()
                                buyerId = partners.firstOrNull()?.id
                                resetRowsToOneBlank()
                                message = "已取消编辑"
                            }
                        ) { Text("取消") }
                    }
                }
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                CompactDateNavigator(
                    label = "日期",
                    date = date,
                    modifier = Modifier.weight(1.15f)
                ) { date = it }

                Box(Modifier.weight(0.95f)) {
                    CompactSelectButton(
                        "进货人",
                        buyerDisplayName,
                        Modifier.fillMaxWidth()
                    ) { buyerMenu = true }

                    DropdownMenu(
                        expanded = buyerMenu,
                        onDismissRequest = { buyerMenu = false }
                    ) {
                        partners.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = {
                                    buyerId = p.id
                                    historicalBuyerName = ""
                                    buyerMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        if (editingOrderId == null && purchasedPlanItems.isNotEmpty()) {
            item {
                OutlinedButton(
                    onClick = {
                        if (rows.size == 1 && rows[0].isBlank) {
                            rows.clear()
                        } else if (rows.lastOrNull()?.isBlank == true) {
                            rows.removeAt(rows.lastIndex)
                        }

                        purchasedPlanItems.forEach { item ->
                            rows.add(
                                PurchaseDraftRow(
                                    rowId = nextRowId++,
                                    fruitId = item.fruitId,
                                    fruitNameSnapshot = item.fruitName,
                                    unit = item.unit,
                                    quantity = cleanNumber(item.quantity),
                                    totalCost = ""
                                )
                            )
                        }

                        if (rows.isEmpty()) rows.add(newBlankRow())

                        message =
                            "已导入 ${purchasedPlanItems.size} 项已采购水果，请填写每项实际总价"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🛒 从采购计划导入已采购商品（${purchasedPlanItems.size}项）")
                }
            }
        }

        items(
            rows,
            key = { row -> "purchase_draft_${row.rowId}" }
        ) { row ->
            PurchaseDraftRowEditor(
                row = row,
                fruits = fruits,
                canDelete = rows.size > 1,
                onChange = { updated ->
                    updateRow(row.rowId, updated)
                },
                onAddFruit = {
                    pendingFruitRowId = row.rowId
                    addFruitDialog = true
                },
                onDelete = {
                    val index = rows.indexOfFirst { it.rowId == row.rowId }
                    if (index >= 0) rows.removeAt(index)
                    if (rows.isEmpty()) rows.add(newBlankRow())
                }
            )
        }

        item {
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    if (rows.lastOrNull()?.isBlank == true) {
                        message = "下面已经有一组空白商品，直接填写即可"
                    } else {
                        rows.add(newBlankRow())
                        message = ""
                    }
                },
                modifier = Modifier.fillMaxWidth().height(42.dp)
            ) {
                Text("＋ 添加商品")
            }
        }

        val activeRows = meaningfulRows()

        if (activeRows.isNotEmpty()) {
            item {
                val total =
                    activeRows.sumOf { it.totalCost.toDoubleOrNull() ?: 0.0 }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "本次进货 ${activeRows.size} 项",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "合计 ${money(total)}",
                        fontWeight = FontWeight.Bold,
                        color = BrandGreen
                    )
                }
            }
        }

        item {
            CompactTextField(
                "备注（可选）",
                remark,
                { remark = it },
                Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    focusManager.clearFocus()
                    val filledRows = meaningfulRows()

                    when {
                        buyer == null -> {
                            message = "请先在更多页面添加合伙人"
                        }

                        filledRows.isEmpty() -> {
                            message = "请至少填写一种进货水果"
                        }

                        filledRows.any {
                            it.fruitId == null ||
                                (it.quantity.toDoubleOrNull() ?: 0.0) <= 0 ||
                                (it.totalCost.toDoubleOrNull() ?: 0.0) <= 0
                        } -> {
                            message = "有商品没有选水果，或数量/总价没有填写正确"
                        }

                        else -> {
                            val lines = filledRows.mapNotNull { r ->
                                val activeFruit =
                                    fruits.firstOrNull { it.id == r.fruitId }

                                val resolvedFruit =
                                    activeFruit ?: r.fruitId?.let { id ->
                                        r.fruitNameSnapshot
                                            .takeIf { it.isNotBlank() }
                                            ?.let { name ->
                                                FruitOption(id, name, r.unit)
                                            }
                                    }

                                resolvedFruit?.let { f ->
                                    PurchaseLineInput(
                                        fruit = f,
                                        unit = r.unit,
                                        quantity = r.quantity.toDouble(),
                                        totalCost = r.totalCost.toDouble()
                                    )
                                }
                            }

                            if (lines.size != filledRows.size) {
                                message = "有历史水果无法识别，请重新选择该商品"
                            } else {
                                val editId = editingOrderId
                                val ok =
                                    if (editId == null) {
                                        db.addPurchaseOrder(
                                            date,
                                            buyer,
                                            lines,
                                            remark
                                        ) > 0
                                    } else {
                                        db.updatePurchaseOrder(
                                            editId,
                                            date,
                                            buyer,
                                            lines,
                                            remark
                                        )
                                    }

                                if (ok) {
                                    editingOrderId = null
                                    historicalBuyerName = ""
                                    remark = ""
                                    resetRowsToOneBlank()
                                    message =
                                        if (editId == null) {
                                            "整张进货单已保存"
                                        } else {
                                            "进货单已更新"
                                        }
                                    onChanged()
                                } else {
                                    message = "保存失败，请检查内容"
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
            ) {
                Text(
                    if (editingOrderId == null) {
                        "保存整张进货单"
                    } else {
                        "保存修改"
                    }
                )
            }
        }

        if (message.isNotBlank()) {
            item {
                Text(
                    message,
                    color = BrandGreen,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        item {
            HorizontalDivider()
            Text(
                "进货历史",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (history.isEmpty()) {
            item { Text("暂无进货记录", color = Color.Gray) }
        }

        items(
            history,
            key = { detail -> "purchase_history_${detail.order.id}" }
        ) { detail ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${detail.order.date} · ${detail.order.buyerName}",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "合计 ${money(detail.order.totalCost)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }

                        TextButton(
                            onClick = {
                                editingOrderId = detail.order.id
                                date = detail.order.date
                                buyerId = detail.order.buyerId
                                historicalBuyerName = detail.order.buyerName
                                remark = detail.order.remark

                                rows.clear()
                                detail.items.forEach { item ->
                                    rows.add(
                                        PurchaseDraftRow(
                                            rowId = nextRowId++,
                                            fruitId = item.fruitId,
                                            fruitNameSnapshot = item.fruitName,
                                            unit = item.unit,
                                            quantity = cleanNumber(item.quantity),
                                            totalCost = cleanNumber(item.totalCost)
                                        )
                                    )
                                }

                                if (rows.isEmpty()) {
                                    rows.add(newBlankRow())
                                }

                                message =
                                    "已载入历史进货单，可直接修改上面的商品"
                            }
                        ) { Text("编辑") }

                        TextButton(
                            onClick = { deleteOrder = detail }
                        ) { Text("删除") }
                    }

                    detail.items.forEach { item ->
                        Text(
                            "• ${item.fruitName} ${fmt(item.quantity)}${item.unit} " +
                                "${money(item.totalCost)} " +
                                "(${money(item.unitPrice)}/${item.unit})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    if (addFruitDialog) {
        AddFruitDialog(
            onDismiss = {
                addFruitDialog = false
                pendingFruitRowId = null
            },
            onSave = { name, defaultUnit ->
                val id = db.addFruit(name, defaultUnit)
                if (id > 0) {
                    val targetId = pendingFruitRowId
                    if (targetId != null) {
                        val row = rows.firstOrNull { it.rowId == targetId }
                        if (row != null) {
                            updateRow(
                                targetId,
                                row.copy(
                                    fruitId = id,
                                    fruitNameSnapshot = name.trim(),
                                    unit = "件"
                                )
                            )
                        }
                    }
                    message = "水果商品已添加"
                    onChanged()
                } else {
                    message = "水果商品保存失败"
                }
                addFruitDialog = false
                pendingFruitRowId = null
            }
        )
    }

    deleteOrder?.let { detail ->
        ConfirmDelete(
            "删除 ${detail.order.date} 的整张进货单？历史价格也会同步隐藏。",
            { deleteOrder = null }
        ) {
            db.deletePurchaseOrder(detail.order.id)

            if (editingOrderId == detail.order.id) {
                editingOrderId = null
                historicalBuyerName = ""
                remark = ""
                resetRowsToOneBlank()
            }

            deleteOrder = null
            onChanged()
        }
    }
}

@Composable
private fun PurchaseDraftRowEditor(
    row: PurchaseDraftRow,
    fruits: List<FruitOption>,
    canDelete: Boolean,
    onChange: (PurchaseDraftRow) -> Unit,
    onAddFruit: () -> Unit,
    onDelete: () -> Unit
) {
    var fruitMenu by remember(row.rowId) { mutableStateOf(false) }
    var unitMenu by remember(row.rowId) { mutableStateOf(false) }

    val selectedFruit = fruits.firstOrNull { it.id == row.fruitId }
    val fruitDisplay =
        selectedFruit?.name
            ?: row.fruitNameSnapshot.takeIf { it.isNotBlank() }
            ?: "请选择水果"

    val quantityNumber = row.quantity.toDoubleOrNull() ?: 0.0
    val totalNumber = row.totalCost.toDoubleOrNull() ?: 0.0
    val unitPrice =
        if (quantityNumber > 0 && totalNumber > 0) {
            totalNumber / quantityNumber
        } else {
            0.0
        }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 第一行：水果 + 单位
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(Modifier.weight(1f)) {
                    CompactSelectButton(
                        "水果",
                        fruitDisplay,
                        Modifier.fillMaxWidth()
                    ) {
                        fruitMenu = true
                    }

                    DropdownMenu(
                        expanded = fruitMenu,
                        onDismissRequest = { fruitMenu = false }
                    ) {
                        fruits.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f.name) },
                                onClick = {
                                    onChange(
                                        row.copy(
                                            fruitId = f.id,
                                            fruitNameSnapshot = f.name,
                                            unit = "件"
                                        )
                                    )
                                    fruitMenu = false
                                }
                            )
                        }

                        DropdownMenuItem(
                            text = { Text("＋新增水果") },
                            onClick = {
                                fruitMenu = false
                                onAddFruit()
                            }
                        )
                    }
                }

                Box(Modifier.width(82.dp)) {
                    CompactSelectButton(
                        "单位",
                        row.unit,
                        Modifier.fillMaxWidth()
                    ) {
                        unitMenu = true
                    }

                    DropdownMenu(
                        expanded = unitMenu,
                        onDismissRequest = { unitMenu = false }
                    ) {
                        listOf("斤", "筐", "箱", "件").forEach { u ->
                            DropdownMenuItem(
                                text = { Text(u) },
                                onClick = {
                                    onChange(row.copy(unit = u))
                                    unitMenu = false
                                }
                            )
                        }
                    }
                }
            }

            // 第二行：数量 + 总价 + 单件
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                CompactNumberField(
                    "数量",
                    row.quantity,
                    { onChange(row.copy(quantity = it)) },
                    Modifier.weight(0.9f)
                )

                CompactNumberField(
                    "总价",
                    row.totalCost,
                    { onChange(row.copy(totalCost = it)) },
                    Modifier.weight(1f)
                )

                CompactReadOnlyField(
                    "单件",
                    if (unitPrice > 0) money(unitPrice) else "—",
                    Modifier.weight(1f)
                )
            }

            if (canDelete) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDelete) {
                        Text("删除此商品")
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionScreen(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val stores = remember(dataVersion) { db.getStores() }
    val partners = remember(dataVersion) { db.getPartners() }

    var storeId by remember { mutableStateOf<Long?>(null) }
    var historicalStoreName by remember { mutableStateOf("") }
    var storeMenu by remember { mutableStateOf(false) }
    var addStoreDialog by remember { mutableStateOf(false) }

    var wechat by remember { mutableStateOf("") }
    var alipay by remember { mutableStateOf("") }
    var cash by remember { mutableStateOf("") }
    var collectorId by remember { mutableStateOf<Long?>(null) }
    var historicalCollectorName by remember { mutableStateOf("") }
    var collectorMenu by remember { mutableStateOf(false) }

    var expense by remember { mutableStateOf("") }
    var expensePayerId by remember { mutableStateOf<Long?>(null) }
    var historicalExpensePayerName by remember { mutableStateOf("") }
    var expensePayerMenu by remember { mutableStateOf(false) }
    var openingStock by remember { mutableStateOf("") }
    var closingStock by remember { mutableStateOf("") }
    var newCustomer by remember { mutableStateOf("") }
    var oldCustomer by remember { mutableStateOf("") }

    var editingRecordId by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var deleteRecord by remember { mutableStateOf<StoreDailyRecord?>(null) }

    val activeSelectedStore = stores.firstOrNull { it.id == storeId }
    val historicalSelectedStore = if (editingRecordId != null && storeId != null && activeSelectedStore == null) {
        StoreOption(
            storeId!!,
            historicalStoreName.ifBlank { db.getStoreByIdIncludingDeleted(storeId!!)?.name ?: "已删除摊位" },
            ""
        )
    } else null
    val selectedStore = activeSelectedStore ?: historicalSelectedStore
    val storeDisplayName = when {
        activeSelectedStore != null -> activeSelectedStore.name
        historicalSelectedStore != null -> "${historicalSelectedStore.name}（已删除）"
        else -> "请添加位置"
    }

    val activeCollector = partners.firstOrNull { it.id == collectorId }
    val collectorDisplayName = when {
        activeCollector != null -> activeCollector.name
        editingRecordId != null && collectorId != null ->
            "${historicalCollectorName.ifBlank { db.getPartnerByIdIncludingDeleted(collectorId!!)?.name ?: "已删除合伙人" }}（已删除）"
        else -> "未指定"
    }

    val activeExpensePayer = partners.firstOrNull { it.id == expensePayerId }
    val expensePayerDisplayName = when {
        activeExpensePayer != null -> activeExpensePayer.name
        editingRecordId != null && expensePayerId != null ->
            "${historicalExpensePayerName.ifBlank { db.getPartnerByIdIncludingDeleted(expensePayerId!!)?.name ?: "已删除合伙人" }}（已删除）"
        else -> "未指定"
    }

    val todayRecords = remember(dataVersion, date) { db.getDailyRecords(date) }
    val sharedPurchase = remember(dataVersion, date) { db.getPurchaseTotal(date) }

    fun clearForm(keepDate: Boolean = true) {
        if (!keepDate) date = LocalDate.now().toString()
        editingRecordId = null
        historicalStoreName = ""
        historicalCollectorName = ""
        historicalExpensePayerName = ""
        wechat = ""
        alipay = ""
        cash = ""
        expense = ""
        closingStock = ""
        newCustomer = ""
        oldCustomer = ""
        collectorId = partners.firstOrNull()?.id
        expensePayerId = partners.firstOrNull()?.id
        val s = storeId?.let { db.getStoreById(it) }
        openingStock = if (s != null) cleanNumber(db.getPreviousClosingStock(s.id, date)) else ""
    }

    fun loadRecord(r: StoreDailyRecord) {
        editingRecordId = r.id
        date = r.date
        storeId = r.storeId
        historicalStoreName = r.storeName
        wechat = cleanNumber(r.wechatIncome)
        alipay = cleanNumber(r.alipayIncome)
        cash = cleanNumber(r.cashIncome)
        collectorId = listOf(r.wechatCollectorId, r.alipayCollectorId, r.cashCollectorId)
            .firstOrNull { it > 0 }
        historicalCollectorName = when (collectorId) {
            r.wechatCollectorId -> r.wechatCollectorName
            r.alipayCollectorId -> r.alipayCollectorName
            r.cashCollectorId -> r.cashCollectorName
            else -> ""
        }
        expense = cleanNumber(r.expense)
        expensePayerId = r.expensePayerId.takeIf { it > 0 }
        historicalExpensePayerName = r.expensePayerName
        openingStock = cleanNumber(r.openingStockValue)
        closingStock = cleanNumber(r.stockLeftValue)
        newCustomer = r.newCustomer.toString()
        oldCustomer = r.oldCustomer.toString()
        message = "已载入 ${r.storeName} 的营业记录，可直接修改"
        isError = false
    }

    LaunchedEffect(dataVersion, stores.map { it.id }, partners.map { it.id }, editingRecordId) {
        if (editingRecordId == null) {
            if (storeId == null || stores.none { it.id == storeId }) {
                storeId = stores.firstOrNull()?.id
                historicalStoreName = ""
            }
            if (collectorId != null && partners.none { it.id == collectorId }) {
                collectorId = partners.firstOrNull()?.id
                historicalCollectorName = ""
            }
            if (expensePayerId != null && partners.none { it.id == expensePayerId }) {
                expensePayerId = partners.firstOrNull()?.id
                historicalExpensePayerName = ""
            }
        }
    }

    // For a NEW entry only, change opening stock when the chosen store/date changes.
    // In edit mode we never overwrite values loaded from history.
    LaunchedEffect(date, storeId) {
        if (editingRecordId == null) {
            val s = storeId?.let { db.getStoreById(it) }
            openingStock = if (s != null) cleanNumber(db.getPreviousClosingStock(s.id, date)) else ""
        }
    }

    val w = wechat.toDoubleOrNull() ?: 0.0
    val a = alipay.toDoubleOrNull() ?: 0.0
    val c = cash.toDoubleOrNull() ?: 0.0
    val e = expense.toDoubleOrNull() ?: 0.0
    val open = openingStock.toDoubleOrNull() ?: 0.0
    val close = closingStock.toDoubleOrNull() ?: 0.0
    val n = newCustomer.toIntOrNull() ?: 0
    val o = oldCustomer.toIntOrNull() ?: 0
    val revenue = w + a + c
    val contribution = revenue + close - open - e

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        item { PageHeader("摊位营业记录", "营业原始数据优先保存；修改后相关利润分配/结算会按需重新计算") }

        if (editingRecordId != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7D9))) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("正在编辑营业记录 #$editingRecordId", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        TextButton(onClick = {
                            clearForm()
                            message = "已取消编辑"
                            isError = false
                        }) { Text("取消编辑") }
                    }
                }
            }
        }

        item {
            CompactDateNavigator(
                label = "营业日期",
                date = date,
                modifier = Modifier.fillMaxWidth(),
                chineseDisplay = true,
                showWeekday = true
            ) { date = it }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    CompactSelectButton(
                        "摊位",
                        storeDisplayName,
                        Modifier.fillMaxWidth()
                    ) { storeMenu = true }

                    DropdownMenu(expanded = storeMenu, onDismissRequest = { storeMenu = false }) {
                        stores.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.name) },
                                onClick = {
                                    storeId = s.id
                                    historicalStoreName = ""
                                    storeMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("＋新增位置") },
                            onClick = {
                                storeMenu = false
                                addStoreDialog = true
                            }
                        )
                    }
                }

                Box(Modifier.weight(1f)) {
                    CompactSelectButton(
                        "收款归属",
                        collectorDisplayName,
                        Modifier.fillMaxWidth()
                    ) { collectorMenu = true }

                    DropdownMenu(expanded = collectorMenu, onDismissRequest = { collectorMenu = false }) {
                        partners.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = {
                                    collectorId = p.id
                                    historicalCollectorName = ""
                                    collectorMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("未指定") },
                            onClick = {
                                collectorId = null
                                historicalCollectorName = ""
                                collectorMenu = false
                            }
                        )
                    }
                }
            }
        }

        item {
            Text("收款", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactNumberField("微信", wechat, { wechat = it }, Modifier.weight(1f))
                CompactNumberField("支付宝", alipay, { alipay = it }, Modifier.weight(1f))
                CompactNumberField("现金", cash, { cash = it }, Modifier.weight(1f))
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                CompactNumberField("日常开销", expense, { expense = it }, Modifier.weight(1f))

                Box(Modifier.weight(1f)) {
                    CompactSelectButton(
                        "费用付款人",
                        expensePayerDisplayName,
                        Modifier.fillMaxWidth()
                    ) { expensePayerMenu = true }

                    DropdownMenu(
                        expanded = expensePayerMenu,
                        onDismissRequest = { expensePayerMenu = false }
                    ) {
                        partners.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = {
                                    expensePayerId = p.id
                                    historicalExpensePayerName = ""
                                    expensePayerMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("未指定") },
                            onClick = {
                                expensePayerId = null
                                historicalExpensePayerName = ""
                                expensePayerMenu = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(5.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactNumberField("开摊库存", openingStock, { openingStock = it }, Modifier.weight(1f))
                CompactNumberField("收摊库存", closingStock, { closingStock = it }, Modifier.weight(1f))
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactIntegerField("新客", newCustomer, { newCustomer = it }, Modifier.weight(1f))
                CompactIntegerField("老客", oldCustomer, { oldCustomer = it }, Modifier.weight(1f))
                CompactReadOnlyField("客户合计", "${n + o}", Modifier.weight(1f))
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniSummaryCard("本摊营业额", money(revenue), Modifier.weight(1f), SoftGreen)
                MiniSummaryCard("经营贡献*", money(contribution), Modifier.weight(1f), SoftOrange)
            }
            Text(
                "*经营贡献尚未扣除共用进货；今日共用进货 ${money(sharedPurchase)}，最终利润以“结算/首页”为准。",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.padding(top = 3.dp)
            )
        }

        item {
            Button(
                onClick = {
                    val requestedStore = storeId?.let { id ->
                        db.getStoreById(id) ?: if (editingRecordId != null) {
                            StoreOption(
                                id,
                                historicalStoreName.ifBlank { db.getStoreByIdIncludingDeleted(id)?.name ?: "已删除摊位" },
                                ""
                            )
                        } else null
                    }
                    if (requestedStore == null) {
                        message = "保存失败：请选择有效摊位"
                        isError = true
                        return@Button
                    }

                    val requestedCollector = collectorId?.let { id ->
                        db.getPartnerById(id) ?: if (editingRecordId != null) {
                            PartnerOption(
                                id,
                                historicalCollectorName.ifBlank { db.getPartnerByIdIncludingDeleted(id)?.name ?: "已删除合伙人" }
                            )
                        } else null
                    }
                    if (collectorId != null && requestedCollector == null) {
                        message = "保存失败：收款归属已失效，请重新选择"
                        isError = true
                        return@Button
                    }

                    val requestedExpensePayer = expensePayerId?.let { id ->
                        db.getPartnerById(id) ?: if (editingRecordId != null) {
                            PartnerOption(
                                id,
                                historicalExpensePayerName.ifBlank { db.getPartnerByIdIncludingDeleted(id)?.name ?: "已删除合伙人" }
                            )
                        } else null
                    }
                    if (e > 0 && expensePayerId != null && requestedExpensePayer == null) {
                        message = "保存失败：费用付款人已失效，请重新选择"
                        isError = true
                        return@Button
                    }

                    val result = db.saveStoreDailyRecord(
                        recordId = editingRecordId,
                        date = date,
                        store = requestedStore,
                        wechat = w,
                        wechatCollector = requestedCollector,
                        alipay = a,
                        alipayCollector = requestedCollector,
                        cash = c,
                        cashCollector = requestedCollector,
                        expense = e,
                        expensePayer = requestedExpensePayer,
                        openingStock = open,
                        closingStock = close,
                        newCustomer = n,
                        oldCustomer = o
                    )

                    message = result.message
                    isError = !result.success

                    if (result.success) {
                        // 保存完成后立即退出编辑模式，避免继续显示“正在编辑营业记录”。
                        clearForm()
                        onChanged()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                contentPadding = PaddingValues(vertical = 5.dp)
            ) {
                Text(if (editingRecordId == null) "保存当前摊位" else "保存修改")
            }

            if (message.isNotBlank()) {
                Text(
                    message,
                    color = if (isError) MaterialTheme.colorScheme.error else BrandGreen,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }

        if (todayRecords.isNotEmpty()) {
            item {
                Text(
                    "当天已记录摊位",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(todayRecords, key = { it.id }) { r ->
                RecordCard {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (r.storeName == "共用货品") "未知摊位（旧数据）" else r.storeName,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "营业 ${money(r.revenue)} · 微信 ${money(r.wechatIncome)} · 支付宝 ${money(r.alipayIncome)} · 现金 ${money(r.cashIncome)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "客户 ${r.customerTotal} · 收款 ${r.wechatCollectorName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = {
                            loadRecord(r)
                        }) { Text("编辑") }

                        TextButton(onClick = {
                            deleteRecord = r
                        }) { Text("删除") }
                    }
                }
            }
        }
    }

    if (addStoreDialog) {
        AddStoreDialog(
            onDismiss = { addStoreDialog = false }
        ) { name, address ->
            val newId = db.addStore(name, address)
            addStoreDialog = false
            if (newId > 0) storeId = newId
            onChanged()
        }
    }

    deleteRecord?.let { r ->
        ConfirmDelete(
            "删除 ${r.date} ${r.storeName} 的营业记录？删除后该日旧利润分配/资金结算也会自动作废。",
            { deleteRecord = null }
        ) {
            if (db.deleteStoreDailyRecord(r.id)) {
                if (editingRecordId == r.id) clearForm()
                message = "营业记录已删除；该日需要重新确认利润分配/结算"
                isError = false
                onChanged()
            } else {
                message = "删除失败：记录不存在"
                isError = true
            }
            deleteRecord = null
        }
    }
}

@Composable
private fun MoneyCollectorRow(
    label: String,
    amount: String,
    onAmount: (String) -> Unit,
    partners: List<PartnerOption>,
    selectedId: Long?,
    onSelected: (Long?) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        NumberField(label, amount, onAmount, Modifier.weight(1f))
        Box(Modifier.weight(1.15f)) {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(partners.firstOrNull { it.id == selectedId }?.name ?: "收款人", maxLines = 1)
                Spacer(Modifier.weight(1f)); Text("▼")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                partners.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { onSelected(p.id); menu = false }) }
                DropdownMenuItem(text = { Text("未指定") }, onClick = { onSelected(null); menu = false })
            }
        }
    }
}

@Composable
private fun SettlementScreen(
    db: AppDatabase,
    dataVersion: Int,
    onChanged: () -> Unit
) {
    var view by remember { mutableStateOf(SettlementView.DAY) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SettlementView.entries.forEach { item ->
                if (view == item) {
                    Button(
                        onClick = { view = item },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(item.label, fontSize = 12.sp)
                    }
                } else {
                    OutlinedButton(
                        onClick = { view = item },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(item.label, fontSize = 12.sp)
                    }
                }
            }
        }

        when (view) {
            SettlementView.DAY ->
                SettlementDayContent(
                    db = db,
                    dataVersion = dataVersion,
                    onChanged = onChanged
                )

            SettlementView.BATCH ->
                SettlementBatchContent(
                    db = db,
                    dataVersion = dataVersion,
                    onChanged = onChanged
                )

            SettlementView.STATS ->
                SettlementStatsContent(
                    db = db,
                    dataVersion = dataVersion,
                    onChanged = onChanged
                )
        }
    }
}

@Composable
private fun SettlementDayContent(
    db: AppDatabase,
    dataVersion: Int,
    onChanged: () -> Unit
) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var message by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<Long?>(null) }

    val summary = remember(dataVersion, date) {
        db.getDailySummary(date)
    }
    val purchases = remember(dataVersion, date) {
        db.getPurchaseTotalsByPartner(date)
    }
    val receipts = remember(dataVersion, date) {
        db.getReceiptsByPartner(date)
    }
    val expenses = remember(dataVersion, date) {
        db.getExpenseTotalsByPartner(date)
    }
    val profitRows = remember(dataVersion, date) {
        db.getProfitDistribution(date)
    }
    val bundle = remember(dataVersion, date) {
        db.getCashSettlement(date)
    }

    LaunchedEffect(
        dataVersion,
        date,
        summary.profit,
        profitRows.size
    ) {
        if (profitRows.isEmpty() && summary.profit > 0) {
            val rules = db.getProfitRules()
            if (rules.isNotEmpty()) {
                val partners = db.getPartners()
                val allocations =
                    rules.mapNotNull { rule ->
                        partners
                            .firstOrNull { it.id == rule.partnerId }
                            ?.let { p -> p to rule.percent }
                    }

                if (
                    allocations.isNotEmpty() &&
                    kotlin.math.abs(
                        allocations.sumOf { it.second } - 100.0
                    ) < 0.01
                ) {
                    if (
                        db.saveProfitDistribution(
                            date,
                            allocations
                        )
                    ) {
                        onChanged()
                    }
                }
            }
        }
    }

    val profitSettlementRows =
        remember(dataVersion, date) {
            db.getProfitSettlementDaily(date, date)
        }
    val pendingProfitRows =
        profitSettlementRows.filter {
            it.pendingProfit > 0.005
        }
    val history =
        remember(dataVersion) {
            db.getRecentCashSettlements(20)
        }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = 14.dp,
            vertical = 4.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PageHeader(
                "当日结算",
                "利润是否已发放按“日期 + 合伙人”分别确认；资金轧差方案独立保留"
            )
        }

        item {
            CompactDateNavigator(
                label = "结算日期",
                date = date,
                modifier = Modifier.fillMaxWidth(),
                chineseDisplay = true,
                showWeekday = true
            ) {
                date = it
                message = ""
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniSummaryCard(
                    "营业额",
                    money(summary.revenue),
                    Modifier.weight(1f),
                    SoftGreen
                )
                MiniSummaryCard(
                    "进货",
                    money(summary.purchaseCost),
                    Modifier.weight(1f),
                    SoftPurple
                )
                MiniSummaryCard(
                    "利润",
                    money(summary.profit),
                    Modifier.weight(1f),
                    SoftOrange
                )
            }
        }

        item {
            Text(
                "利润结算状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (profitSettlementRows.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFF8FAFC)
                    )
                ) {
                    Text(
                        if (summary.profit > 0) {
                            "当天利润分配正在生成或尚未保存。"
                        } else {
                            "当天没有可结算的正利润。"
                        },
                        modifier = Modifier.padding(12.dp),
                        color = Color.Gray
                    )
                }
            }
        }

        items(
            profitSettlementRows,
            key = {
                "profit_status_${it.date}_${it.partnerId}"
            }
        ) { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 10.dp
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.partnerName,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "应得 ${money(row.earnedProfit)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }

                        when {
                            row.pendingProfit > 0.005 -> {
                                Text(
                                    "待结算 ${money(row.pendingProfit)}",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            row.pendingProfit < -0.005 -> {
                                Text(
                                    "多结算 ${money(-row.pendingProfit)}",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            else -> {
                                Text(
                                    "已结算",
                                    color = BrandGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (row.settledProfit > 0.005) {
                        Text(
                            buildString {
                                append(
                                    "累计已结算 ${money(row.settledProfit)}"
                                )
                                if (
                                    row.lastSettlementDate.isNotBlank()
                                ) {
                                    append(
                                        " · 最近 ${row.lastSettlementDate}"
                                    )
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }

                    if (row.pendingProfit > 0.005) {
                        OutlinedButton(
                            onClick = {
                                val result =
                                    db.createProfitSettlementBatch(
                                        start = date,
                                        end = date,
                                        partnerIds =
                                            setOf(row.partnerId),
                                        note =
                                            "单人单日利润结算"
                                    )
                                message = result.message
                                if (result.success) {
                                    onChanged()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                        ) {
                            Text(
                                "确认 ${row.partnerName} 利润已结算"
                            )
                        }
                    }
                }
            }
        }

        if (pendingProfitRows.isNotEmpty()) {
            item {
                Button(
                    onClick = {
                        val result =
                            db.createProfitSettlementBatch(
                                start = date,
                                end = date,
                                partnerIds =
                                    pendingProfitRows
                                        .map { it.partnerId }
                                        .toSet(),
                                note = "当日全部利润结算"
                            )
                        message = result.message
                        if (result.success) onChanged()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "全部确认利润已结算 · " +
                            money(
                                pendingProfitRows.sumOf {
                                    it.pendingProfit
                                }
                            )
                    )
                }
            }
        }

        if (message.isNotBlank()) {
            item {
                Text(
                    message,
                    color =
                        if (
                            message.contains("已确认") ||
                            message.contains("已生成") ||
                            message.contains("成功")
                        ) {
                            BrandGreen
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        item {
            HorizontalDivider()

            Text(
                "资金轧差方案",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "这里计算进货垫付、费用垫付、应得利润和实际收款的资金差额。" +
                    "它与上面的“利润已发放确认”是两套状态。" +
                    "如果利润每3～7天才发一次，可只使用利润结算/批量结算；" +
                    "资金轧差方案仍按当天全部应得利润计算。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFF8FAFC)
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "结算前核对",
                        fontWeight = FontWeight.Bold
                    )
                    SummaryRow(
                        "进货垫付合计",
                        money(purchases.sumOf { it.amount })
                    )
                    SummaryRow(
                        "实际收款合计",
                        money(receipts.sumOf { it.amount })
                    )
                    SummaryRow(
                        "费用垫付合计",
                        money(expenses.sumOf { it.amount })
                    )
                    SummaryRow(
                        "已保存利润分配",
                        money(
                            profitRows.sumOf {
                                it.allocatedProfit
                            }
                        )
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    val result =
                        db.generateCashSettlement(date)
                    message = result.message
                    if (result.success) onChanged()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (bundle == null) {
                        "生成当日资金轧差方案"
                    } else {
                        "重新生成资金轧差方案"
                    }
                )
            }
        }

        bundle?.let { b ->
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "每人最终余额",
                        style =
                            MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (b.settlement.status == 1) {
                                    "资金已确认"
                                } else {
                                    "待确认"
                                }
                            )
                        }
                    )
                }
            }

            items(
                b.partners,
                key = { "cp${it.id}" }
            ) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(11.dp)) {
                        Row {
                            Text(
                                p.partnerName,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                when {
                                    p.balance > 0.005 ->
                                        "应收 ${money(p.balance)}"

                                    p.balance < -0.005 ->
                                        "应转出 ${money(-p.balance)}"

                                    else ->
                                        "已平"
                                },
                                fontWeight = FontWeight.Bold,
                                color =
                                    if (p.balance >= -0.005) {
                                        BrandGreen
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                            )
                        }

                        Text(
                            "进货 ${money(p.purchasePaid)} + " +
                                "费用 ${money(p.expensePaid)} + " +
                                "利润 ${money(p.profitShare)} − " +
                                "已收 ${money(p.revenueReceived)}",
                            style =
                                MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Text(
                            "最终应留 ${money(p.shouldKeep)}",
                            style =
                                MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                Text(
                    "最少转账方案",
                    style =
                        MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (b.transfers.isEmpty()) {
                    Text(
                        "无需转账，已经平账。",
                        color = BrandGreen
                    )
                }
            }

            items(
                b.transfers,
                key = { "ct${it.id}" }
            ) { t ->
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = SoftGreen
                        ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Text(
                            "${t.fromPartnerName}  →  " +
                                t.toPartnerName,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            money(t.amount),
                            fontWeight = FontWeight.Bold,
                            color = BrandGreen
                        )
                    }
                }
            }

            item {
                if (b.settlement.status == 0) {
                    Button(
                        onClick = {
                            if (
                                db.confirmCashSettlement(
                                    b.settlement.id
                                )
                            ) {
                                message =
                                    "资金轧差方案已确认执行"
                                onChanged()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("确认资金轧差方案已执行")
                    }
                }

                TextButton(
                    onClick = {
                        deleteId = b.settlement.id
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除本次资金轧差记录")
                }
            }
        }

        if (history.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text(
                    "最近资金轧差记录",
                    style =
                        MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(
                history,
                key = { "cash${it.id}" }
            ) { h ->
                RecordCard {
                    Column(Modifier.weight(1f)) {
                        Text(
                            h.date,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "营业 ${money(h.revenue)} · " +
                                "进货 ${money(h.purchaseCost)} · " +
                                "利润 ${money(h.profit)}",
                            style =
                                MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        if (h.status == 1) {
                            "已确认"
                        } else {
                            "待确认"
                        },
                        color =
                            if (h.status == 1) {
                                BrandGreen
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                    )
                }
            }
        }
    }

    deleteId?.let { id ->
        ConfirmDelete(
            "删除这张资金轧差记录？不会删除进货、营业、利润分配或利润结算确认。",
            { deleteId = null }
        ) {
            db.deleteCashSettlement(id)
            deleteId = null
            message = "资金轧差记录已删除"
            onChanged()
        }
    }
}

@Composable
private fun SettlementBatchContent(
    db: AppDatabase,
    dataVersion: Int,
    onChanged: () -> Unit
) {
    var filter by remember {
        mutableStateOf(HistoryTimeFilter.LAST_7)
    }
    val today = LocalDate.now()
    var customStart by remember {
        mutableStateOf(today.minusDays(6).toString())
    }
    var customEnd by remember {
        mutableStateOf(today.toString())
    }
    var message by remember {
        mutableStateOf("")
    }

    val invalidCustom =
        filter == HistoryTimeFilter.CUSTOM &&
            customStart > customEnd
    val range =
        resolveTimeRange(
            filter,
            customStart,
            customEnd,
            today
        )
    val queryStart =
        if (invalidCustom) "9999-12-31" else range.first
    val queryEnd =
        if (invalidCustom) "0000-01-01" else range.second

    val dailyRows =
        remember(dataVersion, queryStart, queryEnd) {
            db.getProfitSettlementDaily(
                queryStart,
                queryEnd
            )
        }
    val partnerStats =
        remember(dataVersion, queryStart, queryEnd) {
            db.getPartnerProfitSettlementSummary(
                queryStart,
                queryEnd
            )
        }

    val actualStart =
        dailyRows.minOfOrNull { it.date }
    val actualEnd =
        dailyRows.maxOfOrNull { it.date }

    val selected =
        remember {
            mutableStateMapOf<Long, Boolean>()
        }

    LaunchedEffect(
        queryStart,
        queryEnd,
        partnerStats.map {
            "${it.partnerId}:${it.pendingProfit}"
        }
    ) {
        selected.clear()
        partnerStats
            .filter { it.pendingProfit > 0.005 }
            .forEach {
                selected[it.partnerId] = true
            }
    }

    val selectedIds =
        partnerStats
            .filter {
                selected[it.partnerId] == true &&
                    it.pendingProfit > 0.005
            }
            .map { it.partnerId }
            .toSet()

    val selectedPending =
        partnerStats
            .filter {
                it.partnerId in selectedIds
            }
            .sumOf { it.pendingProfit }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = 14.dp,
            vertical = 4.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PageHeader(
                "批量利润结算",
                "适合每3～7天或任意时间范围统一结算；只会结算当前仍待结算的利润"
            )
        }

        item {
            TimeFilterSelector(
                filter = filter,
                onFilterChange = {
                    filter = it
                    message = ""
                },
                customStart = customStart,
                onCustomStart = {
                    customStart = it
                    message = ""
                },
                customEnd = customEnd,
                onCustomEnd = {
                    customEnd = it
                    message = ""
                }
            )
        }

        if (invalidCustom) {
            item {
                Text(
                    "开始日期不能晚于结束日期",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        if (partnerStats.isEmpty()) {
            item {
                Text(
                    "当前时间范围没有可结算的利润分配记录。",
                    color = Color.Gray
                )
            }
        }

        items(
            partnerStats,
            key = { "batch_partner_${it.partnerId}" }
        ) { stat ->
            val canSettle =
                stat.pendingProfit > 0.005

            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(
                        horizontal = 10.dp,
                        vertical = 8.dp
                    ),
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked =
                            selected[stat.partnerId] == true,
                        enabled = canSettle,
                        onCheckedChange = { checked ->
                            selected[stat.partnerId] =
                                checked
                        }
                    )

                    Column(Modifier.weight(1f)) {
                        Text(
                            stat.partnerName,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "应得 ${money(stat.earnedProfit)} · " +
                                "已结算 ${money(stat.settledProfit)}",
                            style =
                                MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }

                    Text(
                        when {
                            stat.pendingProfit > 0.005 ->
                                "待结算 ${money(stat.pendingProfit)}"

                            stat.pendingProfit < -0.005 ->
                                "多结算 ${money(-stat.pendingProfit)}"

                            else ->
                                "已结清"
                        },
                        color =
                            if (
                                kotlin.math.abs(
                                    stat.pendingProfit
                                ) <= 0.005
                            ) {
                                BrandGreen
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (partnerStats.any {
                it.pendingProfit > 0.005
            }
        ) {
            item {
                Row(
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            partnerStats
                                .filter {
                                    it.pendingProfit > 0.005
                                }
                                .forEach {
                                    selected[it.partnerId] = true
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("全选")
                    }

                    OutlinedButton(
                        onClick = {
                            selected.clear()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取消全选")
                    }
                }

                Button(
                    onClick = {
                        val start =
                            range.first ?: actualStart
                        val end =
                            range.second ?: actualEnd

                        if (
                            start == null ||
                            end == null
                        ) {
                            message =
                                "当前范围没有利润记录"
                        } else {
                            val result =
                                db.createProfitSettlementBatch(
                                    start = start,
                                    end = end,
                                    partnerIds = selectedIds,
                                    note =
                                        "批量利润结算"
                                )
                            message = result.message
                            if (result.success) {
                                onChanged()
                            }
                        }
                    },
                    enabled =
                        selectedIds.isNotEmpty() &&
                            !invalidCustom,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                ) {
                    Text(
                        "确认选中合伙人已结算 · " +
                            money(selectedPending)
                    )
                }
            }
        }

        if (message.isNotBlank()) {
            item {
                Text(
                    message,
                    color =
                        if (message.contains("已确认")) {
                            BrandGreen
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    style =
                        MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SettlementStatsContent(
    db: AppDatabase,
    dataVersion: Int,
    onChanged: () -> Unit
) {
    var filter by remember {
        mutableStateOf(HistoryTimeFilter.THIS_MONTH)
    }
    val today = LocalDate.now()
    var customStart by remember {
        mutableStateOf(
            today.withDayOfMonth(1).toString()
        )
    }
    var customEnd by remember {
        mutableStateOf(today.toString())
    }
    var undoBatch by remember {
        mutableStateOf<ProfitSettlementBatchRecord?>(null)
    }
    var message by remember {
        mutableStateOf("")
    }

    val invalidCustom =
        filter == HistoryTimeFilter.CUSTOM &&
            customStart > customEnd
    val range =
        resolveTimeRange(
            filter,
            customStart,
            customEnd,
            today
        )
    val queryStart =
        if (invalidCustom) "9999-12-31" else range.first
    val queryEnd =
        if (invalidCustom) "0000-01-01" else range.second

    val partnerStats =
        remember(dataVersion, queryStart, queryEnd) {
            db.getPartnerProfitSettlementSummary(
                queryStart,
                queryEnd
            )
        }
    val dailyRows =
        remember(dataVersion, queryStart, queryEnd) {
            db.getProfitSettlementDaily(
                queryStart,
                queryEnd
            )
        }
    val batches =
        remember(dataVersion, queryStart, queryEnd) {
            db.getProfitSettlementBatchesForProfitPeriod(
                queryStart,
                queryEnd,
                80
            )
        }

    val totalEarned =
        partnerStats.sumOf { it.earnedProfit }
    val totalSettled =
        partnerStats.sumOf { it.settledProfit }
    val totalPending =
        partnerStats.sumOf { it.pendingProfit }

    val dailyGroups =
        dailyRows
            .groupBy { it.date }
            .toList()
            .sortedByDescending { it.first }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = 14.dp,
            vertical = 4.dp
        ),
        verticalArrangement =
            Arrangement.spacedBy(8.dp)
    ) {
        item {
            PageHeader(
                "利润结算统计",
                "统计每位合伙人每天应得、已结算和待结算利润"
            )
        }

        item {
            TimeFilterSelector(
                filter = filter,
                onFilterChange = {
                    filter = it
                    message = ""
                },
                customStart = customStart,
                onCustomStart = {
                    customStart = it
                    message = ""
                },
                customEnd = customEnd,
                onCustomEnd = {
                    customEnd = it
                    message = ""
                }
            )
        }

        if (invalidCustom) {
            item {
                Text(
                    "开始日期不能晚于结束日期",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        item {
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                MiniSummaryCard(
                    "应得利润",
                    money(totalEarned),
                    Modifier.weight(1f),
                    SoftOrange
                )
                MiniSummaryCard(
                    "已结算",
                    money(totalSettled),
                    Modifier.weight(1f),
                    SoftGreen
                )
                MiniSummaryCard(
                    "待结算",
                    money(totalPending),
                    Modifier.weight(1f),
                    SoftPurple
                )
            }
        }

        item {
            Text(
                "合伙人统计",
                style =
                    MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (partnerStats.isEmpty()) {
            item {
                Text(
                    "当前时间范围暂无利润分配记录。",
                    color = Color.Gray
                )
            }
        }

        items(
            partnerStats,
            key = { "settlement_stat_${it.partnerId}" }
        ) { stat ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stat.partnerName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    SummaryRow(
                        "应得利润",
                        money(stat.earnedProfit)
                    )
                    SummaryRow(
                        "已结算利润",
                        money(stat.settledProfit)
                    )
                    SummaryRow(
                        if (stat.pendingProfit >= 0) {
                            "待结算利润"
                        } else {
                            "多结算"
                        },
                        money(
                            kotlin.math.abs(
                                stat.pendingProfit
                            )
                        )
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFEAF8F0)
                )
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "期间合计结算利润",
                        color = Color.Gray,
                        style =
                            MaterialTheme.typography.bodySmall
                    )
                    Text(
                        money(totalSettled),
                        color = BrandGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp
                    )
                    Text(
                        "期间应得 ${money(totalEarned)} · " +
                            if (totalPending >= 0) {
                                "待结算 ${money(totalPending)}"
                            } else {
                                "多结算 ${money(-totalPending)}"
                            },
                        style =
                            MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            HorizontalDivider()
            Text(
                "每天每人利润明细",
                style =
                    MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        dailyGroups.forEach { entry ->
            val day = entry.first
            val rows = entry.second

            item(
                key = "settlement_day_detail_$day"
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Text(
                                day,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "当日应得 " +
                                    money(
                                        rows.sumOf {
                                            it.earnedProfit
                                        }
                                    ),
                                style =
                                    MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }

                        rows.forEach { row ->
                            Row(
                                Modifier.padding(
                                    top = 6.dp
                                ),
                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {
                                Column(
                                    Modifier.weight(1f)
                                ) {
                                    Text(
                                        row.partnerName,
                                        fontWeight =
                                            FontWeight.SemiBold
                                    )
                                    Text(
                                        "应得 ${money(row.earnedProfit)} · " +
                                            "已结算 ${money(row.settledProfit)}",
                                        style =
                                            MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }

                                Text(
                                    when {
                                        row.pendingProfit > 0.005 ->
                                            "待 ${money(row.pendingProfit)}"

                                        row.pendingProfit < -0.005 ->
                                            "多 ${money(-row.pendingProfit)}"

                                        else ->
                                            "已结算"
                                    },
                                    color =
                                        if (
                                            kotlin.math.abs(
                                                row.pendingProfit
                                            ) <= 0.005
                                        ) {
                                            BrandGreen
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        if (batches.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text(
                    "结算批次",
                    style =
                        MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "误确认时可撤销整个批次，利润会重新变成待结算。",
                    style =
                        MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            items(
                batches,
                key = { "profit_batch_${it.id}" }
            ) { batch ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "结算于 ${batch.settlementDate}",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "利润期间 " +
                                    "${batch.periodStart} ～ " +
                                    batch.periodEnd,
                                style =
                                    MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                            Text(
                                money(batch.totalAmount),
                                color = BrandGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        TextButton(
                            onClick = {
                                undoBatch = batch
                            }
                        ) {
                            Text("撤销")
                        }
                    }
                }
            }
        }

        if (message.isNotBlank()) {
            item {
                Text(
                    message,
                    color = BrandGreen,
                    style =
                        MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    undoBatch?.let { batch ->
        ConfirmActionDialog(
            title = "撤销利润结算",
            text =
                "撤销 ${batch.settlementDate} 的结算批次？" +
                    "该批次 ${money(batch.totalAmount)} " +
                    "会重新计入待结算利润。",
            confirmText = "确认撤销",
            onDismiss = {
                undoBatch = null
            }
        ) {
            if (
                db.deleteProfitSettlementBatch(
                    batch.id
                )
            ) {
                message = "利润结算批次已撤销"
                onChanged()
            }
            undoBatch = null
        }
    }
}


@Composable
private fun MoreScreen(
    db: AppDatabase,
    dataVersion: Int,
    initialSub: MorePage = MorePage.MENU,
    onPlan: () -> Unit,
    onChanged: () -> Unit
) {
    var sub by remember(initialSub) { mutableStateOf(initialSub) }

    fun goBack() {
        sub =
            when (sub) {
                MorePage.HISTORY, MorePage.STATS ->
                    MorePage.DATA_CENTER

                else ->
                    MorePage.MENU
            }
    }

    BackHandler(enabled = sub != MorePage.MENU) {
        goBack()
    }

    when (sub) {
        MorePage.MENU -> {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { PageHeader("更多") }

                item {
                    MenuCard("📊 数据中心") {
                        sub = MorePage.DATA_CENTER
                    }
                }

                item {
                    MenuCard("👥 合伙人管理") {
                        sub = MorePage.PARTNERS
                    }
                }

                item {
                    MenuCard("📍 摊位管理") {
                        sub = MorePage.STORES
                    }
                }

                item {
                    MenuCard("📦 商品管理") {
                        sub = MorePage.FRUITS
                    }
                }

                item {
                    MenuCard("💰 利润分配") {
                        sub = MorePage.PROFIT
                    }
                }

                item {
                    MenuCard("🛒 采购清单") {
                        onPlan()
                    }
                }
            }
        }

        MorePage.DATA_CENTER -> {
            SubPage(
                "数据中心",
                { sub = MorePage.MENU }
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        MenuCard("🕘 历史记录") {
                            sub = MorePage.HISTORY
                        }
                    }

                    item {
                        MenuCard("📈 经营分析") {
                            sub = MorePage.STATS
                        }
                    }
                }
            }
        }

        MorePage.HISTORY -> {
            SubPage(
                "历史记录",
                { sub = MorePage.DATA_CENTER }
            ) {
                HistoryContent(
                    db,
                    dataVersion,
                    onChanged
                )
            }
        }

        MorePage.STATS -> {
            SubPage(
                "经营分析",
                { sub = MorePage.DATA_CENTER }
            ) {
                StatsContent(
                    db,
                    dataVersion
                )
            }
        }

        MorePage.PARTNERS -> {
            SubPage(
                "合伙人管理",
                { sub = MorePage.MENU }
            ) {
                PartnerContent(
                    db,
                    dataVersion,
                    onChanged
                )
            }
        }

        MorePage.STORES -> {
            SubPage(
                "摊位管理",
                { sub = MorePage.MENU }
            ) {
                StoreContent(
                    db,
                    dataVersion,
                    onChanged
                )
            }
        }

        MorePage.FRUITS -> {
            SubPage(
                "商品管理",
                { sub = MorePage.MENU }
            ) {
                FruitManagementContent(
                    db,
                    dataVersion,
                    onChanged
                )
            }
        }

        MorePage.PROFIT -> {
            SubPage(
                "利润分配",
                { sub = MorePage.MENU }
            ) {
                ProfitContent(
                    db,
                    dataVersion,
                    onChanged
                )
            }
        }
    }
}

@Composable
private fun MenuCard(
    title: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "›",
                color = BrandGreen,
                fontSize = 24.sp
            )
        }
    }
}

@Composable
private fun SubPage(title: String, back: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("← 返回") }
            Text(title, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun HistoryContent(
    db: AppDatabase,
    dataVersion: Int,
    onChanged: () -> Unit
) {
    var timeFilter by remember {
        mutableStateOf(HistoryTimeFilter.ALL)
    }
    var filterMenu by remember {
        mutableStateOf(false)
    }

    val today = LocalDate.now()

    var customStart by remember {
        mutableStateOf(today.withDayOfMonth(1).toString())
    }
    var customEnd by remember {
        mutableStateOf(today.toString())
    }

    val range = remember(timeFilter, customStart, customEnd) {
        when (timeFilter) {
            HistoryTimeFilter.ALL ->
                null to null

            HistoryTimeFilter.TODAY ->
                today.toString() to today.toString()

            HistoryTimeFilter.YESTERDAY -> {
                val d = today.minusDays(1)
                d.toString() to d.toString()
            }

            HistoryTimeFilter.LAST_7 ->
                today.minusDays(6).toString() to today.toString()

            HistoryTimeFilter.LAST_30 ->
                today.minusDays(29).toString() to today.toString()

            HistoryTimeFilter.THIS_MONTH ->
                today.withDayOfMonth(1).toString() to today.toString()

            HistoryTimeFilter.LAST_MONTH -> {
                val firstThisMonth = today.withDayOfMonth(1)
                val firstLastMonth = firstThisMonth.minusMonths(1)
                firstLastMonth.toString() to
                    firstThisMonth.minusDays(1).toString()
            }

            HistoryTimeFilter.CUSTOM ->
                customStart to customEnd
        }
    }

    val invalidCustomRange =
        timeFilter == HistoryTimeFilter.CUSTOM &&
            customStart > customEnd

    val queryStart =
        if (invalidCustomRange) "9999-12-31" else range.first
    val queryEnd =
        if (invalidCustomRange) "0000-01-01" else range.second

    val purchases = remember(dataVersion, queryStart, queryEnd) {
        db.getPurchaseOrdersBetween(queryStart, queryEnd)
    }

    val sessions = remember(dataVersion, queryStart, queryEnd) {
        db.getDailyRecordsBetween(queryStart, queryEnd)
    }

    val profitRows = remember(dataVersion, queryStart, queryEnd) {
        db.getProfitDistributionsBetween(queryStart, queryEnd)
    }

    val purchasePlans = remember(dataVersion, queryStart, queryEnd) {
        db.getPurchasePlansBetween(queryStart, queryEnd)
    }

    val profitByDate = remember(profitRows) {
        profitRows
            .groupBy { it.date }
            .toList()
            .sortedByDescending { it.first }
    }

    var deleteOrder by remember {
        mutableStateOf<PurchaseOrderDetail?>(null)
    }
    var deleteSession by remember {
        mutableStateOf<StoreDailyRecord?>(null)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "时间筛选",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp)
            ) {
                CompactSelectButton(
                    "查看范围",
                    timeFilter.label,
                    Modifier.fillMaxWidth()
                ) {
                    filterMenu = true
                }

                DropdownMenu(
                    expanded = filterMenu,
                    onDismissRequest = { filterMenu = false }
                ) {
                    HistoryTimeFilter.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                timeFilter = option
                                filterMenu = false
                            }
                        )
                    }
                }
            }
        }

        if (timeFilter == HistoryTimeFilter.CUSTOM) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactDateSelector(
                        "开始日期",
                        customStart,
                        Modifier.weight(1f)
                    ) {
                        customStart = it
                    }

                    CompactDateSelector(
                        "结束日期",
                        customEnd,
                        Modifier.weight(1f)
                    ) {
                        customEnd = it
                    }
                }

                if (invalidCustomRange) {
                    Text(
                        "开始日期不能晚于结束日期",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        if (
            !invalidCustomRange &&
            range.first != null &&
            range.second != null
        ) {
            item {
                Text(
                    "${range.first} ～ ${range.second}",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFF5F8F6)
                )
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "营业 ${sessions.size} 条",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "进货 ${purchases.size} 单",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "利润 ${profitByDate.size} 天",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "采购 ${purchasePlans.size} 张",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Text(
                "营业历史",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (sessions.isEmpty()) {
            item {
                Text(
                    "当前时间范围暂无营业记录",
                    color = Color.Gray
                )
            }
        }

        items(
            sessions,
            key = { "s${it.id}" }
        ) { s ->
            RecordCard {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${s.date} · ${s.storeName}",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "营业 ${money(s.revenue)} · " +
                            "利润 ${money(s.profit)} · " +
                            "客户 ${s.customerTotal}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                TextButton(
                    onClick = { deleteSession = s }
                ) {
                    Text("删除")
                }
            }
        }

        item {
            HorizontalDivider()

            Text(
                "进货历史",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                "修改进货记录请到“进货”页点击对应记录的“编辑”。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }

        if (purchases.isEmpty()) {
            item {
                Text(
                    "当前时间范围暂无进货记录",
                    color = Color.Gray
                )
            }
        }

        items(
            purchases,
            key = { "p${it.order.id}" }
        ) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${p.order.date} · ${p.order.buyerName}",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "共用进货 · ${money(p.order.totalCost)}"
                            )
                        }

                        TextButton(
                            onClick = { deleteOrder = p }
                        ) {
                            Text("删除")
                        }
                    }

                    p.items.forEach { i ->
                        Text(
                            "• ${i.fruitName} " +
                                "${fmt(i.quantity)}${i.unit} " +
                                money(i.totalCost),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            HorizontalDivider()

            Text(
                "利润分配历史",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (profitByDate.isEmpty()) {
            item {
                Text(
                    "当前时间范围暂无利润分配记录",
                    color = Color.Gray
                )
            }
        }

        profitByDate.forEach { entry ->
            val date = entry.first
            val rows = entry.second

            item(key = "profit-$date") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(11.dp)) {
                        Text(
                            date,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            "利润 " +
                                money(
                                    rows.firstOrNull()?.sourceProfit ?: 0.0
                                ) +
                                " · 已分配 " +
                                money(
                                    rows.sumOf { it.allocatedProfit }
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )

                        rows.forEach { r ->
                            Text(
                                "• ${r.partnerName} " +
                                    "${fmt(r.ratio)}%  " +
                                    money(r.allocatedProfit),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        item {
            HorizontalDivider()

            Text(
                "采购计划历史",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (purchasePlans.isEmpty()) {
            item {
                Text(
                    "当前时间范围暂无采购计划",
                    color = Color.Gray
                )
            }
        }

        items(
            purchasePlans,
            key = { "plan${it.plan.id}" }
        ) { detail ->
            val pending =
                detail.items.count { it.status == 0 }
            val purchased =
                detail.items.count { it.status == 1 }
            val cancelled =
                detail.items.count { it.status == 2 }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(11.dp)) {
                    Text(
                        detail.plan.planDate,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "待采购 $pending · " +
                            "已采购 $purchased · " +
                            "取消 $cancelled",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )

                    detail.items.forEach { item ->
                        val statusText =
                            when (item.status) {
                                1 -> "已采购"
                                2 -> "取消"
                                else -> "待采购"
                            }

                        Text(
                            "• ${item.fruitName} " +
                                "${fmt(item.quantity)}${item.unit} · " +
                                statusText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    deleteOrder?.let { p ->
        ConfirmDelete(
            "删除这张进货单？",
            { deleteOrder = null }
        ) {
            db.deletePurchaseOrder(p.order.id)
            deleteOrder = null
            onChanged()
        }
    }

    deleteSession?.let { s ->
        ConfirmDelete(
            "删除 ${s.date} ${s.storeName} 营业记录？",
            { deleteSession = null }
        ) {
            db.deleteStoreDailyRecord(s.id)
            deleteSession = null
            onChanged()
        }
    }
}

@Composable
private fun StatsContent(
    db: AppDatabase,
    dataVersion: Int
) {
    var filter by remember {
        mutableStateOf(HistoryTimeFilter.THIS_MONTH)
    }
    val today = LocalDate.now()
    var customStart by remember {
        mutableStateOf(
            today.withDayOfMonth(1).toString()
        )
    }
    var customEnd by remember {
        mutableStateOf(today.toString())
    }

    val invalidCustom =
        filter == HistoryTimeFilter.CUSTOM &&
            customStart > customEnd
    val range =
        resolveTimeRange(
            filter,
            customStart,
            customEnd,
            today
        )
    val queryStart =
        if (invalidCustom) "9999-12-31" else range.first
    val queryEnd =
        if (invalidCustom) "0000-01-01" else range.second

    val rankings =
        remember(
            dataVersion,
            queryStart,
            queryEnd
        ) {
            db.getRankings(
                queryStart,
                queryEnd
            )
        }

    val records =
        remember(
            dataVersion,
            queryStart,
            queryEnd
        ) {
            db.getDailyRecordsBetween(
                queryStart,
                queryEnd
            )
        }

    val purchases =
        remember(
            dataVersion,
            queryStart,
            queryEnd
        ) {
            db.getPurchaseOrdersBetween(
                queryStart,
                queryEnd
            )
        }

    val activityDates =
        remember(records, purchases) {
            (
                records.map { it.date } +
                    purchases.map { it.order.date }
                )
                .distinct()
                .sorted()
        }

    val summaries =
        remember(
            dataVersion,
            activityDates
        ) {
            activityDates.map {
                db.getDailySummary(it)
            }
        }

    val totalRevenue =
        summaries.sumOf { it.revenue }
    val totalProfit =
        summaries.sumOf { it.profit }
    val totalPurchase =
        summaries.sumOf { it.purchaseCost }
    val totalCustomers =
        summaries.sumOf { it.customers }
    val activeDays =
        activityDates.size

    val fruits =
        remember(dataVersion) {
            db.getFruits()
        }
    var fruitId by remember {
        mutableStateOf<Long?>(null)
    }
    val fruit =
        fruits.firstOrNull {
            it.id == fruitId
        } ?: fruits.firstOrNull()
    var fruitMenu by remember {
        mutableStateOf(false)
    }

    val prices =
        remember(
            dataVersion,
            fruit?.id,
            queryStart,
            queryEnd
        ) {
            fruit?.let {
                db.getFruitPriceHistoryBetween(
                    fruitId = it.id,
                    start = queryStart,
                    end = queryEnd,
                    limit = 100
                )
            } ?: emptyList()
        }

    val context =
        androidx.compose.ui.platform.LocalContext.current
    var exportMessage by remember {
        mutableStateOf("")
    }
    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/json"
            )
        ) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver
                        .openOutputStream(uri)
                        ?.bufferedWriter(Charsets.UTF_8)
                        ?.use {
                            it.write(db.exportJson())
                        }
                }
                    .onSuccess {
                        exportMessage = "备份已导出"
                    }
                    .onFailure {
                        exportMessage =
                            "导出失败：${it.message}"
                    }
            }
        }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement =
            Arrangement.spacedBy(10.dp)
    ) {
        item {
            TimeFilterSelector(
                filter = filter,
                onFilterChange = {
                    filter = it
                },
                customStart = customStart,
                onCustomStart = {
                    customStart = it
                },
                customEnd = customEnd,
                onCustomEnd = {
                    customEnd = it
                }
            )
        }

        if (invalidCustom) {
            item {
                Text(
                    "开始日期不能晚于结束日期",
                    color =
                        MaterialTheme.colorScheme.error
                )
            }
        }

        item {
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                MetricCard(
                    "营业额",
                    money(totalRevenue),
                    Modifier.weight(1f),
                    SoftGreen
                )
                MetricCard(
                    "利润",
                    money(totalProfit),
                    Modifier.weight(1f),
                    SoftOrange
                )
            }
        }

        item {
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                MetricCard(
                    "进货金额",
                    money(totalPurchase),
                    Modifier.weight(1f),
                    SoftPurple
                )
                MetricCard(
                    "客户数",
                    "$totalCustomers 人",
                    Modifier.weight(1f),
                    SoftBlue
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor =
                        Color(0xFFF8FAFC)
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    SummaryRow(
                        "经营天数",
                        "$activeDays 天"
                    )
                    SummaryRow(
                        "日均营业额",
                        money(
                            if (activeDays > 0) {
                                totalRevenue / activeDays
                            } else {
                                0.0
                            }
                        )
                    )
                    SummaryRow(
                        "日均利润",
                        money(
                            if (activeDays > 0) {
                                totalProfit / activeDays
                            } else {
                                0.0
                            }
                        )
                    )
                }
            }
        }

        item {
            RankingSection(
                "营业额排行榜",
                rankings.sortedByDescending {
                    it.revenue
                }
            ) {
                money(it.revenue)
            }
        }

        item {
            Text(
                "利润排行中的共用进货按各摊位营业额比例分摊。",
                style =
                    MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
            RankingSection(
                "利润排行榜",
                rankings.sortedByDescending {
                    it.profit
                }
            ) {
                money(it.profit)
            }
        }

        item {
            RankingSection(
                "客户数排行榜",
                rankings.sortedByDescending {
                    it.customers
                }
            ) {
                "${it.customers} 人"
            }
        }

        item {
            Text(
                "商品历史进价",
                style =
                    MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Box {
                OutlinedButton(
                    onClick = {
                        fruitMenu = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        fruit?.name ?: "请选择商品",
                        Modifier.weight(1f)
                    )
                    Text("▼")
                }

                DropdownMenu(
                    expanded = fruitMenu,
                    onDismissRequest = {
                        fruitMenu = false
                    }
                ) {
                    fruits.forEach { f ->
                        DropdownMenuItem(
                            text = {
                                Text(f.name)
                            },
                            onClick = {
                                fruitId = f.id
                                fruitMenu = false
                            }
                        )
                    }
                }
            }

            if (prices.isEmpty()) {
                Text(
                    "当前时间范围没有该商品的进货记录。",
                    color = Color.Gray,
                    style =
                        MaterialTheme.typography.bodySmall
                )
            }

            prices.forEach { p ->
                Column(
                    Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        "${p.date} · " +
                            "${money(p.unitPrice)}/${p.unit}",
                        fontWeight =
                            FontWeight.SemiBold
                    )
                    Text(
                        "${p.buyerName} · " +
                            "${fmt(p.quantity)}${p.unit}",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    exportLauncher.launch(
                        "天鲜果业备份_" +
                            "${LocalDate.now()}.json"
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导出 JSON 备份")
            }

            if (exportMessage.isNotBlank()) {
                Text(
                    exportMessage,
                    color = BrandGreen
                )
            }
        }
    }
}

@Composable
private fun PartnerContent(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    val partners = remember(dataVersion) { db.getPartners() }
    var addDialog by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<PartnerOption?>(null) }
    var delete by remember { mutableStateOf<PartnerOption?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("当前 ${partners.size} 位合伙人，人数不设上限。删除只代表今后不再参与新记录；历史进货、营业、利润和结算仍保留原 ID 与当时姓名，并可继续编辑。", color = Color.Gray) }
        items(partners, key = { it.id }) { p ->
            RecordCard {
                Text(p.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { edit = p }) { Text("改名") }
                TextButton(onClick = { delete = p }) { Text("删除") }
            }
        }
        item { Button(onClick = { addDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加合伙人") } }
    }
    if (addDialog) PartnerNameDialog("添加合伙人", "", { addDialog = false }) { name ->
        db.addPartner(name); addDialog = false; onChanged()
    }
    edit?.let { p ->
        PartnerNameDialog("修改合伙人名称", p.name, { edit = null }) { name ->
            db.updatePartnerName(p.id, name); edit = null; onChanged()
        }
    }
    delete?.let { p -> ConfirmDelete("删除合伙人“${p.name}”？删除后不再出现在新记录选择列表中；已有进货、营业、利润分配和结算历史继续保留，并可编辑原历史记录。", { delete = null }) {
        db.deletePartner(p.id); delete = null; onChanged()
    } }
}

@Composable
private fun FruitManagementContent(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    val fruits = remember(dataVersion) { db.getFruitAdminRecords() }
    var addDialog by remember { mutableStateOf(false) }
    var editFruit by remember { mutableStateOf<FruitAdminRecord?>(null) }
    var disableFruit by remember { mutableStateOf<FruitAdminRecord?>(null) }
    var message by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "停用后的水果不会出现在新的采购计划和进货选择中，但历史进货、历史价格和采购计划仍保留原商品名。",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }

        items(fruits, key = { it.id }) { f ->
            RecordCard {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name, fontWeight = FontWeight.Bold)
                        if (!f.enabled) {
                            Spacer(Modifier.width(6.dp))
                            Text("已停用", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(
                        "默认单位：${f.defaultUnit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }

                if (f.enabled) {
                    TextButton(onClick = { editFruit = f }) { Text("编辑") }
                    TextButton(onClick = { disableFruit = f }) { Text("删除") }
                } else {
                    TextButton(
                        onClick = {
                            if (db.restoreFruit(f.id)) {
                                message = "${f.name} 已恢复"
                                onChanged()
                            }
                        }
                    ) { Text("恢复") }
                }
            }
        }

        item {
            Button(
                onClick = { addDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("＋ 新增商品") }

            if (message.isNotBlank()) {
                Text(message, color = BrandGreen, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (addDialog) {
        AddFruitDialog(
            onDismiss = { addDialog = false },
            onSave = { name, unit ->
                val id = db.addFruit(name, unit)
                addDialog = false
                message = if (id > 0) "商品已保存" else "保存失败，商品名称可能重复"
                onChanged()
            }
        )
    }

    editFruit?.let { f ->
        FruitEditDialog(
            fruit = f,
            onDismiss = { editFruit = null },
            onSave = { name, unit ->
                val ok = db.updateFruit(f.id, name, unit)
                message = if (ok) "商品已修改" else "修改失败"
                editFruit = null
                onChanged()
            }
        )
    }

    disableFruit?.let { f ->
        ConfirmDelete(
            "停用“${f.name}”？它将不再出现在新的采购/进货列表中，历史记录不会删除。",
            { disableFruit = null }
        ) {
            if (db.disableFruit(f.id)) {
                message = "${f.name} 已停用"
                onChanged()
            }
            disableFruit = null
        }
    }
}

@Composable
private fun StoreContent(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    val stores = remember(dataVersion) { db.getStores() }
    var addDialog by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<StoreOption?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(stores, key = { it.id }) { s ->
            RecordCard {
                Column(Modifier.weight(1f)) { Text(s.name, fontWeight = FontWeight.Bold); if (s.address.isNotBlank()) Text(s.address, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                TextButton(onClick = { delete = s }) { Text("删除") }
            }
        }
        item { Button(onClick = { addDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ 新增摆摊位置") } }
    }
    if (addDialog) AddStoreDialog({ addDialog = false }) { name, address -> db.addStore(name, address); addDialog = false; onChanged() }
    delete?.let { s -> ConfirmDelete("删除位置“${s.name}”？历史营业和进货记录不会被删除。", { delete = null }) { db.deleteStore(s.id); delete = null; onChanged() } }
}

@Composable
private fun ProfitContent(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val partners = remember(dataVersion) { db.getPartners() }
    val savedRules = remember(dataVersion) { db.getProfitRules() }
    val summary = remember(dataVersion, date) { db.getDailySummary(date) }
    val percentages = remember { mutableStateMapOf<Long, String>() }
    var message by remember { mutableStateOf("") }
    var deleteDate by remember { mutableStateOf<String?>(null) }
    val saved = remember(dataVersion, date) { db.getProfitDistribution(date) }
    val history = remember(dataVersion) { db.getRecentProfitDistributions(200).groupBy { it.date }.toSortedMap(reverseOrder()) }

    LaunchedEffect(dataVersion, partners.map { it.id }) {
        val ruleMap = savedRules.associate { it.partnerId to it.percent }
        percentages.keys.retainAll(partners.map { it.id }.toSet())
        if (ruleMap.isNotEmpty()) {
            partners.forEach { p -> percentages[p.id] = cleanPercent(ruleMap[p.id] ?: 0.0) }
        } else if (partners.size == 4) {
            val defaults = listOf(33.0, 13.4, 26.8, 26.8)
            partners.forEachIndexed { index, p -> percentages[p.id] = cleanPercent(defaults[index]) }
        } else if (partners.isNotEmpty()) {
            val equal = 100.0 / partners.size
            var used = 0.0
            partners.forEachIndexed { index, p ->
                val value = if (index == partners.lastIndex) 100.0 - used else roundPercent(equal)
                percentages[p.id] = cleanPercent(value)
                used += value
            }
        }
    }

    val totalPercent = partners.sumOf { percentages[it.id]?.toDoubleOrNull() ?: 0.0 }
    val allocations = partners.map { p -> p to (percentages[p.id]?.toDoubleOrNull() ?: 0.0) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("本表与营业、收款、总账完全分开。百分比规则可自定义并保存；历史分配冻结当时比例。", color = Color.Gray) }
        item { DateField("分配日期", date) { date = it } }
        item { MetricCard("当天可分利润", money(summary.profit), Modifier.fillMaxWidth(), SoftOrange) }
        item {
            Text("分配百分比", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("当前合计：${String.format(Locale.CHINA, "%.2f", totalPercent)}%（必须等于100%）", color = if (kotlin.math.abs(totalPercent - 100.0) < 0.01) BrandGreen else MaterialTheme.colorScheme.error)
        }
        items(partners, key = { "rule${it.id}" }) { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = percentages[p.id] ?: "",
                    onValueChange = { v -> if (v.matches(Regex("^\\d*(\\.\\d{0,2})?$"))) percentages[p.id] = v },
                    modifier = Modifier.width(110.dp).height(50.dp),
                    suffix = { Text("%") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (partners.isEmpty()) message = "请先添加合伙人"
                    else if (kotlin.math.abs(totalPercent - 100.0) >= 0.01) message = "百分比合计必须等于100%"
                    else {
                        db.saveProfitRules(allocations)
                        message = "利润分配百分比规则已保存"
                        onChanged()
                    }
                }, modifier = Modifier.weight(1f)) { Text("保存百分比规则") }
                Button(onClick = {
                    if (summary.profit <= 0) message = "当天利润必须大于0才能生成利润分配"
                    else if (partners.isEmpty()) message = "请先添加合伙人"
                    else if (kotlin.math.abs(totalPercent - 100.0) >= 0.01) message = "百分比合计必须等于100%"
                    else {
                        val ok = db.saveProfitDistribution(date, allocations)
                        message = if (ok) "利润分配表已独立保存" else "保存失败"
                        if (ok) onChanged()
                    }
                }, modifier = Modifier.weight(1f)) { Text("保存当日分配") }
            }
            if (message.isNotBlank()) Text(message, color = BrandGreen, modifier = Modifier.padding(top = 6.dp))
        }
        if (summary.profit > 0 && partners.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SoftGreen)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("分配预览", fontWeight = FontWeight.Bold)
                        allocations.forEach { (p, pct) -> SummaryRow("${p.name} · ${cleanPercent(pct)}%", money(summary.profit * pct / 100.0)) }
                    }
                }
            }
        }
        if (saved.isNotEmpty()) {
            item {
                RecordCard {
                    Column(Modifier.weight(1f)) {
                        Text("当前日期已保存", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("利润基数：${money(saved.first().sourceProfit)}", style = MaterialTheme.typography.bodySmall)
                        saved.forEach { r -> Text("${r.partnerName} · ${cleanPercent(r.ratio * 100)}%：${money(r.allocatedProfit)}", style = MaterialTheme.typography.bodySmall) }
                    }
                    TextButton(onClick = { deleteDate = date }) { Text("删除") }
                }
            }
        }
        item { HorizontalDivider(); Text("利润分配历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        history.entries.take(30).forEach { (d, rows) ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("$d · 利润 ${money(rows.firstOrNull()?.sourceProfit ?: 0.0)}", fontWeight = FontWeight.Bold)
                            rows.forEach { r -> Text("${r.partnerName} ${cleanPercent(r.ratio * 100)}%：${money(r.allocatedProfit)}", style = MaterialTheme.typography.bodySmall) }
                        }
                        TextButton(onClick = { deleteDate = d }) { Text("删除") }
                    }
                }
            }
        }
    }
    deleteDate?.let { d ->
        ConfirmDelete("删除 $d 的整张利润分配历史？不会删除当天营业和总账。", { deleteDate = null }) {
            db.deleteProfitDistribution(d); deleteDate = null; onChanged()
        }
    }
}

private fun roleLabel(role: String): String = when (role) {
    "CUSTOM_PERCENT" -> "自定义比例"
    "PRIMARY_33" -> "33%"
    "SMALL_1" -> "小份1"
    "LARGE_2" -> "大份2"
    else -> role
}

@Composable
private fun RankingSection(title: String, rankings: List<RankingRecord>, value: (RankingRecord) -> String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (rankings.isEmpty()) Text("暂无数据", color = Color.Gray)
        rankings.take(10).forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.", Modifier.width(28.dp), fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f)) { Text(r.storeName); Text("${r.days} 个营业日", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                Text(value(r), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text(value, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold)
    }
}

@Composable
private fun SelectButton(label: String, value: String, modifier: Modifier = Modifier, expanded: Boolean, onExpanded: (Boolean) -> Unit) {
    OutlinedButton(onClick = { onExpanded(!expanded) }, modifier = modifier) {
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray); Text(value, maxLines = 1) }
        Text("▼")
    }
}

@Composable
private fun DateField(label: String, date: String, onDate: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    OutlinedButton(onClick = { showDatePicker(context, date, onDate) }, modifier = Modifier.fillMaxWidth()) {
        Text("$label：$date", Modifier.weight(1f)); Text("选择日期")
    }
}

private fun showDatePicker(context: Context, current: String, onDate: (String) -> Unit) {
    val d = runCatching { LocalDate.parse(current) }.getOrDefault(LocalDate.now())
    DatePickerDialog(context, { _, y, m, day -> onDate(LocalDate.of(y, m + 1, day).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
}

@Composable
private fun NumberField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = { if (it.matches(Regex("^\\d*(\\.\\d{0,2})?$"))) onValue(it) },
        label = { Text(label) }, modifier = modifier, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

@Composable
private fun IntegerField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = { if (it.all(Char::isDigit)) onValue(it) },
        label = { Text(label) }, modifier = modifier, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun CollectorSelector(partners: List<PartnerOption>, selectedId: Long?, onSelected: (Long?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
            Text("收款归属", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Spacer(Modifier.width(10.dp))
            Text(partners.firstOrNull { it.id == selectedId }?.name ?: "未指定", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text("▼")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            partners.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { onSelected(p.id); menu = false }) }
            DropdownMenuItem(text = { Text("未指定") }, onClick = { onSelected(null); menu = false })
        }
    }
}

@Composable
private fun CompactSelectButton(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(40.dp),
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp)
        ) {
            Text(value, modifier = Modifier.weight(1f), maxLines = 1, fontSize = 13.sp)
            Text("▼", fontSize = 10.sp)
        }
    }
}

private fun resolveTimeRange(
    filter: HistoryTimeFilter,
    customStart: String,
    customEnd: String,
    today: LocalDate = LocalDate.now()
): Pair<String?, String?> =
    when (filter) {
        HistoryTimeFilter.ALL ->
            null to null

        HistoryTimeFilter.TODAY ->
            today.toString() to today.toString()

        HistoryTimeFilter.YESTERDAY -> {
            val d = today.minusDays(1)
            d.toString() to d.toString()
        }

        HistoryTimeFilter.LAST_7 ->
            today.minusDays(6).toString() to
                today.toString()

        HistoryTimeFilter.LAST_30 ->
            today.minusDays(29).toString() to
                today.toString()

        HistoryTimeFilter.THIS_MONTH ->
            today.withDayOfMonth(1).toString() to
                today.toString()

        HistoryTimeFilter.LAST_MONTH -> {
            val firstThisMonth =
                today.withDayOfMonth(1)
            val firstLastMonth =
                firstThisMonth.minusMonths(1)
            firstLastMonth.toString() to
                firstThisMonth
                    .minusDays(1)
                    .toString()
        }

        HistoryTimeFilter.CUSTOM ->
            customStart to customEnd
    }

@Composable
private fun TimeFilterSelector(
    filter: HistoryTimeFilter,
    onFilterChange: (HistoryTimeFilter) -> Unit,
    customStart: String,
    onCustomStart: (String) -> Unit,
    customEnd: String,
    onCustomEnd: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var menu by remember {
        mutableStateOf(false)
    }

    val range =
        resolveTimeRange(
            filter,
            customStart,
            customEnd
        )

    Column(
        modifier,
        verticalArrangement =
            Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            CompactSelectButton(
                "时间范围",
                filter.label,
                Modifier.fillMaxWidth()
            ) {
                menu = true
            }

            DropdownMenu(
                expanded = menu,
                onDismissRequest = {
                    menu = false
                }
            ) {
                HistoryTimeFilter.entries.forEach {
                    option ->
                    DropdownMenuItem(
                        text = {
                            Text(option.label)
                        },
                        onClick = {
                            onFilterChange(option)
                            menu = false
                        }
                    )
                }
            }
        }

        if (filter == HistoryTimeFilter.CUSTOM) {
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                CompactDateSelector(
                    "开始日期",
                    customStart,
                    Modifier.weight(1f),
                    onCustomStart
                )
                CompactDateSelector(
                    "结束日期",
                    customEnd,
                    Modifier.weight(1f),
                    onCustomEnd
                )
            }
        } else if (
            range.first != null &&
            range.second != null
        ) {
            Text(
                "${range.first} ～ ${range.second}",
                color = Color.Gray,
                style =
                    MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CompactDateNavigator(
    label: String?,
    date: String,
    modifier: Modifier = Modifier,
    chineseDisplay: Boolean = false,
    showWeekday: Boolean = false,
    onDate: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val parsedDate =
        runCatching { LocalDate.parse(date) }
            .getOrElse { LocalDate.now() }

    val displayText =
        if (chineseDisplay) {
            buildString {
                append(
                    parsedDate.format(
                        DateTimeFormatter.ofPattern("yyyy年M月d日")
                    )
                )
                if (showWeekday) {
                    append("  ")
                    append(chineseWeekday(parsedDate))
                }
            }
        } else {
            parsedDate.toString()
        }

    Column(modifier) {
        if (!label.isNullOrBlank()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            IconButton(
                onClick = {
                    onDate(parsedDate.minusDays(1).toString())
                },
                modifier = Modifier.size(36.dp)
            ) {
                Text(
                    "‹",
                    color = BrandGreen,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = {
                    showDatePicker(
                        context,
                        parsedDate.toString(),
                        onDate
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    displayText,
                    modifier = Modifier.weight(1f),
                    fontSize = if (chineseDisplay) 13.sp else 12.sp,
                    maxLines = 1
                )
                Text(
                    "📅",
                    fontSize = 11.sp
                )
            }

            IconButton(
                onClick = {
                    onDate(parsedDate.plusDays(1).toString())
                },
                modifier = Modifier.size(36.dp)
            ) {
                Text(
                    "›",
                    color = BrandGreen,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CompactDateSelector(label: String, date: String, modifier: Modifier = Modifier, onDate: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        OutlinedButton(
            onClick = { showDatePicker(context, date, onDate) },
            modifier = Modifier.fillMaxWidth().height(40.dp),
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp)
        ) {
            Text(date, modifier = Modifier.weight(1f), fontSize = 13.sp)
            Text("📅", fontSize = 12.sp)
        }
    }
}

@Composable
private fun CompactNumberField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    CompactBasicField(
        label = label,
        value = value,
        modifier = modifier,
        keyboardType = KeyboardType.Decimal,
        accept = { it.matches(Regex("^\\d*(\\.\\d{0,2})?$")) },
        onValue = onValue
    )
}

@Composable
private fun CompactIntegerField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    CompactBasicField(
        label = label,
        value = value,
        modifier = modifier,
        keyboardType = KeyboardType.Number,
        accept = { it.all(Char::isDigit) },
        onValue = onValue
    )
}

@Composable
private fun CompactBasicField(
    label: String,
    value: String,
    modifier: Modifier,
    keyboardType: KeyboardType,
    accept: (String) -> Boolean,
    onValue: (String) -> Unit
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1)
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFFB8BDC5), RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = { if (accept(it)) onValue(it) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = Color(0xFF222222)),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CompactReadOnlyField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1)
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFFD5D9DE), RoundedCornerShape(8.dp))
                .background(Color(0xFFF7F8FA))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(value, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun CompactTextField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Box(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFFB8BDC5), RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = Color(0xFF222222)),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MiniSummaryCard(title: String, value: String, modifier: Modifier = Modifier, color: Color = SoftGreen) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = color), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = Color.DarkGray)
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun cleanPercent(v: Double): String = if (kotlin.math.abs(v - v.toLong()) < 0.0001) v.toLong().toString() else String.format(Locale.CHINA, "%.2f", v).trimEnd('0').trimEnd('.')
private fun roundPercent(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

@Composable
private fun RecordCard(content: @Composable RowScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, content = content) }
}

@Composable
private fun AddFruitDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("件") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("新增商品") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("商品名称") }, singleLine = true)
                Text("默认单位")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("斤", "筐", "箱", "件").forEach { u -> FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) }) } }
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(name, unit) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun FruitEditDialog(
    fruit: FruitAdminRecord,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember(fruit.id) { mutableStateOf(fruit.name) }
    var unit by remember(fruit.id) { mutableStateOf(fruit.defaultUnit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑商品") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("商品名称") },
                    singleLine = true
                )
                Text("默认单位")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("斤", "筐", "箱", "件").forEach { u ->
                        FilterChip(
                            selected = unit == u,
                            onClick = { unit = u },
                            label = { Text(u) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onSave(name.trim(), unit) }) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AddStoreDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("新增摆摊位置") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("位置简称，例如：龙归A摊") }, singleLine = true)
            OutlinedTextField(address, { address = it }, label = { Text("详细位置（可选）") })
        } },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(name, address) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PartnerNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("姓名/称呼") }, singleLine = true) },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(name) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ConfirmActionDialog(
    title: String,
    text: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title)
        },
        text = {
            Text(text)
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ConfirmDelete(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("确认删除") }, text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun money(v: Double): String = "¥" + if (kotlin.math.abs(v - v.toLong()) < 0.005) v.toLong().toString() else String.format(Locale.CHINA, "%.2f", v)
private fun fmt(v: Double): String = if (kotlin.math.abs(v - v.toLong()) < 0.005) v.toLong().toString() else String.format(Locale.CHINA, "%.2f", v)
private fun cleanNumber(v: Double): String = if (v == 0.0) "" else fmt(v)
