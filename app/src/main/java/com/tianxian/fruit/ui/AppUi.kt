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

private enum class MorePage { MENU, HISTORY, STATS, PARTNERS, STORES, PROFIT, FRUITS }

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
                    AppPage.MORE -> MoreScreen(db, dataVersion, moreTarget) { dataVersion++ }
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
    val todayDate = LocalDate.now()
    val today = todayDate.toString()
    val summary = remember(dataVersion, today) { db.getDailySummary(today) }
    val records = remember(dataVersion, today) { db.getDailyRecords(today) }
    val tomorrow = todayDate.plusDays(1).toString()
    val tomorrowPlan = remember(dataVersion, tomorrow) { db.getPurchasePlan(tomorrow) }
    val trend = remember(dataVersion, today) {
        (6 downTo 0).map { offset ->
            val d = todayDate.minusDays(offset.toLong())
            d to db.getDailySummary(d.toString()).revenue
        }
    }
    val month = currentMonthRange()
    val rankings = remember(dataVersion, month) {
        db.getRankings(month.first, month.second).sortedByDescending { it.revenue }
    }

    val dateText = todayDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    val weekText = chineseWeekday(todayDate)
    val locationText = if (records.isEmpty()) "今日未记录摊位"
    else records.joinToString("、") { it.storeName }.take(24)

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
                            listOf(Color(0xFF0A6E3A), Color(0xFF148E51), Color(0xFF0B5E34))
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Column(Modifier.align(Alignment.CenterStart)) {
                    Text("天鲜果业", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("每一天努力，收获更甜的生活", color = Color.White.copy(alpha = 0.88f), fontSize = 13.sp)
                }
                Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.End) {
                    Text("🍇🍊🍓", fontSize = 24.sp)
                    Text("新鲜水果 · 从这里开始！", color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
                }
                TextButton(onClick = onMore, modifier = Modifier.align(Alignment.TopEnd)) {
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
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📅", fontSize = 17.sp)
                            Spacer(Modifier.width(6.dp))
                            Text("$dateText  $weekText", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("📍", fontSize = 17.sp)
                            Spacer(Modifier.width(4.dp))
                            Text(locationText, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            DashboardTile("💰", "今日营业额", money(summary.revenue), SoftOrange, Modifier.weight(1f))
                            DashboardTile("📈", "今日利润", money(summary.profit), SoftGreen, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            DashboardTile("🛒", "今日进货成本", money(summary.purchaseCost), SoftBlue, Modifier.weight(1f))
                            DashboardTile("📦", "剩余库存价值", money(summary.closingStockValue), Color(0xFFFFF7D9), Modifier.weight(1f))
                        }

                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9FC))) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("👥 今日客户", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                Text("${summary.customers} 人", fontWeight = FontWeight.Bold, color = BrandGreen)
                                if (records.isNotEmpty()) {
                                    Spacer(Modifier.width(12.dp))
                                    Text("新 ${records.sumOf { it.newCustomer }}  ·  老 ${records.sumOf { it.oldCustomer }}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                            }
                        }

                        Text("快捷操作", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuickActionTile("🛒", "新增进货", onPurchase, Modifier.weight(1f), Color(0xFFEAF8F0))
                            QuickActionTile("🏪", "记录营业", onSession, Modifier.weight(1f), Color(0xFFEAF3FF))
                            QuickActionTile("🛒", "采购计划", onPlan, Modifier.weight(1f), Color(0xFFFFEFE5))
                            QuickActionTile("🧾", "查看历史", onHistory, Modifier.weight(1f), Color(0xFFF2ECFF))
                        }

                        Card(
                            onClick = onPlan,
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF4FAF6)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🛒", fontSize = 22.sp)
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("明日采购", fontWeight = FontWeight.Bold)
                                    val planText = if (tomorrowPlan == null) {
                                        "暂无计划，点击添加"
                                    } else {
                                        val pendingCount = tomorrowPlan.items.count { it.status == 0 }
                                        val purchasedCount = tomorrowPlan.items.count { it.status == 1 }
                                        val cancelledCount = tomorrowPlan.items.count { it.status == 2 }
                                        when {
                                            tomorrowPlan.items.isEmpty() -> "暂无商品"
                                            pendingCount > 0 ->
                                                "待采购 $pendingCount · 已采购 $purchasedCount · 取消 $cancelledCount"
                                            purchasedCount > 0 ->
                                                "已完成 $purchasedCount 项" +
                                                    if (cancelledCount > 0) " · 取消 $cancelledCount" else ""
                                            else -> "已全部取消"
                                        }
                                    }
                                    Text(planText, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                Text("›", color = BrandGreen, fontSize = 24.sp)
                            }
                        }

                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFCFCFD))) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("最近7天营业额趋势", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    TextButton(onClick = onStats) { Text("查看详情 ›") }
                                }
                                RevenueTrendChart(trend)
                                Row(Modifier.fillMaxWidth()) {
                                    trend.forEach { (d, _) ->
                                        Text(
                                            "${d.monthValue}/${d.dayOfMonth}",
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }
                        }

                        Text("常用功能", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            UtilityTile("🍎", "水果管理", onFruits, Modifier.weight(1f))
                            UtilityTile("📍", "位置管理", onStores, Modifier.weight(1f))
                            UtilityTile("📊", "统计分析", onStats, Modifier.weight(1f))
                            UtilityTile("☁", "数据备份", onStats, Modifier.weight(1f))
                        }

                        if (rankings.isNotEmpty()) {
                            Text("本月营业额前三", fontWeight = FontWeight.Bold)
                            rankings.take(3).forEachIndexed { index, r ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${index + 1}.", Modifier.width(28.dp), fontWeight = FontWeight.Bold)
                                    Text(r.storeName, Modifier.weight(1f))
                                    Text(money(r.revenue), fontWeight = FontWeight.SemiBold)
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
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val fruits = remember(dataVersion) { db.getFruits() }
    val partners = remember(dataVersion) { db.getPartners() }

    var buyerId by remember { mutableStateOf<Long?>(null) }
    var historicalBuyerName by remember { mutableStateOf("") }

    var fruitId by remember { mutableStateOf<Long?>(null) }
    val fruit = fruits.firstOrNull { it.id == fruitId }
    var unit by remember { mutableStateOf("件") }
    var quantity by remember { mutableStateOf("") }
    var totalCost by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }

    var buyerMenu by remember { mutableStateOf(false) }
    var fruitMenu by remember { mutableStateOf(false) }
    var unitMenu by remember { mutableStateOf(false) }
    var addFruitDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val draft = remember { mutableStateListOf<PurchaseLineInput>() }

    var editingDraftIndex by remember { mutableStateOf<Int?>(null) }
    var editingOrderId by remember { mutableStateOf<Long?>(null) }
    var deleteOrder by remember { mutableStateOf<PurchaseOrderDetail?>(null) }
    val history = remember(dataVersion) { db.getPurchaseOrders(50) }

    val activeBuyer = partners.firstOrNull { it.id == buyerId }
    val historicalBuyer = if (editingOrderId != null && buyerId != null && activeBuyer == null) {
        PartnerOption(
            buyerId!!,
            historicalBuyerName.ifBlank {
                db.getPartnerByIdIncludingDeleted(buyerId!!)?.name ?: "已删除合伙人"
            }
        )
    } else null

    val buyer = activeBuyer ?: historicalBuyer ?: if (editingOrderId == null) partners.firstOrNull() else null

    val buyerDisplayName = when {
        activeBuyer != null -> activeBuyer.name
        historicalBuyer != null -> "${historicalBuyer.name}（已删除）"
        else -> "请先添加"
    }

    LaunchedEffect(dataVersion, partners.size) {
        if (buyerId == null && partners.isNotEmpty()) buyerId = partners.first().id
    }

    val q = quantity.toDoubleOrNull() ?: 0.0
    val cost = totalCost.toDoubleOrNull() ?: 0.0
    val price = if (q > 0) cost / q else 0.0

    val planForDate = remember(dataVersion, date) { db.getPurchasePlan(date) }
    val purchasedPlanItems = planForDate?.items?.filter { it.status == 1 }.orEmpty()

    fun clearProductEditor() {
        fruitId = null
        unit = "件"
        quantity = ""
        totalCost = ""
        editingDraftIndex = null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { PageHeader("批量进货", "连续添加商品，最后一次保存整张进货单") }

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
                        TextButton(onClick = {
                            editingOrderId = null
                            draft.clear()
                            remark = ""
                            clearProductEditor()
                            date = LocalDate.now().toString()
                            buyerId = partners.firstOrNull()?.id
                            historicalBuyerName = ""
                            message = "已取消编辑"
                        }) { Text("取消") }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                CompactDateSelector("日期", date, Modifier.weight(1.05f)) { date = it }
                Box(Modifier.weight(0.95f)) {
                    CompactSelectButton(
                        "进货人",
                        buyerDisplayName,
                        Modifier.fillMaxWidth()
                    ) { buyerMenu = true }

                    DropdownMenu(expanded = buyerMenu, onDismissRequest = { buyerMenu = false }) {
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
                        var added = 0
                        purchasedPlanItems.forEach { item ->
                            val f = fruits.firstOrNull { it.id == item.fruitId }
                                ?: FruitOption(item.fruitId, item.fruitName, item.unit)

                            val existingIndex = draft.indexOfFirst {
                                it.fruit.id == item.fruitId && it.unit == item.unit
                            }

                            val imported = PurchaseLineInput(
                                fruit = f,
                                unit = item.unit,
                                quantity = item.quantity,
                                totalCost = 0.0
                            )

                            if (existingIndex >= 0) {
                                draft[existingIndex] = imported
                            } else {
                                draft.add(imported)
                            }
                            added++
                        }
                        message = "已导入 $added 项已采购商品，请逐项填写实际总价"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🛒 从采购计划导入已采购商品（${purchasedPlanItems.size}项）")
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    CompactSelectButton(
                        "水果",
                        fruit?.name ?: "请选择水果",
                        Modifier.fillMaxWidth()
                    ) { fruitMenu = true }

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

                Box(Modifier.width(78.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Bottom) {
                CompactNumberField("数量", quantity, { quantity = it }, Modifier.weight(0.85f))
                CompactNumberField("总价", totalCost, { totalCost = it }, Modifier.weight(1f))
                CompactReadOnlyField(
                    "单价/$unit",
                    if (q > 0 && cost > 0) money(price) else "—",
                    Modifier.weight(1f)
                )
            }
        }

        item {
            Button(
                onClick = {
                    if (fruit == null || q <= 0 || cost <= 0) {
                        message = "请选择水果，并填写正确的数量和总价"
                    } else {
                        val line = PurchaseLineInput(fruit, unit, q, cost)
                        val editIndex = editingDraftIndex

                        if (editIndex != null && editIndex in draft.indices) {
                            draft[editIndex] = line
                            message = "${fruit.name} 已修改，可继续选择下一种水果"
                        } else {
                            draft.add(line)
                            message = "${fruit.name} 已添加，可继续选择下一种水果"
                        }

                        clearProductEditor()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(if (editingDraftIndex == null) "＋ 添加商品" else "保存商品修改")
            }
        }

        if (draft.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "本次进货 ${draft.size} 项",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "合计 ${money(draft.sumOf { it.totalCost })}",
                        fontWeight = FontWeight.Bold,
                        color = BrandGreen
                    )
                }
            }

            items(draft.indices.toList()) { index ->
                val d = draft[index]
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.fruit.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${fmt(d.quantity)}${d.unit} · ${money(d.totalCost)} · " +
                                    if (d.quantity > 0) "${money(d.totalCost / d.quantity)}/${d.unit}"
                                    else "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (d.totalCost > 0) Color.Unspecified else MaterialTheme.colorScheme.error
                            )
                            if (d.totalCost <= 0) {
                                Text(
                                    "请编辑填写实际总价",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        TextButton(
                            onClick = {
                                editingDraftIndex = index
                                fruitId = d.fruit.id
                                unit = d.unit
                                quantity = cleanNumber(d.quantity)
                                totalCost = if (d.totalCost > 0) cleanNumber(d.totalCost) else ""
                                message = "正在修改 ${d.fruit.name}"
                            }
                        ) { Text("编辑") }

                        TextButton(
                            onClick = {
                                draft.removeAt(index)
                                val current = editingDraftIndex
                                if (current == index) {
                                    clearProductEditor()
                                } else if (current != null && current > index) {
                                    editingDraftIndex = current - 1
                                }
                            }
                        ) { Text("删除") }
                    }
                }
            }

            item {
                CompactTextField("备注（可选）", remark, { remark = it }, Modifier.fillMaxWidth())

                Button(
                    onClick = {
                        when {
                            buyer == null -> message = "请先在更多页面添加合伙人"
                            draft.isEmpty() -> message = "请先添加商品"
                            draft.any { it.quantity <= 0 || it.totalCost <= 0 } ->
                                message = "还有商品未填写正确的数量或总价，请先编辑补充"
                            else -> {
                                val editId = editingOrderId
                                val ok = if (editId == null) {
                                    db.addPurchaseOrder(date, buyer, draft.toList(), remark) > 0
                                } else {
                                    db.updatePurchaseOrder(editId, date, buyer, draft.toList(), remark)
                                }

                                if (ok) {
                                    draft.clear()
                                    remark = ""
                                    editingOrderId = null
                                    historicalBuyerName = ""
                                    clearProductEditor()
                                    message =
                                        if (editId == null) "整张进货单已保存"
                                        else "进货单已更新"
                                    onChanged()
                                } else {
                                    message = "保存失败，请检查内容"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                ) {
                    Text(if (editingOrderId == null) "保存整张进货单" else "保存修改")
                }
            }
        }

        if (message.isNotBlank()) {
            item { Text(message, color = BrandGreen, style = MaterialTheme.typography.bodySmall) }
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

        items(history, key = { it.order.id }) { detail ->
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

                        Row {
                            TextButton(onClick = {
                                editingOrderId = detail.order.id
                                date = detail.order.date
                                buyerId = detail.order.buyerId
                                historicalBuyerName = detail.order.buyerName
                                remark = detail.order.remark
                                draft.clear()

                                detail.items.forEach { item ->
                                    val f = fruits.firstOrNull { it.id == item.fruitId }
                                        ?: FruitOption(item.fruitId, item.fruitName, item.unit)
                                    draft.add(
                                        PurchaseLineInput(
                                            f,
                                            item.unit,
                                            item.quantity,
                                            item.totalCost
                                        )
                                    )
                                }

                                clearProductEditor()
                                message = "已载入历史进货单，可在上方修改"
                            }) { Text("编辑") }

                            TextButton(onClick = { deleteOrder = detail }) { Text("删除") }
                        }
                    }

                    detail.items.forEach { item ->
                        Text(
                            "• ${item.fruitName}  ${fmt(item.quantity)}${item.unit}  " +
                                "${money(item.totalCost)}  (${money(item.unitPrice)}/${item.unit})",
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
                if (id > 0) {
                    fruitId = id
                    unit = "件"
                }
                onChanged()
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
                draft.clear()
                remark = ""
                clearProductEditor()
            }
            deleteOrder = null
            onChanged()
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

        item { CompactDateSelector("营业日期", date, Modifier.fillMaxWidth()) { date = it } }

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
private fun SettlementScreen(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var message by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<Long?>(null) }

    val summary = remember(dataVersion, date) { db.getDailySummary(date) }
    val purchases = remember(dataVersion, date) { db.getPurchaseTotalsByPartner(date) }
    val receipts = remember(dataVersion, date) { db.getReceiptsByPartner(date) }
    val expenses = remember(dataVersion, date) { db.getExpenseTotalsByPartner(date) }
    val profitRows = remember(dataVersion, date) { db.getProfitDistribution(date) }
    val bundle = remember(dataVersion, date) { db.getCashSettlement(date) }

    // V1.2.0: 使用默认利润规则自动生成当天分配，不再要求每天进入“利润分配”手动保存。
    LaunchedEffect(dataVersion, date, summary.profit, profitRows.size) {
        if (profitRows.isEmpty() && summary.profit > 0) {
            val rules = db.getProfitRules()
            if (rules.isNotEmpty()) {
                val partners = db.getPartners()
                val allocations = rules.mapNotNull { rule ->
                    partners.firstOrNull { it.id == rule.partnerId }?.let { p ->
                        p to rule.percent
                    }
                }
                if (allocations.isNotEmpty() && kotlin.math.abs(allocations.sumOf { it.second } - 100.0) < 0.01) {
                    if (db.saveProfitDistribution(date, allocations)) {
                        onChanged()
                    }
                }
            }
        }
    }
    val history = remember(dataVersion) { db.getRecentCashSettlements(20) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PageHeader(
                "当日资金结算",
                "进货垫付 + 费用垫付 + 应得利润 − 实际收款，最后一次性轧差"
            )
        }
        item { CompactDateSelector("结算日期", date, Modifier.fillMaxWidth()) { date = it } }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniSummaryCard("营业额", money(summary.revenue), Modifier.weight(1f), SoftGreen)
                MiniSummaryCard("进货", money(summary.purchaseCost), Modifier.weight(1f), SoftPurple)
                MiniSummaryCard("利润", money(summary.profit), Modifier.weight(1f), SoftOrange)
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
                Column(Modifier.padding(12.dp)) {
                    Text("结算前核对", fontWeight = FontWeight.Bold)
                    SummaryRow("进货垫付合计", money(purchases.sumOf { it.amount }))
                    SummaryRow("实际收款合计", money(receipts.sumOf { it.amount }))
                    SummaryRow("费用垫付合计", money(expenses.sumOf { it.amount }))
                    SummaryRow("已保存利润分配", money(profitRows.sumOf { it.allocatedProfit }))
                    if (profitRows.isEmpty()) {
                        Text(
                            "尚未保存当天利润分配，请先到：更多 → 利润分配。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    val result = db.generateCashSettlement(date)
                    message = result.message
                    if (result.success) onChanged()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (bundle == null) "生成今日结算方案" else "重新生成今日结算方案")
            }
            if (message.isNotBlank()) {
                Text(
                    message,
                    color = if (message.contains("已生成") || message.contains("已结清")) BrandGreen else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        bundle?.let { b ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("每人最终余额", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    AssistChip(
                        onClick = {},
                        label = { Text(if (b.settlement.status == 1) "已结清" else "待结清") }
                    )
                }
            }

            items(b.partners, key = { "cp${it.id}" }) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(11.dp)) {
                        Row {
                            Text(p.partnerName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(
                                when {
                                    p.balance > 0.005 -> "应收 ${money(p.balance)}"
                                    p.balance < -0.005 -> "应转出 ${money(-p.balance)}"
                                    else -> "已平"
                                },
                                fontWeight = FontWeight.Bold,
                                color = if (p.balance >= -0.005) BrandGreen else MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            "进货 ${money(p.purchasePaid)} + 费用 ${money(p.expensePaid)} + 利润 ${money(p.profitShare)} − 已收 ${money(p.revenueReceived)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Text("最终应留 ${money(p.shouldKeep)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Text("最少转账方案", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (b.transfers.isEmpty()) {
                    Text("无需转账，已经平账。", color = BrandGreen)
                }
            }

            items(b.transfers, key = { "ct${it.id}" }) { t ->
                Card(colors = CardDefaults.cardColors(containerColor = SoftGreen), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${t.fromPartnerName}  →  ${t.toPartnerName}", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text(money(t.amount), fontWeight = FontWeight.Bold, color = BrandGreen)
                    }
                }
            }

            item {
                if (b.settlement.status == 0) {
                    Button(
                        onClick = {
                            if (db.confirmCashSettlement(b.settlement.id)) {
                                message = "今日账目已确认结清"
                                onChanged()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("确认今日已结清") }
                }
                TextButton(
                    onClick = { deleteId = b.settlement.id },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("删除本次结算记录") }
            }
        }

        if (history.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text("最近结算历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(history, key = { "cash${it.id}" }) { h ->
                RecordCard {
                    Column(Modifier.weight(1f)) {
                        Text(h.date, fontWeight = FontWeight.Bold)
                        Text(
                            "营业 ${money(h.revenue)} · 进货 ${money(h.purchaseCost)} · 利润 ${money(h.profit)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(if (h.status == 1) "已结清" else "待结清", color = if (h.status == 1) BrandGreen else MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    deleteId?.let { id ->
        ConfirmDelete("删除这张当日资金结算？不会删除进货、营业或利润分配。", { deleteId = null }) {
            db.deleteCashSettlement(id)
            deleteId = null
            message = "结算记录已删除"
            onChanged()
        }
    }
}

@Composable
private fun MoreScreen(db: AppDatabase, dataVersion: Int, initialSub: MorePage = MorePage.MENU, onChanged: () -> Unit) {
    var sub by remember(initialSub) { mutableStateOf(initialSub) }
    BackHandler(enabled = sub != MorePage.MENU) { sub = MorePage.MENU }
    when (sub) {
        MorePage.MENU -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { PageHeader("更多", "历史、统计、合伙人、位置和独立利润分配") }
            item { MenuCard("🕘 历史记录", "查看并删除历史进货、历史营业") { sub = MorePage.HISTORY } }
            item { MenuCard("📊 经营统计", "营业额 / 利润 / 客户排行榜，水果历史进价") { sub = MorePage.STATS } }
            item { MenuCard("👥 合伙人", "人数不设上限，可新增、改名或删除") { sub = MorePage.PARTNERS } }
            item { MenuCard("📍 摆摊位置", "新增或删除位置；删除不影响历史记录") { sub = MorePage.STORES } }
            item { MenuCard("🍎 水果商品管理", "新增、修改、停用或恢复商品；历史进货不受影响") { sub = MorePage.FRUITS } }
            item { MenuCard("💰 利润分配", "独立利润分配表；每位合伙人的百分比可自定义") { sub = MorePage.PROFIT } }
        }
        MorePage.HISTORY -> SubPage("历史记录", { sub = MorePage.MENU }) { HistoryContent(db, dataVersion, onChanged) }
        MorePage.STATS -> SubPage("经营统计", { sub = MorePage.MENU }) { StatsContent(db, dataVersion) }
        MorePage.PARTNERS -> SubPage("合伙人管理", { sub = MorePage.MENU }) { PartnerContent(db, dataVersion, onChanged) }
        MorePage.STORES -> SubPage("摆摊位置管理", { sub = MorePage.MENU }) { StoreContent(db, dataVersion, onChanged) }
        MorePage.FRUITS -> SubPage("水果商品管理", { sub = MorePage.MENU }) { FruitManagementContent(db, dataVersion, onChanged) }
        MorePage.PROFIT -> SubPage("利润分配", { sub = MorePage.MENU }) { ProfitContent(db, dataVersion, onChanged) }
    }
}

@Composable
private fun MenuCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(subtitle, color = Color.Gray) }
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
private fun HistoryContent(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    val purchases = remember(dataVersion) { db.getPurchaseOrders(100) }
    val sessions = remember(dataVersion) { db.getRecentDailyRecords(150) }
    var deleteOrder by remember { mutableStateOf<PurchaseOrderDetail?>(null) }
    var deleteSession by remember { mutableStateOf<StoreDailyRecord?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("营业历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (sessions.isEmpty()) item { Text("暂无", color = Color.Gray) }
        items(sessions, key = { "s${it.id}" }) { s ->
            RecordCard {
                Column(Modifier.weight(1f)) { Text("${s.date} · ${s.storeName}", fontWeight = FontWeight.Bold); Text("营业 ${money(s.revenue)} · 利润 ${money(s.profit)} · 客户 ${s.customerTotal}", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { deleteSession = s }) { Text("删除") }
            }
        }
        item { HorizontalDivider(); Text("进货历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text("修改进货记录请到“进货”页点击对应记录的“编辑”。", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
        if (purchases.isEmpty()) item { Text("暂无", color = Color.Gray) }
        items(purchases, key = { "p${it.order.id}" }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("${p.order.date} · ${p.order.buyerName}", fontWeight = FontWeight.Bold); Text("共用进货 · ${money(p.order.totalCost)}") }
                        TextButton(onClick = { deleteOrder = p }) { Text("删除") }
                    }
                    p.items.forEach { i -> Text("• ${i.fruitName} ${fmt(i.quantity)}${i.unit} ${money(i.totalCost)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    deleteOrder?.let { p -> ConfirmDelete("删除这张进货单？", { deleteOrder = null }) { db.deletePurchaseOrder(p.order.id); deleteOrder = null; onChanged() } }
    deleteSession?.let { s -> ConfirmDelete("删除 ${s.date} ${s.storeName} 营业记录？", { deleteSession = null }) { db.deleteStoreDailyRecord(s.id); deleteSession = null; onChanged() } }
}

@Composable
private fun StatsContent(db: AppDatabase, dataVersion: Int) {
    var thisMonth by remember { mutableStateOf(true) }
    val month = currentMonthRange()
    val rankings = remember(dataVersion, thisMonth) { if (thisMonth) db.getRankings(month.first, month.second) else db.getRankings() }
    val records = remember(dataVersion, thisMonth) { if (thisMonth) db.getDailyRecordsBetween(month.first, month.second) else db.getDailyRecordsBetween(null, null) }
    val fruits = remember(dataVersion) { db.getFruits() }
    var fruitId by remember { mutableStateOf<Long?>(null) }
    val fruit = fruits.firstOrNull { it.id == fruitId } ?: fruits.firstOrNull()
    var fruitMenu by remember { mutableStateOf(false) }
    val prices = remember(dataVersion, fruit?.id) { fruit?.let { db.getFruitPriceHistory(it.id, 30) } ?: emptyList() }
    val context = androidx.compose.ui.platform.LocalContext.current
    var exportMessage by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(db.exportJson()) } }
            .onSuccess { exportMessage = "备份已导出" }.onFailure { exportMessage = "导出失败：${it.message}" }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (thisMonth) Button(onClick = {}) { Text("本月") } else OutlinedButton(onClick = { thisMonth = true }) { Text("本月") }
                if (!thisMonth) Button(onClick = {}) { Text("全部") } else OutlinedButton(onClick = { thisMonth = false }) { Text("全部") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("营业额", money(records.sumOf { it.revenue }), Modifier.weight(1f), SoftGreen)
                MetricCard("利润", money(records.sumOf { it.profit }), Modifier.weight(1f), SoftOrange)
            }
        }
        item { RankingSection("营业额排行榜", rankings.sortedByDescending { it.revenue }) { money(it.revenue) } }
        item {
            Text("利润排行中的共用进货按各摊位营业额比例分摊。", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            RankingSection("利润排行榜", rankings.sortedByDescending { it.profit }) { money(it.profit) }
        }
        item { RankingSection("客户数排行榜", rankings.sortedByDescending { it.customers }) { "${it.customers} 人" } }
        item {
            Text("水果历史进价", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Box {
                OutlinedButton(onClick = { fruitMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(fruit?.name ?: "请选择水果", Modifier.weight(1f)); Text("▼") }
                DropdownMenu(expanded = fruitMenu, onDismissRequest = { fruitMenu = false }) {
                    fruits.forEach { f -> DropdownMenuItem(text = { Text(f.name) }, onClick = { fruitId = f.id; fruitMenu = false }) }
                }
            }
            prices.forEach { p ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("${p.date} · ${money(p.unitPrice)}/${p.unit}", fontWeight = FontWeight.SemiBold)
                    Text("${p.buyerName} · ${fmt(p.quantity)}${p.unit}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
        item {
            Button(onClick = { exportLauncher.launch("天鲜果业备份_${LocalDate.now()}.json") }, modifier = Modifier.fillMaxWidth()) { Text("导出 JSON 备份") }
            if (exportMessage.isNotBlank()) Text(exportMessage, color = BrandGreen)
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
            ) { Text("＋ 新增水果商品") }

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
                message = if (id > 0) "水果商品已保存" else "保存失败，商品名称可能重复"
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
        onDismissRequest = onDismiss, title = { Text("新增水果") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("水果名称") }, singleLine = true)
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
        title = { Text("编辑水果商品") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("水果名称") },
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
