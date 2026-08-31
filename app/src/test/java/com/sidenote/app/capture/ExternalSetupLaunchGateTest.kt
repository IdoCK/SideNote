package com.sidenote.app.capture

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class ExternalSetupLaunchGateTest {
    @Test
    fun doubleBeginCoalescesSecondLaunchAndDisarmsOnce() {
        val disarms = AtomicInteger()
        val gate = gate(disarms = disarms)

        val first = gate.begin()
        val second = gate.begin()

        assertThat(first).isNotNull()
        assertThat(second).isNull()
        assertThat(disarms.get()).isEqualTo(1)
    }

    @Test
    fun concurrentBeginAcceptsExactlyOneLaunch() {
        val disarms = AtomicInteger()
        val gate = gate(disarms = disarms)
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val begin = CountDownLatch(1)

        try {
            val results = List(2) {
                executor.submit<Long?> {
                    ready.countDown()
                    check(begin.await(5, TimeUnit.SECONDS))
                    gate.begin()
                }
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            begin.countDown()

            assertThat(results.map { it.get(5, TimeUnit.SECONDS) }.filterNotNull())
                .hasSize(1)
            assertThat(disarms.get()).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun failedSecondLaunchTokenCannotRearmFirstLaunch() {
        val rearms = AtomicInteger()
        val gate = gate(rearms = rearms)
        val first = checkNotNull(gate.begin())

        assertThat(gate.onLaunchFailed(first + 1)).isFalse()
        assertThat(rearms.get()).isEqualTo(0)
        assertThat(gate.begin()).isNull()

        assertThat(gate.onResult(first)).isTrue()
        assertThat(rearms.get()).isEqualTo(1)
    }

    @Test
    fun staleAndDuplicateResultsCannotRearmAnotherOrCompletedLaunch() {
        val rearms = AtomicInteger()
        val gate = gate(rearms = rearms)
        val first = checkNotNull(gate.begin())
        assertThat(gate.onResult(first)).isTrue()
        val second = checkNotNull(gate.begin())

        assertThat(gate.onResult(first)).isFalse()
        assertThat(rearms.get()).isEqualTo(1)
        assertThat(gate.onResult(second)).isTrue()
        assertThat(gate.onResult(second)).isFalse()
        assertThat(rearms.get()).isEqualTo(2)
    }

    @Test
    fun matchingNormalResultRearmsExactlyOnce() {
        val rearms = AtomicInteger()
        val gate = gate(rearms = rearms)
        val token = checkNotNull(gate.begin())

        assertThat(gate.onResult(token)).isTrue()

        assertThat(rearms.get()).isEqualTo(1)
        assertThat(gate.begin()).isNotNull()
    }

    @Test
    fun matchingLaunchFailureRearmsExactlyOnce() {
        val rearms = AtomicInteger()
        val gate = gate(rearms = rearms)
        val token = checkNotNull(gate.begin())

        assertThat(gate.onLaunchFailed(token)).isTrue()
        assertThat(gate.onLaunchFailed(token)).isFalse()

        assertThat(rearms.get()).isEqualTo(1)
        assertThat(gate.begin()).isNotNull()
    }

    private fun gate(
        disarms: AtomicInteger = AtomicInteger(),
        rearms: AtomicInteger = AtomicInteger(),
    ): ExternalSetupLaunchGate = ExternalSetupLaunchGate(
        disarm = { disarms.incrementAndGet() },
        rearm = { rearms.incrementAndGet() },
    )
}
