package com.tianxian.fruit.ui

import android.app.DatePickerDialog
import android.content.Context
import androidx.activity.compose.BackHandler
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
    HOME("首页", "🏠"), PURCHASE("进货", "📦"), SESSION("营业", "📝"), SETTLEMENT("结算", "🧾"), MORE("更多", "☰")
}

private enum class MorePage { MENU, HISTORY, STATS, PARTNERS, STORES, PROFIT }

@Composable
fun TianXianApp(db: AppDatabase) {
    var page by remember { mutableStateOf(AppPage.HOME) }
    var dataVersion by remember { mutableIntStateOf(0) }
    BackHandler(enabled = page != AppPage.HOME) { page = AppPage.HOME }
    MaterialTheme(colorScheme = lightColorScheme(primary = BrandGreen, secondary = BrandGreen)) {
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
                    AppPage.HOME -> HomeScreen(db, dataVersion, { page = AppPage.PURCHASE }, { page = AppPage.SESSION }, { page = AppPage.SETTLEMENT })
                    AppPage.PURCHASE -> PurchaseScreen(db, dataVersion) { dataVersion++ }
                    AppPage.SESSION -> SessionScreen(db, dataVersion) { dataVersion++ }
                    AppPage.SETTLEMENT -> SettlementScreen(db, dataVersion)
                    AppPage.MORE -> MoreScreen(db, dataVersion) { dataVersion++ }
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
    onSession: () -> Unit,
    onSettlement: () -> Unit
) {
    val today = LocalDate.now().toString()
    val summary = remember(dataVersion, today) { db.getDailySummary(today) }
    val records = remember(dataVersion, today) { db.getDailyRecords(today) }
    val month = currentMonthRange()
    val rankings = remember(dataVersion, month) { db.getRankings(month.first, month.second).sortedByDescending { it.revenue } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PageHeader("天鲜果业经营助手", "今天 $today · ${summary.storeCount} 个摊位已结账")
            Card(colors = CardDefaults.cardColors(containerColor = SoftGreen)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("📍 今日摊位", fontWeight = FontWeight.SemiBold)
                    Text(if (records.isEmpty()) "尚未记录" else records.joinToString("、") { it.storeName }, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("今日营业额", money(summary.revenue), Modifier.weight(1f), SoftGreen)
                MetricCard("今日利润", money(summary.profit), Modifier.weight(1f), SoftOrange)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("今日客户", "${summary.customers} 人", Modifier.weight(1f), SoftBlue)
                MetricCard("今日进货", money(summary.purchaseCost), Modifier.weight(1f), SoftPurple)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPurchase, modifier = Modifier.weight(1f)) { Text("＋进货") }
                Button(onClick = onSession, modifier = Modifier.weight(1f)) { Text("✎营业") }
                OutlinedButton(onClick = onSettlement, modifier = Modifier.weight(1f)) { Text("结算") }
            }
        }
        item {
            Text("🏆 本月营业额排行", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (rankings.isEmpty()) Text("还没有排行榜数据", color = Color.Gray)
            rankings.take(3).forEachIndexed { i, r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", Modifier.width(28.dp), fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f)) {
                        Text(r.storeName, fontWeight = FontWeight.SemiBold)
                        Text("${r.days} 天 · 客户 ${r.customers}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Text(money(r.revenue), fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun PurchaseScreen(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val fruits = remember(dataVersion) { db.getFruits() }
    val partners = remember(dataVersion) { db.getPartners() }
    val stores = remember(dataVersion) { db.getStores() }
    var buyerId by remember { mutableStateOf<Long?>(null) }
    var storeId by remember { mutableStateOf<Long?>(null) }
    val buyer = partners.firstOrNull { it.id == buyerId } ?: partners.firstOrNull()
    val store = stores.firstOrNull { it.id == storeId } ?: stores.firstOrNull()

    var fruitId by remember { mutableStateOf<Long?>(null) }
    val fruit = fruits.firstOrNull { it.id == fruitId } ?: fruits.firstOrNull()
    var unit by remember(fruit?.id) { mutableStateOf(fruit?.defaultUnit ?: "斤") }
    var quantity by remember { mutableStateOf("") }
    var totalCost by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var buyerMenu by remember { mutableStateOf(false) }
    var storeMenu by remember { mutableStateOf(false) }
    var fruitMenu by remember { mutableStateOf(false) }
    var unitMenu by remember { mutableStateOf(false) }
    var addFruitDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val draft = remember { mutableStateListOf<PurchaseLineInput>() }
    var deleteOrder by remember { mutableStateOf<PurchaseOrderDetail?>(null) }
    val history = remember(dataVersion) { db.getPurchaseOrders(50) }

    val q = quantity.toDoubleOrNull() ?: 0.0
    val cost = totalCost.toDoubleOrNull() ?: 0.0
    val price = if (q > 0) cost / q else 0.0

    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PageHeader("批量进货", "一次进货可连续加入多个水果，并记录谁进货、给哪个摊位") }
        item { DateField("进货日期", date) { date = it } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectButton("进货人", buyer?.name ?: "请先添加合伙人", Modifier.weight(1f), buyerMenu) { buyerMenu = it }
                DropdownMenu(expanded = buyerMenu, onDismissRequest = { buyerMenu = false }) {
                    partners.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { buyerId = p.id; buyerMenu = false }) }
                }
                SelectButton("供货摊位", store?.name ?: "请先添加位置", Modifier.weight(1f), storeMenu) { storeMenu = it }
                DropdownMenu(expanded = storeMenu, onDismissRequest = { storeMenu = false }) {
                    stores.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { storeId = s.id; storeMenu = false }) }
                }
            }
        }
        item {
            Text("添加商品", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Box {
                OutlinedButton(onClick = { fruitMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(fruit?.name ?: "请选择水果", Modifier.weight(1f)); Text("▼")
                }
                DropdownMenu(expanded = fruitMenu, onDismissRequest = { fruitMenu = false }) {
                    fruits.forEach { f -> DropdownMenuItem(text = { Text(f.name) }, onClick = { fruitId = f.id; unit = f.defaultUnit; fruitMenu = false }) }
                    DropdownMenuItem(text = { Text("＋新增水果") }, onClick = { fruitMenu = false; addFruitDialog = true })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(0.8f)) {
                    OutlinedButton(onClick = { unitMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(unit); Spacer(Modifier.weight(1f)); Text("▼") }
                    DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                        listOf("斤", "筐", "箱", "件").forEach { u -> DropdownMenuItem(text = { Text(u) }, onClick = { unit = u; unitMenu = false }) }
                    }
                }
                NumberField("数量", quantity, { quantity = it }, Modifier.weight(1f))
                NumberField("总价", totalCost, { totalCost = it }, Modifier.weight(1f))
            }
            Text("自动单价：${money(price)}/$unit", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            Button(
                onClick = {
                    if (fruit == null || q <= 0 || cost < 0) message = "请正确填写水果、数量和金额"
                    else {
                        draft.add(PurchaseLineInput(fruit, unit, q, cost))
                        quantity = ""; totalCost = ""; message = "已加入本次进货，可继续添加商品"
                    }
                }, modifier = Modifier.fillMaxWidth()
            ) { Text("＋ 加入本次进货") }
        }
        if (draft.isNotEmpty()) {
            item { Text("本次进货明细（${draft.size}项）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(draft.indices.toList()) { index ->
                val d = draft[index]
                RecordCard {
                    Column(Modifier.weight(1f)) {
                        Text(d.fruit.name, fontWeight = FontWeight.SemiBold)
                        Text("${fmt(d.quantity)}${d.unit} · ${money(d.totalCost)} · ${money(d.totalCost / d.quantity)}/${d.unit}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { draft.removeAt(index) }) { Text("移除") }
                }
            }
            item {
                Text("合计：${money(draft.sumOf { it.totalCost })}", fontWeight = FontWeight.Bold)
                OutlinedTextField(remark, { remark = it }, label = { Text("本次进货备注（可选）") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        if (buyer == null || store == null) message = "请先在更多页面设置合伙人和摆摊位置"
                        else if (draft.isEmpty()) message = "请先添加商品"
                        else {
                            db.addPurchaseOrder(date, buyer, store, draft.toList(), remark)
                            draft.clear(); remark = ""; message = "整张进货单已保存"; onChanged()
                        }
                    }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("保存整张进货单") }
            }
        }
        if (message.isNotBlank()) item { Text(message, color = BrandGreen) }
        item { HorizontalDivider(); Text("进货历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
        if (history.isEmpty()) item { Text("暂无进货记录", color = Color.Gray) }
        items(history, key = { it.order.id }) { detail ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${detail.order.date} · ${detail.order.buyerName}", fontWeight = FontWeight.Bold)
                            Text("${detail.order.storeName} · 合计 ${money(detail.order.totalCost)}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { deleteOrder = detail }) { Text("删除") }
                    }
                    detail.items.forEach { item -> Text("• ${item.fruitName} ${fmt(item.quantity)}${item.unit}  ${money(item.totalCost)}  (${money(item.unitPrice)}/${item.unit})", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    if (addFruitDialog) AddFruitDialog(
        onDismiss = { addFruitDialog = false },
        onSave = { name, defaultUnit -> db.addFruit(name, defaultUnit); addFruitDialog = false; onChanged() }
    )
    deleteOrder?.let { detail -> ConfirmDelete("删除 ${detail.order.date} 的整张进货单？历史价格也会同步隐藏。", { deleteOrder = null }) {
        db.deletePurchaseOrder(detail.order.id); deleteOrder = null; onChanged()
    } }
}

@Composable
private fun SessionScreen(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val stores = remember(dataVersion) { db.getStores() }
    val partners = remember(dataVersion) { db.getPartners() }
    var storeId by remember { mutableStateOf<Long?>(null) }
    val store = stores.firstOrNull { it.id == storeId } ?: stores.firstOrNull()
    var storeMenu by remember { mutableStateOf(false) }
    var addStoreDialog by remember { mutableStateOf(false) }

    var wechat by remember { mutableStateOf("") }
    var alipay by remember { mutableStateOf("") }
    var cash by remember { mutableStateOf("") }
    var collectorId by remember { mutableStateOf<Long?>(null) }
    var expense by remember { mutableStateOf("") }
    var openingStock by remember { mutableStateOf("") }
    var closingStock by remember { mutableStateOf("") }
    var newCustomer by remember { mutableStateOf("") }
    var oldCustomer by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var deleteRecord by remember { mutableStateOf<StoreDailyRecord?>(null) }

    LaunchedEffect(dataVersion, stores.size) {
        if (storeId == null && stores.isNotEmpty()) storeId = stores.first().id
    }
    LaunchedEffect(date, storeId, dataVersion) {
        val s = store ?: return@LaunchedEffect
        val saved = db.getStoreDailyRecord(date, s.id)
        if (saved != null) {
            wechat = cleanNumber(saved.wechatIncome)
            alipay = cleanNumber(saved.alipayIncome)
            cash = cleanNumber(saved.cashIncome)
            collectorId = listOf(saved.wechatCollectorId, saved.alipayCollectorId, saved.cashCollectorId).firstOrNull { it > 0 }
            expense = cleanNumber(saved.expense)
            openingStock = cleanNumber(saved.openingStockValue)
            closingStock = cleanNumber(saved.stockLeftValue)
            newCustomer = saved.newCustomer.toString()
            oldCustomer = saved.oldCustomer.toString()
        } else {
            wechat = ""; alipay = ""; cash = ""; expense = ""; closingStock = ""; newCustomer = ""; oldCustomer = ""
            collectorId = partners.firstOrNull()?.id
            openingStock = cleanNumber(db.getPreviousClosingStock(s.id, date))
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
    val purchase = store?.let { db.getPurchaseTotalByStore(date, it.id) } ?: 0.0
    val revenue = w + a + c
    val profit = revenue + close - open - purchase - e
    val todayRecords = remember(dataVersion, date) { db.getDailyRecords(date) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { PageHeader("摊位营业记录", "同一天可保存多个摊位；一个摊位只选择一次收款归属") }
        item { DateField("营业日期", date) { date = it } }
        item {
            Box {
                OutlinedButton(onClick = { storeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(store?.name ?: "请添加摆摊位置", Modifier.weight(1f)); Text("▼")
                }
                DropdownMenu(expanded = storeMenu, onDismissRequest = { storeMenu = false }) {
                    stores.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { storeId = s.id; storeMenu = false }) }
                    DropdownMenuItem(text = { Text("＋新增位置") }, onClick = { storeMenu = false; addStoreDialog = true })
                }
            }
        }
        item {
            CollectorSelector(partners, collectorId) { collectorId = it }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactNumberField("微信", wechat, { wechat = it }, Modifier.weight(1f))
                CompactNumberField("支付宝", alipay, { alipay = it }, Modifier.weight(1f))
                CompactNumberField("现金", cash, { cash = it }, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactNumberField("日常开销", expense, { expense = it }, Modifier.weight(1f))
                CompactReadOnlyField("该摊今日进货", money(purchase), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactNumberField("开摊库存", openingStock, { openingStock = it }, Modifier.weight(1f))
                CompactNumberField("收摊库存", closingStock, { closingStock = it }, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactIntegerField("新客", newCustomer, { newCustomer = it }, Modifier.weight(1f))
                CompactIntegerField("老客", oldCustomer, { oldCustomer = it }, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("该摊营业额", money(revenue), Modifier.weight(1f), SoftGreen)
                MetricCard("该摊利润", money(profit), Modifier.weight(1f), SoftOrange)
            }
        }
        item {
            Button(onClick = {
                val s = store
                if (s == null) message = "请先添加摆摊位置"
                else {
                    val collector = partners.firstOrNull { it.id == collectorId }
                    db.saveStoreDailyRecord(date, s, w, collector, a, collector, c, collector, e, open, close, n, o)
                    message = "${s.name} 营业记录已保存"
                    onChanged()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("保存当前摊位") }
            if (message.isNotBlank()) Text(message, color = BrandGreen, modifier = Modifier.padding(top = 4.dp))
        }
        if (todayRecords.isNotEmpty()) {
            item { Text("当天已记录摊位", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(todayRecords, key = { it.id }) { r ->
                RecordCard {
                    Column(Modifier.weight(1f)) {
                        Text(r.storeName, fontWeight = FontWeight.Bold)
                        Text("营业 ${money(r.revenue)} · 利润 ${money(r.profit)} · 收款 ${r.wechatCollectorName}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { deleteRecord = r }) { Text("删除") }
                }
            }
        }
    }

    if (addStoreDialog) AddStoreDialog({ addStoreDialog = false }) { name, address ->
        db.addStore(name, address); addStoreDialog = false; onChanged()
    }
    deleteRecord?.let { r -> ConfirmDelete("删除 ${r.date} ${r.storeName} 的营业记录？", { deleteRecord = null }) {
        db.deleteStoreDailyRecord(r.id); deleteRecord = null; onChanged()
    } }
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
private fun SettlementScreen(db: AppDatabase, dataVersion: Int) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val summary = remember(dataVersion, date) { db.getDailySummary(date) }
    val purchases = remember(dataVersion, date) { db.getPurchaseTotalsByPartner(date) }
    val receipts = remember(dataVersion, date) { db.getReceiptsByPartner(date) }
    val records = remember(dataVersion, date) { db.getDailyRecords(date) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageHeader("每日总账结算", "这里只汇总经营数据；利润分配在“更多 → 利润分配”独立处理") }
        item { DateField("结算日期", date) { date = it } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("总营业额", money(summary.revenue), Modifier.weight(1f), SoftGreen, "${summary.storeCount} 个摊位")
                MetricCard("总利润", money(summary.profit), Modifier.weight(1f), SoftOrange)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("总进货", money(summary.purchaseCost), Modifier.weight(1f), SoftPurple)
                MetricCard("总费用", money(summary.expense), Modifier.weight(1f), SoftBlue)
            }
        }
        item {
            Text("各自进货金额", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (purchases.isEmpty()) Text("暂无进货", color = Color.Gray)
            purchases.forEach { p -> SummaryRow(p.partnerName, money(p.amount)) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SummaryRow("合计", money(purchases.sumOf { it.amount }), true)
        }
        item {
            Text("各自实际收款", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (receipts.isEmpty()) Text("暂无收款", color = Color.Gray)
            receipts.forEach { p -> SummaryRow(p.partnerName, money(p.amount)) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SummaryRow("合计", money(receipts.sumOf { it.amount }), true)
        }
        item {
            Text("各摊位明细", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (records.isEmpty()) Text("暂无营业记录", color = Color.Gray)
            records.forEach { r ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(r.storeName, fontWeight = FontWeight.Bold)
                        Text("营业 ${money(r.revenue)} · 进货 ${money(r.purchaseCost)} · 费用 ${money(r.expense)}")
                        Text("利润 ${money(r.profit)} · 客户 ${r.customerTotal} 人")
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreScreen(db: AppDatabase, dataVersion: Int, onChanged: () -> Unit) {
    var sub by remember { mutableStateOf(MorePage.MENU) }
    BackHandler(enabled = sub != MorePage.MENU) { sub = MorePage.MENU }
    when (sub) {
        MorePage.MENU -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { PageHeader("更多", "历史、统计、合伙人、位置和独立利润分配") }
            item { MenuCard("🕘 历史记录", "查看并删除历史进货、历史营业") { sub = MorePage.HISTORY } }
            item { MenuCard("📊 经营统计", "营业额 / 利润 / 客户排行榜，水果历史进价") { sub = MorePage.STATS } }
            item { MenuCard("👥 合伙人", "人数不设上限，可新增、改名或删除") { sub = MorePage.PARTNERS } }
            item { MenuCard("📍 摆摊位置", "新增或删除位置；删除不影响历史记录") { sub = MorePage.STORES } }
            item { MenuCard("💰 利润分配", "独立利润分配表；每位合伙人的百分比可自定义") { sub = MorePage.PROFIT } }
        }
        MorePage.HISTORY -> SubPage("历史记录", { sub = MorePage.MENU }) { HistoryContent(db, dataVersion, onChanged) }
        MorePage.STATS -> SubPage("经营统计", { sub = MorePage.MENU }) { StatsContent(db, dataVersion) }
        MorePage.PARTNERS -> SubPage("合伙人管理", { sub = MorePage.MENU }) { PartnerContent(db, dataVersion, onChanged) }
        MorePage.STORES -> SubPage("摆摊位置管理", { sub = MorePage.MENU }) { StoreContent(db, dataVersion, onChanged) }
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
        item { HorizontalDivider(); Text("进货历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (purchases.isEmpty()) item { Text("暂无", color = Color.Gray) }
        items(purchases, key = { "p${it.order.id}" }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("${p.order.date} · ${p.order.buyerName}", fontWeight = FontWeight.Bold); Text("${p.order.storeName} · ${money(p.order.totalCost)}") }
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
        item { RankingSection("利润排行榜", rankings.sortedByDescending { it.profit }) { money(it.profit) } }
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
                    Text("${p.buyerName} → ${p.storeName} · ${fmt(p.quantity)}${p.unit}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
        item { Text("当前 ${partners.size} 位合伙人，人数不设上限。改名只影响以后选择，历史记录保留当时姓名。", color = Color.Gray) }
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
    delete?.let { p -> ConfirmDelete("删除合伙人“${p.name}”？历史进货、历史收款和历史分红仍保留原姓名。", { delete = null }) {
        db.deletePartner(p.id); delete = null; onChanged()
    } }
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
private fun CompactNumberField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.matches(Regex("^\\d*(\\.\\d{0,2})?$"))) onValue(it) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun CompactIntegerField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.all(Char::isDigit)) onValue(it) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun CompactReadOnlyField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        OutlinedTextField(value = value, onValueChange = {}, modifier = Modifier.fillMaxWidth().height(48.dp), readOnly = true, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium)
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
    var unit by remember { mutableStateOf("斤") }
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
