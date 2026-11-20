package com.tianxian.fruit.ui

import org.junit.Assert.*
import org.junit.Test

class CustomerTrafficValueLabelTest {
    @Test fun largeCurrencyStaysInFullYuanRatherThanK() {
        assertEquals("1,400", trafficValueLabel(CustomerTrafficMetric.AMOUNT, 1400.0))
        assertEquals("12,345", trafficValueLabel(CustomerTrafficMetric.AMOUNT, 12345.0))
        assertEquals("999", trafficValueLabel(CustomerTrafficMetric.AMOUNT, 999.0))
    }

    @Test fun countsAreNotCurrency() {
        assertEquals("270", trafficValueLabel(CustomerTrafficMetric.PAYMENTS, 270.0))
        assertEquals("170", trafficValueLabel(CustomerTrafficMetric.CUSTOMERS, 170.0))
    }

    @Test fun invalidMoneyDoesNotAppearAsValidCollection() {
        assertEquals("—", trafficValueLabel(CustomerTrafficMetric.AMOUNT, Double.NaN))
    }
}
