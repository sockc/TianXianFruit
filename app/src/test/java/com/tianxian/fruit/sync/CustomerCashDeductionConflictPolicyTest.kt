package com.tianxian.fruit.sync

import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerCashDeductionConflictPolicyTest {
    @Test fun legacyMissingAndCloudTrueAreNotAutoMerged() {
        val detail = CustomerCashDeductionConflictPolicy.describe(
            false, true, NaturalCashFlagPresence.LEGACY_ABSENT)
        assertTrue(detail.contains("本机不扣减"))
        assertTrue(detail.contains("云端扣减"))
        assertTrue(detail.contains("历史默认不扣减"))
        assertTrue(detail.contains("不覆盖"))
    }

    @Test fun explicitLocalCashChoiceIsDistinguished() {
        val detail = CustomerCashDeductionConflictPolicy.describe(
            false, true, NaturalCashFlagPresence.EXPLICIT)
        assertTrue(detail.contains("本机已经明确保存"))
        assertTrue(detail.contains("不覆盖"))
    }

    @Test fun absentRemoteFlagIsNotTreatedAsFalse() {
        val detail = CustomerCashDeductionConflictPolicy.describe(
            true, null, NaturalCashFlagPresence.EXPLICIT)
        assertTrue(detail.contains("云端字段缺失"))
        assertTrue(detail.contains("不覆盖"))
    }

    @Test fun unknownLocalHistoryCannotBeInvented() {
        val detail = CustomerCashDeductionConflictPolicy.describe(
            false, true, NaturalCashFlagPresence.UNKNOWN)
        assertTrue(detail.contains("来源尚未确定"))
    }
}
