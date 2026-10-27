package com.ghostlink.app.model

/**
 * Pure invitation-lifecycle rules, extracted from the ViewModel so they can be
 * unit-tested without Android.
 *
 * Policy
 *  - An invitation is *usable* until it expires or is consumed.
 *  - It is consumed **exactly once**, and only by a fully authenticated +
 *    human-verified session (`onAuthenticationSucceeded`).
 *  - A failed, cancelled, timed-out or rejected attempt must NOT consume it, so a
 *    transient network failure never destroys a still-valid invitation.
 *  - Trying to start an attempt with an expired/consumed invitation is refused
 *    before any socket work, so a replayed QR cannot open a second session.
 */
object InvitationLifecycle {

    /** Why an attempt may not start. */
    enum class StartBlocker(val message: String) {
        None(""),
        Missing("Generate an invitation first."),
        Expired("This invitation has expired. Generate a new one."),
        Consumed("This invitation has already been used. Generate a new one."),
    }

    /** Checks whether [invitation] may start a new connection attempt at [nowMs]. */
    fun canStart(invitation: Invitation?, nowMs: Long = System.currentTimeMillis()): StartBlocker =
        when {
            invitation == null -> StartBlocker.Missing
            invitation.consumedAt != null -> StartBlocker.Consumed
            nowMs >= invitation.expiresAt -> StartBlocker.Expired
            else -> StartBlocker.None
        }

    /**
     * Applies a *successful* (authenticated + verified) session to the invitation.
     * Idempotent: a second success for the same invitation keeps the first
     * timestamp, so the recorded consumption time cannot be rewritten.
     */
    fun onAuthenticationSucceeded(
        invitation: Invitation?,
        nowMs: Long = System.currentTimeMillis(),
    ): Invitation? = when {
        invitation == null -> null
        invitation.consumedAt != null -> invitation
        else -> invitation.copy(consumedAt = nowMs)
    }

    /**
     * Applies a failed attempt. Returns the invitation unchanged — this exists so
     * the "failure must not consume" rule has an explicit, tested implementation
     * rather than relying on the absence of a code path.
     */
    fun onAttemptFailed(invitation: Invitation?): Invitation? = invitation
}
