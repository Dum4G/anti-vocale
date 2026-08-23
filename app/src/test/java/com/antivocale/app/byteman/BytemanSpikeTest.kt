package com.antivocale.app.byteman

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * TASK-387 spike: proves the freeze+volatile-flag+spin pattern (planner guide)
 * works with coroutines and Gradle in this project. Runs ONLY under -Pbyteman.
 *
 * Trap 2 of the guide is built in: every test spins on writerFrozen WITH A
 * BAIL-OUT, so a rule that never fired fails loudly ("rule not armed") instead
 * of producing a false green.
 */
class BytemanSpikeTest {

    @Before
    fun requireAgent() {
        // JUnit4 equivalent of @EnabledIfSystemProperty: skipped without -Pbyteman.
        assumeTrue(System.getProperty("byteman.agent") == "true")
    }

    /** Spin until the flag is set, bailing out so a dead rule fails the test. */
    private fun awaitFrozen(standIn: SpikeStandIn, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!standIn.writerFrozen) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(10)
        }
        return true
    }

    /** The writer sequence every test uses: partial, freeze point, complete. */
    private fun CoroutineScope.launchWriter(standIn: SpikeStandIn) =
        async(Dispatchers.IO) {
            standIn.publish()
            standIn.windowPoint()
            standIn.complete()
        }

    @Test
    fun `freeze lands inside the window and reader observes the partial state`() = runBlocking {
        val standIn = SpikeStandIn()
        standIn.raceMode = true

        val writer = launchWriter(standIn) // rule freezes at windowPoint (between partial and complete)

        // Health assertion first: the rule MUST have fired (trap 2).
        assertTrue("rule not armed: writerFrozen never set (Byteman rule did not fire)", awaitFrozen(standIn))

        // Writer is frozen INSIDE the window: state is the partial one by construction.
        assertEquals("partial", standIn.state)
        assertFalse("writer should still be frozen (inside waitFor)", writer.isCompleted)
    }

    @Test
    fun `waitFor timeout frees the writer so the test completes without a signal`() {
        val standIn = SpikeStandIn()
        standIn.raceMode = true

        // Trap 1 (BYTEMAN-38): no signalWake; the 5s waitFor timeout must release
        // the writer on its own (guide pattern). withTimeout guards the outer test.
        runBlocking {
            withTimeout(20_000) {
                val writer = launchWriter(standIn)
                assertTrue(awaitFrozen(standIn))
                writer.await() // completes only after the waitFor timeout (<=5s)
                assertEquals("complete", standIn.state)
            }
        }
    }

    @Test
    fun `cancelling the reader scope does not hang - writer still finishes via timeout`() {
        val standIn = SpikeStandIn()
        standIn.raceMode = true
        val scope = CoroutineScope(Dispatchers.IO)
        val writer = scope.launchWriter(standIn)

        assertTrue(awaitFrozen(standIn))
        // structured cancellation must not require a signal:
        writer.cancel()

        val deadline = System.currentTimeMillis() + 20_000
        while (!writer.isCompleted) {
            check(System.currentTimeMillis() < deadline) { "writer did not complete after waitFor timeout" }
            Thread.sleep(20)
        }
        assertEquals("complete", standIn.state)
    }

    @Test
    fun `twin fix-validation shape - raceMode off means no freeze no window`() = runBlocking {
        val standIn = SpikeStandIn()
        standIn.raceMode = false

        val writer = launchWriter(standIn)
        writer.await()

        // The rule's IF guard kept it inert: no freeze ever observed.
        assertFalse(standIn.writerFrozen)
        assertEquals("complete", standIn.state)
    }
}
