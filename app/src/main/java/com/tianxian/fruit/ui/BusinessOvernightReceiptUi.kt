package com.tianxian.fruit.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.tianxian.fruit.data.*
import java.time.LocalDate
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private val overnightAccent = Color(0xFF087D4E)
private val overnightWarning = Color(0xFF976300)

internal data class OvernightCollectorOption(
    val rowId: Long,
    val partnerId: Long?,
    val partnerName: String
)

/**
 * Auxiliary, book-scoped, local-only drafts; NEVER changes source receipts,
 * daily settlement, cloud sync records or saved business totals.
 * A later backend migration is needed before cross-device sync is possible.
 */
internal class OvernightReceiptDraftStore(context: Context, bookId: String) {
    private val prefs = context.getSharedPreferences(
        "overnight_receipts_${bookId.hashCode()}", Context.MODE_PRIVATE
    )

    fun snapshots(): List<OvernightReceiptSnapshot> = runCatching {
        val array = JSONArray(prefs.getString("snapshots", "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val item = OvernightReceiptSnapshot(
                    row.optString("date"), row.optLong("store"), row.optLong("partner"),
                    row.optDouble("wechat", Double.NaN), row.optDouble("alipay", Double.NaN)
                )
                if (item.storeId > 0 && item.partnerId > 0 &&
                    item.wechat.isFinite() && item.alipay.isFinite()) add(item)
            }
        }
    }.getOrElse { emptyList() }

    fun naturalDrafts(): List<NaturalDayReceiptDraft> = runCatching {
        val array = JSONArray(prefs.getString("natural", "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val item = NaturalDayReceiptDraft(
                    row.optString("date"), row.optLong("store"), row.optLong("partner"),
                    row.optDouble("wechat", Double.NaN), row.optDouble("alipay", Double.NaN)
                )
                if (item.storeId > 0 && item.partnerId > 0 &&
                    item.wechat.isFinite() && item.alipay.isFinite()) add(item)
            }
        }
    }.getOrElse { emptyList() }

    fun saveSnapshot(item: OvernightReceiptSnapshot): Boolean {
        val rows = OvernightReceiptPolicy.replace(item, snapshots()) ?: return false
        val data = JSONArray().apply {
            rows.forEach { row ->
                put(JSONObject().put("date", row.calendarDate)
                    .put("store", row.storeId).put("partner", row.partnerId)
                    .put("wechat", row.wechat).put("alipay", row.alipay))
            }
        }
        return prefs.edit().putString("snapshots", data.toString()).commit()
    }

    fun saveNatural(item: NaturalDayReceiptDraft): Boolean {
        val day = runCatching { LocalDate.parse(item.businessDate) }.getOrNull() ?: return false
        if (day.isAfter(LocalDate.now()) || item.partnerId <= 0 || item.storeId <= 0 ||
            listOf(item.wechat, item.alipay).any { !it.isFinite() || it < 0.0 })
            return false
        val previous = naturalDrafts()
        // A merchant's full calendar-day total cannot be assigned in full to
        // two different stores, even on a multi-location selling day.
        if (previous.any { it.businessDate == item.businessDate &&
                it.partnerId == item.partnerId && it.storeId != item.storeId }) return false
        val rows = previous.filterNot {
            it.businessDate == item.businessDate &&
                it.storeId == item.storeId && it.partnerId == item.partnerId
        } + item
        val data = JSONArray().apply {
            rows.forEach { row ->
                put(JSONObject().put("date", row.businessDate)
                    .put("store", row.storeId).put("partner", row.partnerId)
                    .put("wechat", row.wechat).put("alipay", row.alipay))
            }
        }
        return prefs.edit().putString("natural", data.toString()).commit()
    }
}

private fun overnightMoney(value: Double): String =
    String.format(Locale.CHINA, "¥%.2f", value)

@Composable
internal fun BusinessOvernightReceiptAction(
    bookId: String,
    businessDate: String,
    storeId: Long?,
    collectors: List<OvernightCollectorOption>,
    conflictingPartnerIds: Set<Long> = emptySet(),
    canApply: Boolean,
    onApply: (Long, Double, Double) -> Unit
) {
    val context = LocalContext.current
    val storage = remember(context, bookId) { OvernightReceiptDraftStore(context, bookId) }
    var open by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val store = storeId?.takeIf { it > 0L }
    val validCollectors = collectors.filter { (it.partnerId ?: 0L) > 0L }
        .distinctBy { it.partnerId }
    if (store == null || validCollectors.isEmpty()) {
        Text("凌晨结转：请先选择营业位置与收款人",
            style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        return
    }
    val savedNatural = remember(refresh, businessDate, store, validCollectors) {
        storage.naturalDrafts().filter { draft ->
            draft.businessDate == businessDate && draft.storeId == store &&
                validCollectors.any { it.partnerId == draft.partnerId }
        }
    }
    val snapshots = remember(refresh) { storage.snapshots() }
    val pending = savedNatural.any { natural ->
        val adjusted = OvernightReceiptPolicy.adjustment(natural, snapshots)
        adjusted != null && !adjusted.fullyConfirmed
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("今日总额 / 凌晨结转 ›",
            modifier = Modifier.clickable { open = true }.padding(vertical = 4.dp)
                .weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = overnightAccent, fontWeight = FontWeight.SemiBold)
        if (pending) Text("跨日待核对", style = MaterialTheme.typography.labelSmall,
            color = overnightWarning)
    }
    if (!open) return

    val selectedBusinessDay = remember(businessDate) {
        runCatching { LocalDate.parse(businessDate) }.getOrDefault(LocalDate.now())
    }
    var partner by remember(open, businessDate, store) {
        mutableLongStateOf(validCollectors.first().partnerId!!)
    }
    val accountSharedAcrossStores = partner in conflictingPartnerIds
    val mayApply = canApply && !accountSharedAcrossStores
    var showPartnerMenu by remember { mutableStateOf(false) }
    var mode by remember(open) { mutableStateOf("NATURAL") }
    var snapshotDate by remember(open, businessDate) {
        mutableStateOf(LocalDate.now())
    }
    var wx by remember(open, businessDate, store, partner, mode, snapshotDate) {
        mutableStateOf(
            if (mode == "NATURAL") storage.naturalDrafts().firstOrNull {
                it.businessDate == businessDate && it.partnerId == partner && it.storeId == store
            }?.wechat?.let { String.format(Locale.US, "%.2f", it) }.orEmpty()
            else storage.snapshots().firstOrNull {
                it.calendarDate == snapshotDate.toString() && it.partnerId == partner
            }?.wechat?.let { String.format(Locale.US, "%.2f", it) }.orEmpty()
        )
    }
    var ali by remember(open, businessDate, store, partner, mode, snapshotDate) {
        mutableStateOf(
            if (mode == "NATURAL") storage.naturalDrafts().firstOrNull {
                it.businessDate == businessDate && it.partnerId == partner && it.storeId == store
            }?.alipay?.let { String.format(Locale.US, "%.2f", it) }.orEmpty()
            else storage.snapshots().firstOrNull {
                it.calendarDate == snapshotDate.toString() && it.partnerId == partner
            }?.alipay?.let { String.format(Locale.US, "%.2f", it) }.orEmpty()
        )
    }
    var error by remember(open, businessDate) { mutableStateOf("") }
    val wValue = wx.toDoubleOrNull()
    val aValue = ali.toDoubleOrNull()
    val valid = wValue != null && aValue != null &&
        wValue.isFinite() && aValue.isFinite() && wValue >= 0 && aValue >= 0
    val draft = if (valid) NaturalDayReceiptDraft(
        businessDate, store, partner, wValue!!, aValue!!
    ) else null
    val adjustment = if (mode == "NATURAL" && draft != null) {
        OvernightReceiptPolicy.adjustment(draft, snapshots)
    } else null
    val existingSnapshot = storage.snapshots().firstOrNull {
        it.calendarDate == snapshotDate.toString() && it.partnerId == partner
    }

    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("营业收款 · 自动跨日结转", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    FilterChip(selected = mode == "NATURAL", onClick = {
                        mode = "NATURAL"; error = ""
                    }, label = { Text("录今日总额") })
                    FilterChip(selected = mode == "MORNING", onClick = {
                        mode = "MORNING"; error = ""
                    }, label = { Text("登记凌晨收款") })
                }
                Box {
                    Text(
                        "收款人：${validCollectors.firstOrNull { it.partnerId == partner }?.partnerName ?: "选择收款人"} ▾",
                        modifier = Modifier.fillMaxWidth().clickable { showPartnerMenu = true }
                            .padding(vertical = 6.dp),
                        color = overnightAccent, fontWeight = FontWeight.SemiBold)
                    DropdownMenu(expanded = showPartnerMenu,
                        onDismissRequest = { showPartnerMenu = false }) {
                        validCollectors.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.partnerName) },
                                onClick = {
                                    partner = option.partnerId!!
                                    showPartnerMenu = false
                                }
                            )
                        }
                    }
                }

                if (mode == "MORNING") {
                    Text("凌晨收款的自然日期：${snapshotDate}",
                        fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        TextButton(onClick = { snapshotDate = selectedBusinessDay }) {
                            Text("本营业日凌晨")
                        }
                        TextButton(onClick = { snapshotDate = selectedBusinessDay.plusDays(1) }) {
                            Text("次日凌晨")
                        }
                    }
                    Text("这笔凌晨收款归属 ${snapshotDate.minusDays(1)} 的营业；" +
                        "只录凌晨0点以后至收摊的收款额，不是整晚总营业额。",
                        style = MaterialTheme.typography.bodySmall, color = overnightWarning)
                    if (snapshotDate.isAfter(LocalDate.now())) {
                        Text("未来凌晨尚未发生，不能提前登记。",
                            style = MaterialTheme.typography.labelSmall, color = overnightWarning)
                    }
                    if (existingSnapshot != null && existingSnapshot.storeId != store) {
                        Text("这个收款人当日凌晨已归属另一个位置。相同商家账号不允许重复分配；请到原位置核对。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Text("${businessDate} 微信/支付宝商家后台的自然日「今日总额」",
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = wx, onValueChange = { wx = it }, label = { Text("微信（元）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(
                    value = ali, onValueChange = { ali = it }, label = { Text("支付宝（元）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (mode == "NATURAL" && adjustment != null) {
                    HorizontalDivider()
                    Text("微信：${overnightMoney(adjustment.naturalWechat)} " +
                        "−${overnightMoney(adjustment.previousWechat)} " +
                        "+${overnightMoney(adjustment.followingWechat)} " +
                        "= ${overnightMoney(adjustment.adjustedWechat)}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("支付宝：${overnightMoney(adjustment.naturalAlipay)} " +
                        "−${overnightMoney(adjustment.previousAlipay)} " +
                        "+${overnightMoney(adjustment.followingAlipay)} " +
                        "= ${overnightMoney(adjustment.adjustedAlipay)}",
                        style = MaterialTheme.typography.bodySmall)
                    if (!adjustment.previousConfirmed)
                        Text("本日凌晨尚未登记：系统暂按0扣减，请登记金额（无收款也可登记0）。",
                            style = MaterialTheme.typography.labelSmall, color = overnightWarning)
                    if (!adjustment.followingConfirmed)
                        Text("次日凌晨尚未登记：目前是临时结果；确认后可再次填入差额。",
                            style = MaterialTheme.typography.labelSmall, color = overnightWarning)
                    if (accountSharedAcrossStores)
                        Text("该收款人当天在其他位置也有电子收款；商家后台全天总额不能全部归给本位置，请先按交易明细拆分。",
                            style = MaterialTheme.typography.labelSmall, color = overnightWarning)
                    if (!canApply)
                        Text("已有正式营业记录时，请先点击该位置营业记录进入编辑，再填入调整金额；系统不会自动覆盖已保存收入。",
                            style = MaterialTheme.typography.labelSmall, color = overnightWarning)
                }
                if (mode == "NATURAL" && adjustment == null && valid) {
                    Text("跨日扣减大于自然日金额，无法计算。请核对收款人、日期和凌晨金额。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall)
                }
                if (error.isNotBlank())
                    Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                Text("只影响当前营业表待保存的微信/支付宝输入框；现金、正式结算与利润不会自动改动。" +
                    "凌晨登记目前仅保存在本设备，未加入云同步。",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid &&
                    (if (mode == "NATURAL") adjustment != null
                    else snapshotDate <= LocalDate.now() &&
                        (existingSnapshot == null || existingSnapshot.storeId == store)),
                onClick = {
                    if (mode == "MORNING") {
                        val confirmed = storage.saveSnapshot(OvernightReceiptSnapshot(
                            snapshotDate.toString(),store,partner,wValue!!,aValue!!
                        ))
                        if (confirmed) { refresh++; open = false }
                        else error = "保存失败：同一收款人当天凌晨已有其他位置归属，或本机写入失败。"
                    } else if (draft != null && adjustment != null) {
                        if (!storage.saveNatural(draft))
                            error = "无法保存：这个收款人的自然日总额已归属其他位置，不能重复计算。"
                        else if (mayApply) {
                            val selectedRow = collectors.firstOrNull { it.partnerId == partner }
                            if (selectedRow == null)
                                error = "当前收款人已经不在营业表中，请重新选择。"
                            else {
                                onApply(selectedRow.rowId,
                                    adjustment.adjustedWechat, adjustment.adjustedAlipay)
                                refresh++
                                open = false
                            }
                        } else {
                            refresh++
                            open = false
                        }
                    }
                }
            ) { Text(if (mode == "MORNING") "保存凌晨金额"
                else if (mayApply) "计算并填入营业表" else "保存计算草稿") }
        },
        dismissButton = {
            TextButton(onClick = { open = false }) { Text("取消") }
        }
    )
}
