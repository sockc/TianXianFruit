package com.tianxian.fruit.data

import com.tianxian.fruit.sync.BookPermissions
import com.tianxian.fruit.sync.LedgerBook
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class StabilityPoliciesTest {
    @Test fun todayDoesNotIncludeTomorrowRain() {
        val window = BusinessWeatherWindow.forDay(LocalDate.parse("2026-10-01"), "16:00", "24:00")
        val rain = listOf("2026-10-01T16:00:00+08:00" to 0.0,
            "2026-10-02T16:00:00+08:00" to 20.0, "2026-10-03T16:00:00+08:00" to 10.0)
        assertEquals(0.0, rain.filter { window.duringBusiness(it.first) }.sumOf { it.second }, 0.0)
        assertFalse(window.duringBusiness("2026-10-02T00:00:00+08:00"))
    }

    @Test fun windowPreservesMinutesOvernightAndTimezone() {
        val window = BusinessWeatherWindow.forDay(LocalDate.parse("2026-10-01"), "23:30", "02:15")
        assertFalse(window.duringBusiness("2026-10-01T23:00:00+08:00"))
        assertTrue(window.duringBusiness("2026-10-02T01:00:00+08:00"))
        assertTrue(window.duringBusiness("2026-10-01T16:00:00Z"))
        assertFalse(window.duringBusiness("2026-10-02T02:15:00+08:00"))
        assertFalse(window.duringBusiness("malformed"))
        assertTrue(window.beforeBusiness("2026-10-01T21:00:00+08:00"))
    }

    @Test fun preOpenWindowCanCrossIntoPreviousDay() {
        val window = BusinessWeatherWindow.forDay(LocalDate.parse("2026-10-01"), "01:00", "05:00")
        assertTrue(window.beforeBusiness("2026-09-30T23:00:00+08:00"))
        assertFalse(window.beforeBusiness("2026-09-30T21:59:00+08:00"))
    }

    @Test fun unknownCostDoesNotDiluteKnownBatchIntoCompleteCost() {
        val tracker = InventoryCostTracker()
        tracker.purchase(1.0, 0.0, false)
        tracker.purchase(1.0, 100.0, true)
        tracker.snapshot(1.0)
        assertFalse(tracker.complete)
        tracker.snapshot(0.0)
        tracker.purchase(2.0, 200.0, true)
        assertTrue(tracker.complete)
        assertEquals(100.0, tracker.unitCost!!, 0.000001)
    }

    @Test fun mixedKnownAndUnknownPurchasesOnSameDayStayIncomplete() {
        val tracker = InventoryCostTracker()
        tracker.purchase(2.0, 100.0, false)
        tracker.snapshot(0.5)
        assertFalse(tracker.complete)
    }

    @Test fun stockCostCarriesAcrossDays() {
        val tracker = InventoryCostTracker()
        tracker.purchase(2.0, 200.0, true)
        tracker.snapshot(1.0)
        tracker.purchase(1.0, 200.0, true)
        tracker.snapshot(0.5)
        assertTrue(tracker.complete)
        assertEquals(150.0, tracker.unitCost!!, 0.000001)
    }

    @Test fun sourceAndChildrenCountAsOneDeletion() {
        val keys = listOf(
            SyncDeletePolicy.businessKey("purchase_order", "order-A"),
            SyncDeletePolicy.businessKey("purchase_item", "item-1", "order-A"),
            SyncDeletePolicy.businessKey("purchase_item", "item-2", "order-A"),
            SyncDeletePolicy.businessKey("purchase_activity", "activity-A", "order-A"),
            SyncDeletePolicy.businessKey("profit_distribution", "profit-A")
        )
        assertFalse(SyncDeletePolicy.requiresConfirmation(keys))
        assertTrue(SyncDeletePolicy.requiresConfirmation(keys +
            SyncDeletePolicy.businessKey("purchase_order", "order-B")))
    }

    @Test fun snapshotWithoutPurchaseCostRemainsUnknown() {
        val tracker = InventoryCostTracker()
        tracker.snapshot(2.0)
        assertFalse(tracker.complete)
        assertNull(tracker.unitCost)
    }

    @Test fun independentInventoryDeletionsRequireConfirmation() {
        assertTrue(SyncDeletePolicy.requiresConfirmation(listOf(
            SyncDeletePolicy.businessKey("inventory_snapshot", "apple"),
            SyncDeletePolicy.businessKey("inventory_snapshot", "grape")
        )))
    }

    @Test fun refreshPreservesDirtyFieldsAndUpdatesCleanFields() {
        val inputs = mutableMapOf("apple" to "0.5", "grape" to "1")
        val baseline = mutableMapOf("apple" to "0", "grape" to "1")
        DraftRefreshPolicy.merge(inputs, baseline, mapOf("apple" to "0.2", "grape" to "2", "pear" to "0"))
        assertEquals("0.5", inputs["apple"])
        assertEquals("2", inputs["grape"])
        assertEquals("0", inputs["pear"])
        DraftRefreshPolicy.merge(inputs, baseline, mapOf("apple" to "0.2", "grape" to "2", "pear" to "0"))
        assertEquals("0.5", inputs["apple"])
    }

    @Test fun removedDirtyFieldsAndInvalidInputArePreserved() {
        val inputs = mutableMapOf("apple" to "0.5", "grape" to "1", "pear" to ".")
        val baseline = mutableMapOf("apple" to "0", "grape" to "1", "pear" to "0")
        DraftRefreshPolicy.merge(inputs, baseline, emptyMap())
        assertEquals(mapOf("apple" to "0.5", "pear" to "."), inputs)
        assertTrue(DraftRefreshPolicy.sameQuantity("0", "0.0"))
    }

    @Test fun scopedPageStillDisplaysNetworkFailure() {
        assertEquals("网络失败", SyncStatusPolicy.error("网络失败", ""))
        assertEquals("库存表不兼容", SyncStatusPolicy.error("网络失败", "库存表不兼容"))
    }

    @Test fun highIndexWithoutHistoryDoesNotBecomeAConfidentRecommendation() {
        assertEquals(
            "资料不足",
            BusinessAdvicePolicy.recommendation(
                score = 92,
                validHistoryCount = 0,
                similarCount = 0,
                weatherStatus = "COMPLETE"
            )
        )
        assertEquals(
            "INSUFFICIENT",
            BusinessAdvicePolicy.confidence(
                validHistoryCount = 2,
                similarCount = 2,
                weatherStatus = "COMPLETE"
            )
        )
        assertEquals(
            "建议正常营业",
            BusinessAdvicePolicy.recommendation(
                score = 82,
                validHistoryCount = 12,
                similarCount = 6,
                weatherStatus = "COMPLETE"
            )
        )
    }

    @Test fun hourlyWeatherCoverageControlsEvidenceStatus() {
        assertEquals("COMPLETE", BusinessAdvicePolicy.weatherEvidenceStatus(0.90, false))
        assertEquals("PARTIAL", BusinessAdvicePolicy.weatherEvidenceStatus(0.60, false))
        assertEquals("SPARSE", BusinessAdvicePolicy.weatherEvidenceStatus(0.25, false))
        assertEquals("MISSING", BusinessAdvicePolicy.weatherEvidenceStatus(0.0, false))
        assertEquals("STALE", BusinessAdvicePolicy.weatherEvidenceStatus(1.0, true))
    }

    @Test fun abnormalOperatingDaysDoNotEnterNormalHistoryBaseline() {
        assertFalse(BusinessAdvicePolicy.historyUsable(0.45, ""))
        assertFalse(BusinessAdvicePolicy.historyUsable(1.0, "缺货"))
        assertFalse(BusinessAdvicePolicy.historyUsable(1.0, "临时换位"))
        assertTrue(BusinessAdvicePolicy.historyUsable(0.95, "工厂放假"))
        assertTrue(BusinessAdvicePolicy.historyUsable(null, "发薪日"))
    }

    @Test fun businessAdviceSnapshotsHavePreOpenLiveAndFinalStages() {
        val date = LocalDate.parse("2026-10-01")
        val window = BusinessWeatherWindow.forDay(date, "16:00", "24:00")
        assertEquals(
            "PRE_OPEN",
            BusinessAdvicePolicy.snapshotStage(
                date,
                LocalDateTime.parse("2026-10-01T15:30:00"),
                window
            )
        )
        assertEquals(
            "LIVE",
            BusinessAdvicePolicy.snapshotStage(
                date,
                LocalDateTime.parse("2026-10-01T20:00:00"),
                window
            )
        )
        assertEquals(
            "FINAL",
            BusinessAdvicePolicy.snapshotStage(
                date,
                LocalDateTime.parse("2026-10-02T00:30:00"),
                window
            )
        )
    }
    @Test fun forecastAndBusinessAdviceStayLocalOnly() {
        assertTrue(SyncTablePolicy.isLocalOnly("weather_snapshot"))
        assertTrue(SyncTablePolicy.isLocalOnly("daily_business_score"))
        assertFalse(SyncTablePolicy.isLocalOnly("business_weather_history"))
    }

    @Test fun settlementCenterMirrorsCounterpartyOutstandingBalances() {
        assertEquals(0.0, FundBalancePolicy.centerBalance(emptyList()), 0.000001)
        assertEquals(
            2264.68,
            FundBalancePolicy.centerBalance(listOf(-1936.98, -327.70)),
            0.000001
        )
        assertEquals(
            -100.0,
            FundBalancePolicy.centerBalance(listOf(250.0, -150.0)),
            0.000001
        )
    }

    @Test fun explicitEmptyEditorPermissionsDenyViewAndWrite() {
        val book = LedgerBook("book", "test", "test.db", "", permission = "EDITOR",
            permissionTemplate = "CUSTOM", permissions = emptySet(), createdAt = 0L, updatedAt = 0L)
        assertFalse(BookPermissions.has(book, "USER", BookPermissions.PURCHASE_VIEW))
        assertFalse(BookPermissions.canModifyAnything(book, "USER"))
        assertTrue(BookPermissions.has(book.copy(permission = "OWNER"), "USER", BookPermissions.PURCHASE_VIEW))
        assertTrue(BookPermissions.has(book, "SUPERADMIN", BookPermissions.PURCHASE_VIEW))
        assertTrue(BookPermissions.has(book.copy(permissions = BookPermissions.fallbackForRole("EDITOR")),
            "USER", BookPermissions.PURCHASE_VIEW))
    }
}
