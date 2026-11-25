package com.tianxian.fruit.data

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MasterRecordIdentityTest {
    @Test fun namespacesAreDisjointAndExactlyRepresentable() {
        for (uuid in listOf(UUID(0, 0), UUID(-1L, -1L), UUID.randomUUID())) {
            val id = MasterRecordIdentity.androidId(uuid)
            assertTrue(id >= MasterRecordIdentity.ANDROID_MIN)
            assertTrue(id < MasterRecordIdentity.ANDROID_MAX_EXCLUSIVE)
            assertTrue(id < MasterRecordIdentity.SERVER_MIN)
            assertEquals(id, id.toDouble().toLong())
            assertEquals(id, MasterRecordIdentity.androidId(uuid))
        }
    }
    @Test fun pilotIsLimitedToTestLedgerNames() {
        assertTrue(MasterRecordIdentity.pilotEnabled("iOS_TEST_主资料"))
        assertFalse(MasterRecordIdentity.pilotEnabled("子琳"))
        assertFalse(MasterRecordIdentity.pilotEnabled("not_iOS_TEST_"))
        assertFalse(MasterRecordIdentity.pilotEnabled("iOS_TEST"))
    }
    @Test fun distinctLowBitsProduceDistinctIds() {
        assertNotEquals(MasterRecordIdentity.androidId(UUID(1, 123)),
            MasterRecordIdentity.androidId(UUID(1, 124)))
    }
}
