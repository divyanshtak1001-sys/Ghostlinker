package com.ghostlink.app.networking

/**
 * Tracks the single connection attempt that is awaiting the user's Short
 * Authentication String (SAS) confirmation.
 *
 * This is the fail-closed gate that sits between "the peer proved possession of
 * the invitation" and "messages may flow". It is deliberately free of Android
 * types so the rules can be unit-tested:
 *
 *  - only the **current** attempt can be confirmed or rejected;
 *  - a confirm/reject for an attempt that already ended (timeout, rejection,
 *    disconnect, a newer attempt) is refused, so a late tap cannot open an old
 *    session or close a new one;
 *  - starting a new attempt implicitly abandons the previous one.
 */
internal class SasVerificationGate {

    /** A pending attempt. [sasHex] is exactly what both devices must display. */
    data class Pending(val sessionId: String, val sasHex: String)

    /** Outcomes an attempt can reach. */
    sealed interface Outcome {
        /** Confirmed (or rejected) the *current* attempt. */
        data class Resolved(val pending: Pending, val confirmed: Boolean) : Outcome

        /** The id did not match the pending attempt: nothing happened. */
        data class Stale(val sessionId: String) : Outcome
    }

    var pending: Pending? = null
        private set

    /** True when a confirmation is required before data may flow. */
    val isAwaitingConfirmation: Boolean get() = pending != null

    /**
     * Registers a newly authenticated attempt and abandons any previous one.
     * @return the attempt that was superseded, if any (the caller must close it).
     */
    fun begin(sessionId: String, sasHex: String): Pending? {
        val superseded = pending
        pending = Pending(sessionId, sasHex)
        return superseded?.takeIf { it.sessionId != sessionId }
    }

    /** Handles the user's decision for [sessionId]. */
    fun resolve(sessionId: String, confirmed: Boolean): Outcome {
        val current = pending ?: return Outcome.Stale(sessionId)
        if (current.sessionId != sessionId) return Outcome.Stale(sessionId)
        pending = null
        return Outcome.Resolved(current, confirmed)
    }

    /** The attempt timed out; returns it only if it is still the pending one. */
    fun timeout(sessionId: String): Pending? {
        val current = pending ?: return null
        if (current.sessionId != sessionId) return null
        pending = null
        return current
    }

    /** Disconnect / cancel / transport close. */
    fun clear(): Pending? = pending.also { pending = null }
}
