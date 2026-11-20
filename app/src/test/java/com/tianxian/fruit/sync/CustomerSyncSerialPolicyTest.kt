package com.tianxian.fruit.sync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CustomerSyncSerialPolicyTest {
    @Test fun concurrentSyncTriggersOnOneBookAreSerialized() {
        val executor = Executors.newFixedThreadPool(2)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAttempting = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        try {
            val first = executor.submit {
                CustomerSyncSerialPolicy.forBook("same-ledger") {
                    firstEntered.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit {
                secondAttempting.countDown()
                CustomerSyncSerialPolicy.forBook("same-ledger") {
                    secondEntered.countDown()
                }
            }
            assertTrue(secondAttempting.await(5, TimeUnit.SECONDS))
            assertFalse("Second sync must wait for the first",
                secondEntered.await(150, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(0L, secondEntered.count)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun separateLedgersDoNotBlockOneAnother() {
        val executor = Executors.newFixedThreadPool(2)
        val aEntered = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        try {
            val first = executor.submit {
                CustomerSyncSerialPolicy.forBook("ledger-A") {
                    aEntered.countDown()
                    check(releaseA.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(aEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit {
                CustomerSyncSerialPolicy.forBook("ledger-B") { "other completed" }
            }
            assertEquals("other completed", second.get(3, TimeUnit.SECONDS))
            releaseA.countDown()
            first.get(5, TimeUnit.SECONDS)
        } finally {
            releaseA.countDown()
            executor.shutdownNow()
        }
    }
}
