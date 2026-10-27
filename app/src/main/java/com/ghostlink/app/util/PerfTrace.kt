package com.ghostlink.app.util

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal stage timer for the connection pipeline.
 *
 * Purpose: make the "no measurements exist" gap closable on a real device without
 * adding a profiler dependency. Every stage records monotonic timestamps only —
 * never message content, tokens, aliases or MAC addresses — and the whole thing
 * compiles to almost nothing when [enabled] is false.
 *
 * Usage:
 *   PerfTrace.mark(PerfTrace.STAGE_DISCOVERY_START)
 *   PerfTrace.mark(PerfTrace.STAGE_FIRST_PEER)
 *   PerfTrace.report()
 *
 * The output appears in logcat under the `GhostPerf` tag. This is sample-based
 * wall-clock timing, not Macrobenchmark: it measures the app's own stages, not
 * cold-start percentiles, which still require Macrobenchmark on a device.
 */
object PerfTrace {

    private const val TAG = "GhostPerf"

    // Stage names only. Keeping them constants prevents accidental data leakage
    // through the tracing channel.
    const val STAGE_ATTACH = "attach"
    const val STAGE_DISCOVERY_START = "discovery_start"
    const val STAGE_FIRST_PEER = "first_peer"
    const val STAGE_GROUP_REQUESTED = "group_requested"
    const val STAGE_GROUP_FORMED = "group_formed"
    const val STAGE_SOCKET_CONNECTED = "socket_connected"
    const val STAGE_AUTHENTICATED = "authenticated"
    const val STAGE_VERIFIED = "verified"
    const val STAGE_FAILED = "failed"

    @Volatile var enabled: Boolean = false

    private val marks = ConcurrentHashMap<String, Long>()
    private val runStart = SystemClock.elapsedRealtime()

    /** Records the first occurrence of [stage] in the current attempt. */
    fun mark(stage: String) {
        if (!enabled) return
        val t = SystemClock.elapsedRealtime()
        if (marks.putIfAbsent(stage, t) == null) {
            Log.d(TAG, "stage=$stage t=+${t - runStart}ms")
        }
    }

    /** Clears per-attempt marks (keeps the process start for cold-start deltas). */
    fun resetAttempt() {
        marks.clear()
    }

    /** Emits the elapsed deltas between consecutive stages, in pipeline order. */
    fun report() {
        if (!enabled || marks.isEmpty()) return
        val order = listOf(
            STAGE_ATTACH, STAGE_DISCOVERY_START, STAGE_FIRST_PEER, STAGE_GROUP_REQUESTED,
            STAGE_GROUP_FORMED, STAGE_SOCKET_CONNECTED, STAGE_AUTHENTICATED, STAGE_VERIFIED,
        )
        var prev = runStart
        val sb = StringBuilder("timeline:")
        order.forEach { stage ->
            val at = marks[stage] ?: return@forEach
            sb.append(" $stage=+${at - prev}ms")
            prev = at
        }
        Log.i(TAG, sb.toString())
    }

    /** Milliseconds from process start to [stage], or null when unrecorded. */
    fun sinceStart(stage: String): Long? = marks[stage]?.minus(runStart)
}
