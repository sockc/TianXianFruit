package com.tianxian.fruit.ui

import org.junit.Assert.*
import org.junit.Test

class BusinessWarningDisplayPolicyTest {
    private val overdraw = BusinessWarningDisplayPolicy.OVERNIGHT_OVERDRAW

    @Test fun noPrematureOverdrawWarningOnUnsavedEmptyForm() {
        assertNull(BusinessWarningDisplayPolicy.visibleError(
            overdraw, editingExistingRecord = false, hasEnteredReceiptAmount = false))
    }

    @Test fun overdrawWarningStillAppearsWhenEnteringActualReceipts() {
        assertEquals(overdraw, BusinessWarningDisplayPolicy.visibleError(
            overdraw, editingExistingRecord = false, hasEnteredReceiptAmount = true))
    }

    @Test fun editingSavedBusinessStillShowsValidationWarning() {
        assertEquals(overdraw, BusinessWarningDisplayPolicy.visibleError(
            overdraw, editingExistingRecord = true, hasEnteredReceiptAmount = false))
    }

    @Test fun unrelatedProblemsAreNeverHidden() {
        val conflict = "同一微信收款人当天涉及多个位置，请先拆分各位置自然日收款金额"
        assertEquals(conflict, BusinessWarningDisplayPolicy.visibleError(
            conflict, editingExistingRecord = false, hasEnteredReceiptAmount = false))
        assertNull(BusinessWarningDisplayPolicy.visibleError(
            null, editingExistingRecord = false, hasEnteredReceiptAmount = false))
    }
}
