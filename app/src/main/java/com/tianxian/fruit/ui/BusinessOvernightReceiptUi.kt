package com.tianxian.fruit.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxian.fruit.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * Book-isolated edit metadata, NOT part of official business/settlement data.
 * Stable sync IDs are resolved by CustomerCloudBridge before network upload.
 */
internal object OvernightReceiptRefresh {
    val revision = mutableIntStateOf(0)
    fun changed() { revision.intValue += 1 }
}

internal class OvernightReceiptDraftStore(context: Context, bookId: String) {
    private val prefs=context.getSharedPreferences(
        "overnight_receipts_${bookId.hashCode()}",Context.MODE_PRIVATE)

    fun snapshots(): List<OvernightReceiptSnapshot> = runCatching {
        val a=JSONArray(prefs.getString("snapshots","[]"))
        buildList {
            for(i in 0 until a.length()) {
                val j=a.optJSONObject(i) ?: continue
                val s=OvernightReceiptSnapshot(
                    calendarDate=j.optString("date"),
                    storeId=j.optLong("store"),
                    partnerId=j.optLong("partner"),
                    wechat=j.optDouble("wechat",Double.NaN),
                    alipay=j.optDouble("alipay",Double.NaN),
                    cash=j.optDouble("cash",0.0),
                    // Old V121 snapshots were set for both payment channels.
                    wechatRecorded=j.optBoolean("wechat_recorded",true),
                    alipayRecorded=j.optBoolean("alipay_recorded",true),
                    cashRecorded=j.optBoolean("cash_recorded",false)
                )
                if(s.storeId>0 && s.partnerId>0 &&
                    listOf(s.wechat,s.alipay,s.cash).all { it.isFinite() && it>=0.0 }) add(s)
            }
        }
    }.getOrElse { emptyList() }

    fun naturalDrafts(): List<NaturalDayReceiptDraft> = runCatching {
        val a=JSONArray(prefs.getString("natural","[]"))
        buildList {
            for(i in 0 until a.length()) {
                val j=a.optJSONObject(i) ?: continue
                val s=NaturalDayReceiptDraft(j.optString("date"),j.optLong("store"),
                    j.optLong("partner"),j.optDouble("wechat",Double.NaN),
                    j.optDouble("alipay",Double.NaN),j.optDouble("cash",0.0),
                    j.optBoolean("deduct_wechat",true),j.optBoolean("deduct_alipay",true),
                    j.optDouble("saved_wechat",Double.NaN).takeIf { it.isFinite() },
                    j.optDouble("saved_alipay",Double.NaN).takeIf { it.isFinite() },
                    j.optDouble("saved_cash",Double.NaN).takeIf { it.isFinite() },
                    // Pre-V123 cash was never deducted; preserve that
                    // behavior on previously saved draft records.
                    j.optBoolean("deduct_cash",false))
                if(s.storeId>0 && s.partnerId>0 &&
                    listOf(s.wechat,s.alipay,s.cash).all { it.isFinite() && it>=0.0 }) add(s)
            }
        }
    }.getOrElse { emptyList() }

    fun saveSnapshot(s: OvernightReceiptSnapshot): Boolean {
        val next=OvernightReceiptPolicy.replace(s,snapshots()) ?: return false
        val a=JSONArray()
        next.forEach { row ->
            a.put(JSONObject().put("date",row.calendarDate)
                .put("store",row.storeId).put("partner",row.partnerId)
                .put("wechat",row.wechat).put("alipay",row.alipay).put("cash",row.cash)
                .put("wechat_recorded",row.wechatRecorded)
                .put("alipay_recorded",row.alipayRecorded)
                .put("cash_recorded",row.cashRecorded))
        }
        val saved = prefs.edit().putString("snapshots",a.toString()).commit()
        if (saved) OvernightReceiptRefresh.changed()
        return saved
    }

    fun saveNatural(s: NaturalDayReceiptDraft): Boolean {
        if (runCatching { LocalDate.parse(s.businessDate) }.getOrNull()==null ||
            s.storeId<=0 || s.partnerId<=0 ||
            listOf(s.wechat,s.alipay,s.cash).any { !it.isFinite() || it<0.0 }) return false
        val before=naturalDrafts()
        // One provider calendar total belongs to one store per collector/date.
        if(before.any { it.businessDate==s.businessDate &&
                it.partnerId==s.partnerId && it.storeId!=s.storeId }) return false
        val next=before.filterNot {
            it.businessDate==s.businessDate && it.partnerId==s.partnerId
        }+s
        val a=JSONArray()
        next.forEach { row ->
            a.put(JSONObject().put("date",row.businessDate)
                .put("store",row.storeId).put("partner",row.partnerId)
                .put("wechat",row.wechat).put("alipay",row.alipay).put("cash",row.cash)
                .put("deduct_wechat",row.deductWechat)
                .put("deduct_alipay",row.deductAlipay)
                .put("deduct_cash",row.deductCash)
                .put("saved_wechat",row.savedWechat)
                .put("saved_alipay",row.savedAlipay)
                .put("saved_cash",row.savedCash))
        }
        val saved = prefs.edit().putString("natural",a.toString()).commit()
        if (saved) OvernightReceiptRefresh.changed()
        return saved
    }

    /**
     * A deleted sale for D owns the morning of D+1, not the morning of D.
     * Retain cleared rows as syncable tombstones so remote devices cannot
     * restore yesterday's amount from the customer-analytics API.
     */
    fun clearForDeletedBusiness(
        businessDate: String,
        storeId: Long,
        partnerIds: Set<Long> = emptySet()
    ): Boolean {
        val morning = OvernightReceiptDeletionPolicy.emptySnapshot(
            businessDate, storeId, 1L
        )?.calendarDate ?: return false
        val oldSnapshots = snapshots()
        val oldNatural = naturalDrafts()
        val nextSnapshots = oldSnapshots.map {
            OvernightReceiptDeletionPolicy.clearedSnapshot(it, businessDate, storeId)
        }.toMutableList()
        for (partnerId in partnerIds.filter { it > 0L }) {
            // Never steal an adjacent stall's snapshot for the same collector.
            if (nextSnapshots.any { it.calendarDate == morning && it.partnerId == partnerId }) {
                continue
            }
            OvernightReceiptDeletionPolicy.emptySnapshot(
                businessDate, storeId, partnerId
            )?.let { nextSnapshots.add(it) }
        }
        val nextNatural = oldNatural.map {
            OvernightReceiptDeletionPolicy.clearedNatural(it, businessDate, storeId)
        }
        if (nextSnapshots == oldSnapshots && nextNatural == oldNatural) return true

        val snapshotJson = JSONArray()
        nextSnapshots.forEach { row ->
            snapshotJson.put(JSONObject().put("date", row.calendarDate)
                .put("store", row.storeId).put("partner", row.partnerId)
                .put("wechat", row.wechat).put("alipay", row.alipay)
                .put("cash", row.cash)
                .put("wechat_recorded", row.wechatRecorded)
                .put("alipay_recorded", row.alipayRecorded)
                .put("cash_recorded", row.cashRecorded))
        }
        val naturalJson = JSONArray()
        nextNatural.forEach { row ->
            naturalJson.put(JSONObject().put("date", row.businessDate)
                .put("store", row.storeId).put("partner", row.partnerId)
                .put("wechat", row.wechat).put("alipay", row.alipay)
                .put("cash", row.cash)
                .put("deduct_wechat", row.deductWechat)
                .put("deduct_alipay", row.deductAlipay)
                .put("deduct_cash", row.deductCash)
                .put("saved_wechat", row.savedWechat)
                .put("saved_alipay", row.savedAlipay)
                .put("saved_cash", row.savedCash))
        }
        val saved = prefs.edit()
            .putString("snapshots", snapshotJson.toString())
            .putString("natural", naturalJson.toString())
            .commit()
        if (saved) OvernightReceiptRefresh.changed()
        return saved
    }

    fun findSnapshot(date:String,partner:Long): OvernightReceiptSnapshot? =
        snapshots().firstOrNull { it.calendarDate==date && it.partnerId==partner }

    fun findNatural(date:String,store:Long,partner:Long): NaturalDayReceiptDraft? =
        naturalDrafts().firstOrNull {
            it.businessDate==date && it.storeId==store && it.partnerId==partner
        }
}

internal data class OvernightInlineState(
    val previous: OvernightReceiptSnapshot?,
    val following: OvernightReceiptSnapshot?,
    val deductWechat: Boolean,
    val deductAlipay: Boolean,
    val deductCash: Boolean,
    val hasConflict: Boolean
)

private fun displayOvernight(value: Double?): String =
    value?.takeIf { it>0.0 }?.let {
        String.format(Locale.CHINA,"%s",if(it%1.0==0.0) it.toLong().toString()
        else String.format(Locale.CHINA,"%.2f",it).trimEnd('0').trimEnd('.'))
    } ?: "－"

/**
 * Small number only, directly under the original channel input.
 * No dedicated always-visible input or separate "today total" mode.
 */
@Composable
internal fun OvernightInlineNumber(
    bookId: String,
    date: String,
    storeId: Long?,
    partnerId: Long?,
    channel: String,
    tick: Int,
    onChanged: () -> Unit
) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val store=remember(context,bookId) { OvernightReceiptDraftStore(context,bookId) }
    val morning=remember(date) {
        runCatching { LocalDate.parse(date).plusDays(1) }.getOrNull()
    }
    val refreshVersion = OvernightReceiptRefresh.revision.intValue
    val snapshot=remember(tick,date,storeId,partnerId,refreshVersion) {
        if (morning!=null && partnerId!=null)
            store.findSnapshot(morning.toString(),partnerId) else null
    }
    val amount=if(snapshot?.storeId==storeId) snapshot?.amount(channel) else null
    var open by remember(date,storeId,partnerId,channel) { mutableStateOf(false) }
    var input by remember(date,storeId,partnerId,channel) { mutableStateOf("") }
    var error by remember(date,storeId,partnerId,channel) { mutableStateOf("") }
    Text(displayOvernight(amount),
        modifier=Modifier.fillMaxWidth()
            .clickable(enabled=storeId!=null && partnerId!=null) {
                error=""
                input=amount?.let { if(it==0.0) "0" else
                    String.format(Locale.US,"%.2f",it) }.orEmpty()
                open=true
            }.padding(top=2.dp,bottom=2.dp),
        fontSize=10.sp,
        color=if(amount==null) Color.Gray else Color(0xFF0A9566),
        textAlign=androidx.compose.ui.text.style.TextAlign.Center,
        maxLines=1)
    if(open && morning!=null && storeId!=null && partnerId!=null) {
        AlertDialog(
            onDismissRequest={open=false},
            title={Text("修改凌晨${when(channel) {"WECHAT"->"微信";"ALIPAY"->"支付宝";else->"现金"}}收款")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("${morning} 凌晨收款，归属 ${date} 营业；0表示已确认没有收款，留空表示未登记。",
                        style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value=input,onValueChange={input=it},
                        singleLine=true,
                        label={Text("凌晨金额（元）")},
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                    if(snapshot!=null && snapshot.storeId!=storeId)
                        Text("同一收款人在该凌晨已关联其他位置，不能重复计入。",
                            color=MaterialTheme.colorScheme.error)
                    if(morning.isAfter(LocalDate.now()))
                        Text("需在次日凌晨发生后才能记录。",color=MaterialTheme.colorScheme.error)
                    if(error.isNotBlank())
                        Text(error,color=MaterialTheme.colorScheme.error)
                }
            },
            confirmButton={
                TextButton(onClick={
                    val money=input.takeIf { it.isNotBlank() }?.toDoubleOrNull()
                    if(input.isNotBlank() && (money==null || !money.isFinite() ||
                            money<0.0 || money>100000000.0)) {
                        error="金额格式不正确";return@TextButton
                    }
                    if(morning.isAfter(LocalDate.now())) {
                        error="不能提前登记未来的凌晨收款";return@TextButton
                    }
                    val updated=(snapshot ?: OvernightReceiptSnapshot(
                        morning.toString(),storeId,partnerId,0.0,0.0,0.0,
                        wechatRecorded=false,alipayRecorded=false,cashRecorded=false))
                        .withAmount(channel,money)
                    if(store.saveSnapshot(updated)) {
                        open=false;onChanged()
                    } else error="写入失败，或该收款人凌晨已经关联其他位置"
                },enabled=!morning.isAfter(LocalDate.now()) &&
                    (snapshot==null || snapshot.storeId==storeId)) {Text("保存")}
            },
            dismissButton={TextButton(onClick={open=false}){Text("取消")}}
        )
    }
}

/**
 * The previous (calendar) day's selling session may extend past 23:59.
 * For the selected natural date D the "昨日凌晨" row is ONLY the snapshot
 * stamped D 00:00 onwards, never D-1 00:00 or a prior selling session.
 */
@Composable
internal fun OvernightYesterdayNumber(
    amount: Double?,
    deducted: Boolean,
    onToggle: () -> Unit
) {
    val show=amount!=null && amount>0.005
    Text(
        text=if(show) displayOvernight(amount) else "－",
        modifier=Modifier.fillMaxWidth()
            .clickable(enabled=show,onClick=onToggle)
            .padding(vertical=2.dp),
        color=when {
            !show -> Color.Gray
            deducted -> Color(0xFF098A50)
            else -> Color(0xFFD43D42)
        },
        textAlign=androidx.compose.ui.text.style.TextAlign.Center,
        fontSize=10.sp,
        fontWeight=if(show) FontWeight.SemiBold else FontWeight.Normal,
        maxLines=1
    )
}
