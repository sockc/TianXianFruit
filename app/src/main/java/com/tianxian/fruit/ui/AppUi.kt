package com.tianxian.fruit.ui

import android.app.DatePickerDialog
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tianxian.fruit.data.*
import java.time.LocalDate
import java.util.Locale

private val BrandGreen = Color(0xFF13A868)
private val SoftGreen = Color(0xFFE9F8F0)
private val SoftOrange = Color(0xFFFFF3E3)
private val SoftBlue = Color(0xFFEAF3FF)
private val SoftPurple = Color(0xFFF3ECFF)

enum class AppPage(val title: String, val emoji: String) {
    HOME("首页", "🏠"), PURCHASE("进货", "📦"), SESSION("营业", "📝"), HISTORY("历史", "🕘"), STATS("统计", "📊")
}

@Composable
fun TianXianApp(db: AppDatabase) {
    var page by remember { mutableStateOf(AppPage.HOME) }
    var dataVersion by remember { mutableIntStateOf(0) }
    MaterialTheme(
        colorScheme = lightColorScheme(primary = BrandGreen, secondary = BrandGreen)
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    AppPage.entries.forEach { item ->
                        NavigationBarItem(
                            selected = page == item,
                            onClick = { page = item },
                            icon = { Text(item.emoji) },
                            label = { Text(item.title) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (page) {
                    AppPage.HOME -> HomeScreen(db, dataVersion, onGoSession = { page = AppPage.SESSION }, onGoPurchase = { page = AppPage.PURCHASE })
                    AppPage.PURCHASE -> PurchaseScreen(db, dataVersion) { dataVersion++ }
                    AppPage.SESSION -> SessionScreen(db, dataVersion) { dataVersion++ }
                    AppPage.HISTORY -> HistoryScreen(db, dataVersion) { dataVersion++ }
                    AppPage.STATS -> StatsScreen(db, dataVersion)
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
fun HomeScreen(db: AppDatabase, dataVersion: Int, onGoSession: () -> Unit, onGoPurchase: () -> Unit) {
    val today = LocalDate.now().toString()
    val session = remember(dataVersion, today) { db.getSession(today) }
    val purchaseTotal = remember(dataVersion, today) { db.getPurchaseTotal(today) }
    val monthRange = currentMonthRange()
    val rankings = remember(dataVersion, monthRange) { db.getRankings(monthRange.first, monthRange.second).sortedByDescending { it.revenue } }
    val recent = remember(dataVersion) { db.getRecentSessions(7).reversed() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PageHeader("天鲜果业经营助手", "今天 $today")
            Card(colors = CardDefaults.cardColors(containerColor = SoftGreen)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("📍 今日摊位", fontWeight = FontWeight.SemiBold)
                    Text(session?.storeName ?: "尚未记录营业地点", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("今日营业额", money(session?.revenue ?: 0.0), Modifier.weight(1f), SoftGreen)
                MetricCard("今日利润", money(session?.profit ?: 0.0), Modifier.weight(1f), SoftOrange)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("今日客户", "${session?.customerTotal ?: 0} 人", Modifier.weight(1f), SoftBlue,
                    "新客 ${session?.newCustomer ?: 0} / 老客 ${session?.oldCustomer ?: 0}")
                MetricCard("今日进货", money(purchaseTotal), Modifier.weight(1f), SoftPurple,
                    "收摊库存 ${money(session?.stockLeftValue ?: 0.0)}")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onGoPurchase, modifier = Modifier.weight(1f)) { Text("＋ 记录进货") }
                Button(onClick = onGoSession, modifier = Modifier.weight(1f)) { Text("✎ 记录营业") }
            }
        }
        item {
            Text("🏆 本月营业额排行", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (rankings.isEmpty()) Text("还没有排行榜数据", color = Color.Gray)
            rankings.take(3).forEachIndexed { index, r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}.", modifier = Modifier.width(28.dp), fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f)) {
                        Text(r.storeName, fontWeight = FontWeight.SemiBold)
                        Text("${r.days} 天 · 客户 ${r.customers}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Text(money(r.revenue), fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Text("最近 7 次营业", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (recent.isEmpty()) Text("保存营业记录后会显示在这里", color = Color.Gray)
            recent.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(s.date, Modifier.width(96.dp))
                    Text(s.storeName, Modifier.weight(1f))
                    Text(money(s.revenue))
                }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
fun PurchaseScreen(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val fruits = remember(dataVersion) { db.getFruits() }
    var selectedFruitId by remember { mutableStateOf<Long?>(null) }
    val selectedFruit = fruits.firstOrNull { it.id == selectedFruitId } ?: fruits.firstOrNull()
    var unit by remember(selectedFruit?.id) { mutableStateOf(selectedFruit?.defaultUnit ?: "斤") }
    var quantity by remember { mutableStateOf("") }
    var totalCost by remember { mutableStateOf("") }
    var supplier by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var fruitMenu by remember { mutableStateOf(false) }
    var unitMenu by remember { mutableStateOf(false) }
    var addFruitDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val recent = remember(dataVersion) { db.getRecentPurchases(30) }
    val priceHistory = remember(dataVersion, selectedFruit?.id) {
        selectedFruit?.let { db.getFruitPriceHistory(it.id, 6) } ?: emptyList()
    }
    val q = quantity.toDoubleOrNull() ?: 0.0
    val cost = totalCost.toDoubleOrNull() ?: 0.0
    val price = if (q > 0) cost / q else 0.0

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PageHeader("进货登记", "水果名称可固定保存，下次直接选择") }
        item { DateField("进货日期", date) { date = it } }
        item {
            Text("水果品类", style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { fruitMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedFruit?.name ?: "请先添加水果", Modifier.weight(1f))
                    Text("▼")
                }
                DropdownMenu(expanded = fruitMenu, onDismissRequest = { fruitMenu = false }) {
                    fruits.forEach { fruit ->
                        DropdownMenuItem(text = { Text(fruit.name) }, onClick = {
                            selectedFruitId = fruit.id
                            unit = fruit.defaultUnit
                            fruitMenu = false
                        })
                    }
                    DropdownMenuItem(text = { Text("＋ 新增水果") }, onClick = { fruitMenu = false; addFruitDialog = true })
                }
            }
            TextButton(onClick = { addFruitDialog = true }) { Text("＋ 自定义水果") }
        }
        item {
            Text("采购单位", style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { unitMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(unit, Modifier.weight(1f)); Text("▼")
                }
                DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                    listOf("斤", "筐", "箱", "件").forEach { u ->
                        DropdownMenuItem(text = { Text(u) }, onClick = { unit = u; unitMenu = false })
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("数量", quantity, { quantity = it }, Modifier.weight(1f))
                NumberField("总进价(元)", totalCost, { totalCost = it }, Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SoftGreen)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("自动单价")
                    Text("${money(price)}/$unit", fontWeight = FontWeight.Bold)
                }
            }
        }
        item { OutlinedTextField(supplier, { supplier = it }, label = { Text("供应商（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(remark, { remark = it }, label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(
                onClick = {
                    if (selectedFruit == null || q <= 0 || cost < 0) {
                        message = "请正确填写水果、数量和金额"
                    } else {
                        db.addPurchase(date, selectedFruit, unit, q, cost, supplier, remark)
                        quantity = ""; totalCost = ""; supplier = ""; remark = ""
                        message = "进货记录已保存"
                        onChanged()
                    }
                }, modifier = Modifier.fillMaxWidth()
            ) { Text("保存进货") }
            if (message.isNotBlank()) Text(message, color = BrandGreen, modifier = Modifier.padding(top = 6.dp))
        }
        item {
            Text("${selectedFruit?.name ?: "水果"} 最近进价", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (priceHistory.isEmpty()) Text("暂无历史价格", color = Color.Gray)
            priceHistory.forEach { p -> Text("${p.date}　${fmt(p.quantity)}${p.unit}　${money(p.unitPrice)}/${p.unit}") }
        }
        item { Text("最近进货记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(recent, key = { it.id }) { p ->
            RecordCard {
                Column(Modifier.weight(1f)) {
                    Text("${p.date} · ${p.fruitName}", fontWeight = FontWeight.SemiBold)
                    Text("${fmt(p.quantity)}${p.unit} · ${money(p.totalCost)} · ${money(p.unitPrice)}/${p.unit}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (addFruitDialog) AddFruitDialog(
        onDismiss = { addFruitDialog = false },
        onSave = { name, defaultUnit ->
            db.addFruit(name, defaultUnit)
            addFruitDialog = false
            onChanged()
        }
    )
}

@Composable
fun SessionScreen(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val stores = remember(dataVersion) { db.getStores() }
    var storeId by remember { mutableStateOf<Long?>(null) }
    var storeMenu by remember { mutableStateOf(false) }
    var addStoreDialog by remember { mutableStateOf(false) }
    var wechat by remember { mutableStateOf("") }
    var alipay by remember { mutableStateOf("") }
    var cash by remember { mutableStateOf("") }
    var expense by remember { mutableStateOf("") }
    var openingStock by remember { mutableStateOf("") }
    var closingStock by remember { mutableStateOf("") }
    var newCustomer by remember { mutableStateOf("") }
    var oldCustomer by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    val purchaseCost = remember(dataVersion, date) { db.getPurchaseTotal(date) }
    val selectedStore = stores.firstOrNull { it.id == storeId } ?: stores.firstOrNull()

    LaunchedEffect(date, dataVersion) {
        val saved = db.getSession(date)
        if (saved != null) {
            storeId = saved.storeId
            wechat = cleanNumber(saved.wechatIncome)
            alipay = cleanNumber(saved.alipayIncome)
            cash = cleanNumber(saved.cashIncome)
            expense = cleanNumber(saved.expense)
            openingStock = cleanNumber(saved.openingStockValue)
            closingStock = cleanNumber(saved.stockLeftValue)
            newCustomer = saved.newCustomer.toString()
            oldCustomer = saved.oldCustomer.toString()
        } else {
            storeId = stores.firstOrNull()?.id
            wechat = ""; alipay = ""; cash = ""; expense = ""
            openingStock = cleanNumber(db.getPreviousClosingStock(date))
            closingStock = ""; newCustomer = ""; oldCustomer = ""
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
    val profit = revenue + close - open - purchaseCost - e

    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PageHeader("每日营业", "同一天再次保存会更新当天记录，不会重复新增") }
        item { DateField("营业日期", date) { date = it } }
        item {
            Text("摆摊位置", style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { storeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedStore?.let { if (it.address.isBlank()) it.name else "${it.name} · ${it.address}" } ?: "请添加摆摊位置", Modifier.weight(1f))
                    Text("▼")
                }
                DropdownMenu(expanded = storeMenu, onDismissRequest = { storeMenu = false }) {
                    stores.forEach { s -> DropdownMenuItem(text = { Text(if (s.address.isBlank()) s.name else "${s.name} · ${s.address}") }, onClick = { storeId = s.id; storeMenu = false }) }
                    DropdownMenuItem(text = { Text("＋ 新增位置") }, onClick = { storeMenu = false; addStoreDialog = true })
                }
            }
            TextButton(onClick = { addStoreDialog = true }) { Text("＋ 保存新位置") }
        }
        item { Text("收款", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("微信", wechat, { wechat = it }, Modifier.weight(1f))
                NumberField("支付宝", alipay, { alipay = it }, Modifier.weight(1f))
                NumberField("现金", cash, { cash = it }, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("日常开销", expense, { expense = it }, Modifier.weight(1f))
                OutlinedTextField(money(purchaseCost), {}, readOnly = true, label = { Text("当日进货") }, modifier = Modifier.weight(1f), singleLine = true)
            }
        }
        item { Text("库存估值", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("开摊库存", openingStock, { openingStock = it }, Modifier.weight(1f))
                NumberField("收摊库存", closingStock, { closingStock = it }, Modifier.weight(1f))
            }
            Text("开摊库存默认取上一营业日的收摊库存，可手动修改。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        item { Text("客户", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IntegerField("新客", newCustomer, { newCustomer = it }, Modifier.weight(1f))
                IntegerField("老客", oldCustomer, { oldCustomer = it }, Modifier.weight(1f))
            }
            Text("客户总数：${n + o} 人　老客占比：${if (n + o > 0) "%.1f%%".format(Locale.CHINA, o * 100.0 / (n + o)) else "0%"}")
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SoftGreen)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("营业额"); Text(money(revenue), fontWeight = FontWeight.Bold) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("预计净利润"); Text(money(profit), fontWeight = FontWeight.Bold) }
                    Text("利润 = 营业额 + 收摊库存 - 开摊库存 - 进货 - 开销", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                }
            }
        }
        item {
            Button(onClick = {
                if (selectedStore == null) {
                    message = "请先选择或新增摆摊位置"
                } else {
                    db.saveSession(date, selectedStore, w, a, c, e, open, close, n, o)
                    message = "营业记录已保存"
                    onChanged()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("保存当天营业") }
            if (message.isNotBlank()) Text(message, color = BrandGreen, modifier = Modifier.padding(top = 6.dp))
        }
    }

    if (addStoreDialog) AddStoreDialog(
        onDismiss = { addStoreDialog = false },
        onSave = { name, address -> db.addStore(name, address); addStoreDialog = false; onChanged() }
    )
}

@Composable
fun HistoryScreen(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    var showSessions by remember { mutableStateOf(true) }
    val sessions = remember(dataVersion) { db.getRecentSessions(120) }
    val purchases = remember(dataVersion) { db.getRecentPurchases(200) }
    var sessionToDelete by remember { mutableStateOf<SessionRecord?>(null) }
    var purchaseToDelete by remember { mutableStateOf<PurchaseRecord?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { PageHeader("历史记录", "以前的位置、营业额、利润、客户和进价都保留") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showSessions) Button(onClick = {}) { Text("营业记录") } else OutlinedButton(onClick = { showSessions = true }) { Text("营业记录") }
                if (!showSessions) Button(onClick = {}) { Text("进货记录") } else OutlinedButton(onClick = { showSessions = false }) { Text("进货记录") }
            }
        }
        if (showSessions) {
            if (sessions.isEmpty()) item { Text("暂无营业历史", color = Color.Gray) }
            items(sessions, key = { "s${it.id}" }) { s ->
                RecordCard {
                    Column(Modifier.weight(1f)) {
                        Text("${s.date} · ${s.storeName}", fontWeight = FontWeight.Bold)
                        Text("营业额 ${money(s.revenue)}　利润 ${money(s.profit)}", style = MaterialTheme.typography.bodyMedium)
                        Text("客户 ${s.customerTotal}（新 ${s.newCustomer} / 老 ${s.oldCustomer}） · 进货 ${money(s.purchaseCost)}", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                        Text("库存 ${money(s.openingStockValue)} → ${money(s.stockLeftValue)} · 开销 ${money(s.expense)}", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                    }
                    TextButton(onClick = { sessionToDelete = s }) { Text("删除") }
                }
            }
        } else {
            if (purchases.isEmpty()) item { Text("暂无进货历史", color = Color.Gray) }
            items(purchases, key = { "p${it.id}" }) { p ->
                RecordCard {
                    Column(Modifier.weight(1f)) {
                        Text("${p.date} · ${p.fruitName}", fontWeight = FontWeight.Bold)
                        Text("${fmt(p.quantity)}${p.unit}　总价 ${money(p.totalCost)}　单价 ${money(p.unitPrice)}/${p.unit}")
                        if (p.supplier.isNotBlank()) Text("供应商：${p.supplier}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { purchaseToDelete = p }) { Text("删除") }
                }
            }
        }
    }

    sessionToDelete?.let { s -> ConfirmDelete("删除 ${s.date} 的营业记录？", onDismiss = { sessionToDelete = null }) {
        db.deleteSession(s.id); sessionToDelete = null; onChanged()
    } }
    purchaseToDelete?.let { p -> ConfirmDelete("删除 ${p.date} ${p.fruitName} 的进货记录？", onDismiss = { purchaseToDelete = null }) {
        db.deletePurchase(p.id); purchaseToDelete = null; onChanged()
    } }
}

@Composable
fun StatsScreen(db: AppDatabase, dataVersion: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var thisMonth by remember { mutableStateOf(true) }
    val month = currentMonthRange()
    val rankings = remember(dataVersion, thisMonth) {
        if (thisMonth) db.getRankings(month.first, month.second) else db.getRankings()
    }
    val fruits = remember(dataVersion) { db.getFruits() }
    var fruitId by remember { mutableStateOf<Long?>(null) }
    val fruit = fruits.firstOrNull { it.id == fruitId } ?: fruits.firstOrNull()
    var fruitMenu by remember { mutableStateOf(false) }
    val prices = remember(dataVersion, fruit?.id) { fruit?.let { db.getFruitPriceHistory(it.id, 20) } ?: emptyList() }
    val sessions = remember(dataVersion, thisMonth) {
        if (thisMonth) db.getSessionsBetween(month.first, month.second) else db.getSessionsBetween(null, null)
    }
    val totalRevenue = sessions.sumOf { it.revenue }
    val totalProfit = sessions.sumOf { it.profit }
    val totalCustomers = sessions.sumOf { it.customerTotal }
    var exportMessage by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(db.exportJson()) }
            }.onSuccess { exportMessage = "备份已导出" }.onFailure { exportMessage = "导出失败：${it.message}" }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PageHeader("经营统计", "排行榜都会显示对应摆摊位置") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (thisMonth) Button(onClick = {}) { Text("本月") } else OutlinedButton(onClick = { thisMonth = true }) { Text("本月") }
                if (!thisMonth) Button(onClick = {}) { Text("全部") } else OutlinedButton(onClick = { thisMonth = false }) { Text("全部") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("营业额", money(totalRevenue), Modifier.weight(1f), SoftGreen)
                MetricCard("利润", money(totalProfit), Modifier.weight(1f), SoftOrange)
            }
            Spacer(Modifier.height(8.dp))
            MetricCard("客户数", "$totalCustomers 人", Modifier.fillMaxWidth(), SoftBlue)
        }
        item { RankingSection("营业额排行榜", rankings.sortedByDescending { it.revenue }) { money(it.revenue) } }
        item { RankingSection("利润排行榜", rankings.sortedByDescending { it.profit }) { money(it.profit) } }
        item { RankingSection("客户数排行榜", rankings.sortedByDescending { it.customers }) { "${it.customers} 人" } }
        item {
            Text("水果进价历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Box {
                OutlinedButton(onClick = { fruitMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(fruit?.name ?: "请选择水果", Modifier.weight(1f)); Text("▼")
                }
                DropdownMenu(expanded = fruitMenu, onDismissRequest = { fruitMenu = false }) {
                    fruits.forEach { f -> DropdownMenuItem(text = { Text(f.name) }, onClick = { fruitId = f.id; fruitMenu = false }) }
                }
            }
            if (prices.isEmpty()) Text("暂无历史进价", color = Color.Gray)
            prices.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(p.date, Modifier.width(100.dp)); Text("${money(p.unitPrice)}/${p.unit}", Modifier.weight(1f)); Text("${fmt(p.quantity)}${p.unit}")
                }
            }
        }
        item {
            HorizontalDivider()
            Text("数据安全", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Text("数据保存在手机本地 SQLite。升级时只要包名和签名不变，数据会继续保留。建议定期导出 JSON 备份。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { exportLauncher.launch("天鲜果业备份_${LocalDate.now()}.json") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("导出 JSON 备份") }
            if (exportMessage.isNotBlank()) Text(exportMessage, color = BrandGreen)
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun RankingSection(title: String, rankings: List<RankingRecord>, value: (RankingRecord) -> String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (rankings.isEmpty()) Text("暂无数据", color = Color.Gray)
        rankings.take(10).forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.", Modifier.width(28.dp), fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f)) {
                    Text(r.storeName)
                    Text("${r.days} 个营业日", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Text(value(r), fontWeight = FontWeight.SemiBold)
            }
        }
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
    DatePickerDialog(context, { _, y, m, day ->
        onDate(LocalDate.of(y, m + 1, day).toString())
    }, d.year, d.monthValue - 1, d.dayOfMonth).show()
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
private fun RecordCard(content: @Composable RowScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, content = content) }
}

@Composable
private fun AddFruitDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("斤") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增水果") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("水果名称") }, singleLine = true)
                Text("默认单位")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("斤", "筐", "箱").forEach { u ->
                        FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) })
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(name, unit) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AddStoreDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增摆摊位置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("位置简称，例如：龙归A摊") }, singleLine = true)
                OutlinedTextField(address, { address = it }, label = { Text("详细位置（可选）") })
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(name, address) }) { Text("保存") } },
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
