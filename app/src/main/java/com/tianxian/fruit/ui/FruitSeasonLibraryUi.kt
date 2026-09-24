package com.tianxian.fruit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxian.fruit.data.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val SeasonGreen = Color(0xFF13A868)

private fun mdValue(text: String): Int? {
    val parts = text.trim().split("-")
    if (parts.size != 2) return null
    val m = parts[0].toIntOrNull() ?: return null
    val d = parts[1].toIntOrNull() ?: return null
    return m * 100 + d
}

private fun mdInRange(value: Int, startText: String, endText: String): Boolean {
    val start = mdValue(startText) ?: return false
    val end = mdValue(endText) ?: return false
    return if (start <= end) value in start..end else value >= start || value <= end
}

private fun regionSeasonStage(region: FruitSeasonRegionRecord, date: LocalDate = LocalDate.now()): String {
    val value = date.monthValue * 100 + date.dayOfMonth
    return when {
        mdInRange(value, region.highStart, region.highEnd) -> "高峰期"
        mdInRange(value, region.peakStart, region.peakEnd) -> "旺季"
        mdInRange(value, region.earlyStart, region.peakStart) -> "初上市"
        mdInRange(value, region.peakEnd, region.lateEnd) -> "尾季"
        else -> "非主产季"
    }
}

private fun overviewStage(item: FruitSeasonOverviewRecord): String {
    val stages = item.regions.map { regionSeasonStage(it) }
    return when {
        "高峰期" in stages -> "高峰期"
        "旺季" in stages -> "旺季"
        "初上市" in stages -> "初上市"
        "尾季" in stages -> "尾季"
        else -> "非主产季"
    }
}

private fun stageTone(stage: String): Color = when(stage) {
    "高峰期" -> Color(0xFFE5F7EE)
    "旺季" -> Color(0xFFEDF8E8)
    "初上市" -> Color(0xFFFFF5E5)
    "尾季" -> Color(0xFFFFF0EA)
    else -> Color(0xFFF1F1F1)
}

@Composable
fun FruitSeasonLibraryContent(
    db: AppDatabase,
    dataVersion: Int,
    canEdit: Boolean,
    onChanged: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var onlySeasonal by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FruitSeasonOverviewRecord?>(null) }
    var addCatalog by remember { mutableStateOf(false) }
    val categories = remember(dataVersion) { db.getFruitSeasonCategories() }
    val baseItems = remember(dataVersion, query, category) { db.getFruitSeasonOverviews(query, category) }
    val items = remember(baseItems, onlySeasonal) {
        baseItems
            .filter { !onlySeasonal || overviewStage(it) != "非主产季" }
            .sortedWith(compareByDescending<FruitSeasonOverviewRecord> {
                when (overviewStage(it)) { "高峰期" -> 4; "旺季" -> 3; "初上市" -> 2; "尾季" -> 1; else -> 0 }
            }.thenBy { it.catalog.category }.thenBy { it.catalog.standardName })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(
            "按品种、产区记录初上市 / 旺季 / 高峰 / 尾季。第一版只做资料库，不参与采购推荐。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索水果、品种或别名") }
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            FilterChip(selected = onlySeasonal, onClick = { onlySeasonal = !onlySeasonal }, label = { Text("当季") })
            FilterChip(selected = category.isBlank(), onClick = { category = "" }, label = { Text("全部") })
            categories.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }) }
        }
        Text("当前显示 ${items.size} 种", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 6.dp))
        if (canEdit) {
            OutlinedButton(onClick = { addCatalog = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ 新增水果资料") }
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            if (items.isEmpty()) {
                Text("没有匹配的水果资料", color = Color.Gray, modifier = Modifier.padding(20.dp))
            }
            items.forEach { item ->
                val stage = overviewStage(item)
                Card(
                    onClick = { selected = item },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(item.catalog.standardName, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(
                                    listOf(item.catalog.category, item.catalog.variety).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = Color.Gray
                                )
                            }
                            Surface(shape = RoundedCornerShape(50), color = stageTone(stage)) {
                                Text(stage, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = SeasonGreen, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        val regionText = item.regions.take(3).joinToString(" / ") {
                            listOf(it.country.takeIf { x -> x != "中国" }, it.province, it.region).filterNotNull().filter { x -> x.isNotBlank() }.distinct().joinToString(" · ")
                        }
                        if (regionText.isNotBlank()) Text("主要产区：$regionText", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        if (item.linkedFruits.isNotEmpty()) Text("已关联账本商品：${item.linkedFruits.joinToString("、") { it.name }}", style = MaterialTheme.typography.labelSmall, color = SeasonGreen, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    selected?.let { current ->
        FruitSeasonDetailDialog(
            item = current,
            db = db,
            canEdit = canEdit,
            onDismiss = { selected = null },
            onChanged = {
                onChanged()
                selected = db.getFruitSeasonOverview(current.catalog.syncId)
            }
        )
    }
    if (addCatalog) {
        AddSeasonCatalogDialog(
            onDismiss = { addCatalog = false },
            onSave = { name, cat, variety ->
                val syncId = db.addFruitSeasonCatalog(name, cat, variety)
                if (syncId != null) db.addFruitSeasonRegion(syncId, name)
                addCatalog = false
                onChanged()
            }
        )
    }
}

@Composable
private fun FruitSeasonDetailDialog(
    item: FruitSeasonOverviewRecord,
    db: AppDatabase,
    canEdit: Boolean,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    var editRegion by remember { mutableStateOf<FruitSeasonRegionRecord?>(null) }
    var linkDialog by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.catalog.standardName) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text("${item.catalog.category}${if(item.catalog.variety.isNotBlank()) " · ${item.catalog.variety}" else ""}", color = Color.Gray)
                if (item.aliases.isNotEmpty()) Text("常用别名：${item.aliases.joinToString("、")}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                item.profile?.let { p ->
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text("基础属性", fontWeight = FontWeight.Bold)
                    Text("耐储存：${p.storageLevel}　常温约${p.roomDays}天　冷藏约${p.coldDays}天")
                    Text("易损：${p.damageLevel}　运输耐受：${p.transportLevel}")
                    if (p.packageSpec.isNotBlank()) Text("常见规格：${p.packageSpec} ${p.weightRange}")
                    if (p.sweetnessNote.isNotBlank()) Text("口感：${p.sweetnessNote}")
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("产区与季节", fontWeight = FontWeight.Bold)
                if (item.regions.isEmpty()) Text("暂无产区资料", color = Color.Gray)
                item.regions.forEach { r ->
                    val stage = regionSeasonStage(r)
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F8F8))) {
                        Column(Modifier.padding(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(listOf(r.country, r.province, r.region).filter { it.isNotBlank() }.joinToString(" · "), fontWeight = FontWeight.SemiBold)
                                Text(stage, color = SeasonGreen, fontWeight = FontWeight.Bold)
                            }
                            Text("初上市 ${r.earlyStart.ifBlank { "—" }}　旺季 ${r.peakStart.ifBlank { "—" }}～${r.peakEnd.ifBlank { "—" }}")
                            Text("高峰 ${r.highStart.ifBlank { "—" }}～${r.highEnd.ifBlank { "—" }}　尾季至 ${r.lateEnd.ifBlank { "—" }}")
                            Text("${r.cultivation} · 可信度 ${when(r.confidence){"HIGH"->"高";"LOW"->"低";else->"中"}}${if(r.manualOverride) " · 已人工修正" else ""}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            if (r.sourceName.isNotBlank()) Text("来源：${r.sourceName} · 更新 ${r.verifiedAt.ifBlank { "—" }}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            if (r.sourceUrl.isNotBlank()) Text(r.sourceUrl, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
                            if (canEdit) TextButton(onClick = { editRegion = r }) { Text("修改季节资料") }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("账本商品关联", fontWeight = FontWeight.Bold)
                Text(if(item.linkedFruits.isEmpty()) "尚未关联" else item.linkedFruits.joinToString("、") { it.name }, color = if(item.linkedFruits.isEmpty()) Color.Gray else SeasonGreen)
                if (canEdit) OutlinedButton(onClick = { linkDialog = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("关联 / 取消关联") }
                Text("说明：季节库当前只做资料展示与数据沉淀，不参与采购数量、利润或经营指数计算。", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
    editRegion?.let { r ->
        EditSeasonRegionDialog(r, { editRegion = null }) { values ->
            db.updateFruitSeasonRegion(
                r.id, values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9], values[10], values[11], values[12], values[13]
            )
            editRegion = null; onChanged()
        }
    }
    if (linkDialog) {
        FruitSeasonLinkDialog(db, item, { linkDialog = false }) { fruitId, checked ->
            db.setFruitSeasonLink(fruitId, if (checked) item.catalog.syncId else "")
            onChanged()
        }
    }
}

@Composable
private fun EditSeasonRegionDialog(
    r: FruitSeasonRegionRecord,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val initial = listOf(r.country,r.province,r.region,r.cultivation,r.earlyStart,r.peakStart,r.highStart,r.highEnd,r.peakEnd,r.lateEnd,r.sourceName,r.sourceUrl,r.confidence,r.note)
    val fields = remember(r.id) { initial.map { mutableStateOf(it) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改 ${r.catalogName} 产季") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max=560.dp).verticalScroll(rememberScrollState())) {
                listOf("国家","省份","产区","栽培方式","初上市 MM-DD","旺季开始 MM-DD","高峰开始 MM-DD","高峰结束 MM-DD","旺季结束 MM-DD","尾季结束 MM-DD","来源名称","来源网址","可信度 HIGH/MEDIUM/LOW","备注").forEachIndexed { index,label ->
                    OutlinedTextField(fields[index].value,{fields[index].value=it},label={Text(label)},singleLine=index!=13,modifier=Modifier.fillMaxWidth().padding(bottom=5.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(fields.map { it.value }) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun FruitSeasonLinkDialog(
    db: AppDatabase,
    item: FruitSeasonOverviewRecord,
    onDismiss: () -> Unit,
    onToggle: (Long, Boolean) -> Unit
) {
    val fruits = remember { db.getFruits() }
    val linked = remember(item.linkedFruits) { mutableStateMapOf<Long,Boolean>().apply { fruits.forEach { put(it.id, item.linkedFruits.any { x -> x.id==it.id }) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关联账本商品") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max=460.dp).verticalScroll(rememberScrollState())) {
                fruits.filter { it.name != "总价" }.forEach { f ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(f.name, modifier=Modifier.padding(vertical=12.dp))
                        Checkbox(checked=linked[f.id]==true,onCheckedChange={ checked -> linked[f.id]=checked; onToggle(f.id,checked) })
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick=onDismiss){Text("完成")} }
    )
}

@Composable
private fun AddSeasonCatalogDialog(
    onDismiss: () -> Unit,
    onSave: (String,String,String) -> Unit
) {
    var name by remember { mutableStateOf("") }; var category by remember { mutableStateOf("其他") }; var variety by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("新增水果资料")},
        text={ Column { OutlinedTextField(name,{name=it},label={Text("标准名称")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(category,{category=it},label={Text("分类")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(variety,{variety=it},label={Text("品种")},modifier=Modifier.fillMaxWidth()) } },
        confirmButton={TextButton(enabled=name.isNotBlank(),onClick={onSave(name,category,variety)}){Text("保存")}},
        dismissButton={TextButton(onClick=onDismiss){Text("取消")}}
    )
}
