package com.ghostlink.app.networking

import com.ghostlink.app.networking.SasVerificationGate.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules for the SAS confirmation gate that sits between "peer authenticated" and
 * "messages may flow". Sized to the mission's required cases: propagation,
 * missing values, mismatch, cancellation, rejection, timeout, concurrent
 * attempts and stale-session results.
 */
class SasVerificationGateTest {

    private val gate = SasVerificationGate()

    @Test
    fun `a newly authenticated peer must be confirmed before data may flow`() {
        val superseded = gate.begin("s1", "aabbccdd")
        assertNull(superseded)
        assertTrue(gate.isAwaitingConfirmation)
        assertEquals("aabbccdd", gate.pending?.sasHex)
    }

    @Test
    fun `confirmation resolves with the exact code that was displayed`() {
        gate.begin("s1", "deadbeef")
        val outcome = gate.resolve("s1", confirmed = true)
        assertTrue(outcome is Outcome.Resolved)
        outcome as Outcome.Resolved
        assertEquals("deadbeef", outcome.pending.sasHex)
        assertTrue(outcome.confirmed)
        assertFalse(gate.isAwaitingConfirmation)
    }

    @Test
    fun `rejection resolves as not confirmed`() {
        gate.begin("s1", "deadbeef")
        val outcome = gate.resolve("s1", confirmed = false) as Outcome.Resolved
        assertFalse(outcome.confirmed)
        assertFalse(gate.isAwaitingConfirmation)
    }

    @Test
    fun `a stale session id cannot confirm a newer attempt`() {
        gate.begin("old", "1111")
        gate.begin("new", "2222")
        assertTrue(gate.resolve("old", confirmed = true) is Outcome.Stale)
        assertTrue("the new attempt is still pending", gate.isAwaitingConfirmation)
        assertEquals("new", gate.pending?.sessionId)
    }

    @Test
    fun `a stale session id cannot reject a newer attempt`() {
        gate.begin("old", "1111")
        gate.begin("new", "2222")
        assertTrue(gate.resolve("old", confirmed = false) is Outcome.Stale)
        assertTrue(gate.isAwaitingConfirmation)
    }

    @Test
    fun `a newer attempt supersedes and reports the abandoned one`() {
        gate.begin("s1", "1111")
        val superseded = gate.begin("s2", "2222")
        assertEquals("s1", superseded?.sessionId)
        assertEquals("s2", gate.pending?.sessionId)
    }

    @Test
    fun `no pending attempt means confirmation is refused`() {
        // Nothing authenticated yet: a stray tap must not fabricate a session.
        assertTrue(gate.resolve("anything", confirmed = true) is Outcome.Stale)
        assertNull(gate.timeout("anything"))
        assertFalse(gate.isAwaitingConfirmation)
    }

    @Test
    fun `timeout clears only the matching attempt`() {
        gate.begin("s1", "1111")
        assertNull(gate.timeout("other"))
        assertTrue(gate.isAwaitingConfirmation)
        assertEquals("s1", gate.timeout("s1")?.sessionId)
        assertFalse(gate.isAwaitingConfirmation)
        // After a timeout the same id cannot be confirmed late.
        assertTrue(gate.resolve("s1", confirmed = true) is Outcome.Stale)
    }

    @Test
    fun `double confirmation cannot open two sessions`() {
        gate.begin("s1", "1111")
        assertTrue(gate.resolve("s1", confirmed = true) is Outcome.Resolved)
        assertTrue("second confirm is stale", gate.resolve("s1", confirmed = true) is Outcome.Stale)
    }

    @Test
    fun `clear abandons any pending attempt`() {
        gate.begin("s1", "1111")
        assertEquals("s1", gate.clear()?.sessionId)
        assertFalse(gate.isAwaitingConfirmation)
        assertTrue(gate.resolve("s1", confirmed = true) is Outcome.Stale)
    }

    @Test
    fun `sas codes for different attempts are distinct inputs`() {
        gate.begin("s1", "aaaa")
        gate.begin("s2", "bbbb")
        // The displayed code must follow the current attempt, never the previous one.
        assertEquals("bbbb", gate.pending?.sasHex)
    }
}
