package com.tianxian.fruit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BusinessManualCustomerTotalsTest {
    @Test fun anonymousBillWithoutManualInputsIsNotZeroOrAFalseCustomerCount() {
        assertNull(BusinessManualCustomerTotals.total("", ""))
        assertNull(BusinessManualCustomerTotals.total("10", ""))
        assertNull(BusinessManualCustomerTotals.total("", "8"))
    }

    @Test fun explicitManualCountsAreSummedWithoutGuessingFromPaymentRows() {
        assertEquals(10, BusinessManualCustomerTotals.total("3", "7"))
        assertEquals(0, BusinessManualCustomerTotals.total("0", "0"))
        assertEquals(7, BusinessManualCustomerTotals.total("0", "7"))
    }

    @Test fun invalidAndOverflowedCountsCannotProduceFakeTotals() {
        assertNull(BusinessManualCustomerTotals.total("nan", "1"))
        assertNull(BusinessManualCustomerTotals.total("-1", "6"))
        assertNull(BusinessManualCustomerTotals.total("2147483647", "1"))
    }
}
