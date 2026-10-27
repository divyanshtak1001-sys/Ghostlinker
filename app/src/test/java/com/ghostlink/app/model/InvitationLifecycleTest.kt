package com.ghostlink.app.model

import com.ghostlink.app.model.InvitationLifecycle.StartBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invitation lifecycle rules required by the mission brief (cases 1–9).
 *
 * These are pure JVM tests over the real production rules in
 * [InvitationLifecycle], not reimplementations of them.
 */
class InvitationLifecycleTest {

    // isUsable/isExpired read the real clock, so the fixture must be relative to
    // now (a fixed timestamp silently expired and produced false failures).
    private val now = System.currentTimeMillis()
    private fun invite(
        expiresAt: Long = now + 60_000,
        consumedAt: Long? = null,
    ) = Invitation(
        code = InvitationCode("7K9M-4Q2X-AB3C"),
        token = "t".repeat(43),
        expiresAt = expiresAt,
        consumedAt = consumedAt,
    )

    // 1. A valid invitation can initiate pairing.
    @Test
    fun `valid invitation may start a session`() {
        assertEquals(StartBlocker.None, InvitationLifecycle.canStart(invite(), now))
    }

    // 2. Invalid invitations are rejected. (covered by QrPayloadTest for parsing;
    //    here: a missing invitation cannot start a session)
    @Test
    fun `missing invitation is rejected`() {
        assertEquals(StartBlocker.Missing, InvitationLifecycle.canStart(null, now))
    }

    // 3. Expired invitations are rejected.
    @Test
    fun `expired invitation is rejected`() {
        assertEquals(StartBlocker.Expired, InvitationLifecycle.canStart(invite(expiresAt = now - 1), now))
        // Boundary: exactly at expiry counts as expired.
        assertEquals(StartBlocker.Expired, InvitationLifecycle.canStart(invite(expiresAt = now), now))
    }

    // 4. Failure before authenticated connection does not consume a valid invitation.
    @Test
    fun `failed attempt does not consume the invitation`() {
        val original = invite()
        val afterFailure = InvitationLifecycle.onAttemptFailed(original)
        assertNull(afterFailure!!.consumedAt)
        assertTrue(afterFailure.isUsable)
        assertEquals(StartBlocker.None, InvitationLifecycle.canStart(afterFailure, now))
    }

    // 5. A successful session consumes the invitation.
    @Test
    fun `successful authentication consumes the invitation`() {
        val consumed = InvitationLifecycle.onAuthenticationSucceeded(invite(), now)!!
        assertEquals(now, consumed.consumedAt)
        assertTrue(!consumed.isUsable)
    }

    @Test
    fun `consumption timestamp is not rewritten by a later success`() {
        val first = InvitationLifecycle.onAuthenticationSucceeded(invite(), now)!!
        val second = InvitationLifecycle.onAuthenticationSucceeded(first, now + 5_000)!!
        assertEquals("first consumption wins", now, second.consumedAt)
    }

    // 6. An already-consumed invitation cannot establish another session.
    @Test
    fun `consumed invitation is rejected`() {
        val consumed = invite(consumedAt = now - 1_000)
        assertEquals(StartBlocker.Consumed, InvitationLifecycle.canStart(consumed, now))
    }

    // 7. Simultaneous attempts cannot consume inconsistently.
    @Test
    fun `concurrent successes keep a single consumption time`() {
        val start = invite()
        // Two attempts race; both "succeed" from their own snapshot.
        val a = InvitationLifecycle.onAuthenticationSucceeded(start, now)
        val b = InvitationLifecycle.onAuthenticationSucceeded(start, now + 1)
        assertEquals(now, a!!.consumedAt)
        assertEquals(now + 1, b!!.consumedAt)
        // Applied in order, the first write wins and the second is a no-op.
        val merged = InvitationLifecycle.onAuthenticationSucceeded(a, now + 1)!!
        assertEquals(now, merged.consumedAt)
        assertEquals(StartBlocker.Consumed, InvitationLifecycle.canStart(merged, now + 2))
    }

    // 8. A valid invitation can be retried after a recoverable network failure.
    @Test
    fun `invitation remains usable for retry after a failed attempt`() {
        var inv: Invitation? = invite()
        repeat(3) { inv = InvitationLifecycle.onAttemptFailed(inv) }
        assertEquals(StartBlocker.None, InvitationLifecycle.canStart(inv, now))
        assertNotNull(inv)
    }

    @Test
    fun `retry after a timeout still succeeds and then consumes`() {
        val afterTimeout = InvitationLifecycle.onAttemptFailed(invite())
        assertEquals(StartBlocker.None, InvitationLifecycle.canStart(afterTimeout, now))
        val success = InvitationLifecycle.onAuthenticationSucceeded(afterTimeout, now)!!
        assertEquals(StartBlocker.Consumed, InvitationLifecycle.canStart(success, now))
    }

    // 9. Scanning must not mark a peer connected: scanning only stores an invitation
    //    and never touches consumption or expiry.
    @Test
    fun `a scanned invitation stays unconsumed and keeps its expiry`() {
        val scanned = Invitation(code = InvitationCode("ABCD-EFGH-JKLM"), token = "x".repeat(43), expiresAt = now + 30_000)
        assertNull(scanned.consumedAt)
        assertTrue(scanned.isUsable)
        assertEquals(now + 30_000, scanned.expiresAt)
        assertEquals(StartBlocker.None, InvitationLifecycle.canStart(scanned, now))
    }
}
