package com.tianxian.fruit.sync

import org.junit.Assert.*
import org.junit.Test

class CustomerSyncPullPolicyTest {
    @Test fun noUploadNeverNeedsSecondFetch() {
        assertFalse(CustomerSyncPullPolicy.needsPostUploadPull(0))
    }

    @Test fun anySubmittedBatchRequiresReadAfterWrite() {
        assertTrue(CustomerSyncPullPolicy.needsPostUploadPull(1))
        assertTrue(CustomerSyncPullPolicy.needsPostUploadPull(400))
    }
}
