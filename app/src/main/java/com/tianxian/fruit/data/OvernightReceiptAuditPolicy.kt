package com.tianxian.fruit.data

import java.time.LocalDate
import kotlin.math.abs

/** Read-only audit. Never write sales, profits or settlement records here. */
internal data class OvernightCollectorSale(
    val storeId: Long,
    val partnerId: Long,
    val wechat: Double,
    val alipay: Double,
    val cash: Double
)

internal enum class OvernightReviewReason {
    MORNING_CHANGED, SAVED_TOTAL_DIFFERS, DELETED_OWNER, SHARED_COLLECTOR, DUPLICATE_MORNING
}

internal data class OvernightReviewIssue(
    val businessDate: String,
    val partnerId: Long,
    val reason: OvernightReviewReason,
    val relevantStoreIds: Set<Long>,
    val detail: String
)

/**
 * Compare the authoritative saved sale against its original natural-day source
 * and current collector-level mornings. Cross-store duplicates cannot always
 * be proven from net receipts alone, particularly after offline saves; flag
 * them for review rather than changing a ledger value speculatively.
 */
internal object OvernightReceiptAuditPolicy {
    private fun moneyPositive(s: OvernightReceiptSnapshot?) =
        s != null && listOf(s.amount("WECHAT"), s.amount("ALIPAY"),
            s.amount("CASH")).any { (it ?: 0.0) > 0.005 }

    fun inspect(
        date: String,
        sales: List<OvernightCollectorSale>,
        mornings: List<OvernightReceiptSnapshot>,
        drafts: List<NaturalDayReceiptDraft>,
        pendingCollectors: Set<Long>
    ): List<OvernightReviewIssue> {
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return emptyList()
        val today = sales.filter { it.storeId > 0 && it.partnerId > 0 }
        val result = mutableListOf<OvernightReviewIssue>()
        for ((partner, entries) in today.groupBy { it.partnerId }) {
            val activeStores = entries.map { it.storeId }.toSet()
            val previous = mornings.firstOrNull {
                it.calendarDate == date && it.partnerId == partner
            }
            val following = mornings.firstOrNull {
                it.calendarDate == day.plusDays(1).toString() && it.partnerId == partner
            }
            val hasMorning = moneyPositive(previous) || moneyPositive(following)
            val source = drafts.firstOrNull {
                it.businessDate == date && it.partnerId == partner
            }
            val cleared = source != null && OvernightReceiptDeletionPolicy.isClearedNatural(source)
            val activeSource = source?.takeIf {
                !cleared && it.storeId in activeStores
            }
            fun flag(reason: OvernightReviewReason, stores: Set<Long>, detail: String) {
                result.add(OvernightReviewIssue(date, partner, reason, stores, detail))
            }
            val duplicate = mornings.filter {
                it.partnerId == partner && it.calendarDate in
                    setOf(date, day.plusDays(1).toString())
            }.groupingBy { it.calendarDate }.eachCount().values.any { it > 1 }
            if (duplicate) {
                flag(OvernightReviewReason.DUPLICATE_MORNING, activeStores,
                    "同一收款人、同一凌晨日期存在重复记录，请核对合并，暂停自动调整")
            }
            if (partner in pendingCollectors) {
                flag(OvernightReviewReason.MORNING_CHANGED, activeStores,
                    "凌晨收款曾补录或修改，已保存营业额需要人工核对")
            }
            if (source != null && (cleared || source.storeId !in activeStores) &&
                hasMorning) {
                flag(OvernightReviewReason.DELETED_OWNER, activeStores,
                    "原凌晨扣减位置已删除或不再营业，剩余位置需人工确认扣减归属")
            }
            if (activeSource != null) {
                val current = entries.filter { it.storeId == activeSource.storeId }
                val expected = OvernightReceiptPolicy.adjustment(activeSource, mornings)
                val wx = current.sumOf { it.wechat }
                val ali = current.sumOf { it.alipay }
                val cash = current.sumOf { it.cash }
                if (expected == null ||
                    abs(wx - expected.adjustedWechat) > 0.02 ||
                    abs(ali - expected.adjustedAlipay) > 0.02 ||
                    abs(cash - expected.adjustedCash) > 0.02) {
                    flag(OvernightReviewReason.SAVED_TOTAL_DIFFERS,
                        setOf(activeSource.storeId),
                        "凌晨调整后的预期收款与已保存营业额不一致，请进入原位置核对")
                }
            }
            if (activeStores.size > 1 && hasMorning) {
                flag(OvernightReviewReason.SHARED_COLLECTOR, activeStores,
                    "同一收款人当天涉及多个位置；离线同步可能重复扣减，请核对各位置实际收款")
            }
        }
        return result.distinctBy { it.partnerId to it.reason }
    }
}
