package com.ghostlink.app.ui.screens.pairing

import org.json.JSONObject

/**
 * Result of turning a scanned QR string into invitation data.
 *
 * GhostLink invitation QR payload (written by `invitationQrPayload` in
 * MyInvitationScreen.kt):
 *
 *     { "v": 1, "t": "<invitation token>", "e": <expiresAt epoch ms>, "svc": "FD9C" }
 */
internal sealed interface QrScanOutcome {
    class Success(
        val token: String,
        val expiresAt: Long,
        val serviceUuid: String?,
    ) : QrScanOutcome

    /** Not a GhostLink payload at all (someone scanned a random QR). */
    data class NotGhostLink(val raw: String) : QrScanOutcome

    /** Looks like GhostLink, but is malformed / an unsupported version / expired. */
    data class Invalid(val reason: String) : QrScanOutcome
}

/** The only QR payload version this build understands. */
internal const val SUPPORTED_QR_VERSION = 1

/**
 * Parses a scanned QR string into a [QrScanOutcome].
 *
 * Validation rules:
 *  - must be a JSON object with `v`, `t` and `e`
 *  - `v` must equal [SUPPORTED_QR_VERSION]
 *  - `t` must be non-blank and at most 128 characters
 *  - `e` must be a plausible epoch-millis expiry (1e12 ≤ e ≤ 9e12) in the future
 *
 * Expiry is checked against [nowMs] so the check is deterministic in tests and
 * also catches a clock-skewed phone scanning a stale invitation.
 */
internal fun parseInvitationQr(
    raw: String,
    nowMs: Long = System.currentTimeMillis(),
): QrScanOutcome {
    val text = raw.trim()
    if (!text.startsWith("{")) return QrScanOutcome.NotGhostLink(raw)

    val json = try {
        JSONObject(text)
    } catch (_: Exception) {
        return QrScanOutcome.NotGhostLink(raw)
    }

    if (!json.has("v") || !json.has("t") || !json.has("e")) {
        return QrScanOutcome.NotGhostLink(raw)
    }

    val version = json.optInt("v", -1)
    if (version != SUPPORTED_QR_VERSION) {
        return QrScanOutcome.Invalid("Unsupported QR version $version")
    }

    val token = json.optString("t", "").trim()
    if (token.isEmpty() || token.length > 128) {
        return QrScanOutcome.Invalid("QR code carries no valid invitation token")
    }

    val expiresAt = json.optLong("e", 0L)
    if (expiresAt < 1_000_000_000_000L || expiresAt > 9_000_000_000_000L) {
        return QrScanOutcome.Invalid("QR code carries an invalid expiry")
    }
    if (expiresAt <= nowMs) {
        return QrScanOutcome.Invalid("This invitation has expired")
    }

    val service = json.optString("svc", "").trim().ifEmpty { null }
    if (service != null && service.length > 36) {
        return QrScanOutcome.Invalid("QR code carries an invalid service ID")
    }

    return QrScanOutcome.Success(token = token, expiresAt = expiresAt, serviceUuid = service)
}
