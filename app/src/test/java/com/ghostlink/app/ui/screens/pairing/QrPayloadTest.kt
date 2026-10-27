package com.ghostlink.app.ui.screens.pairing

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the invitation QR payload — the exact contract shared between
 * the generator (MyInvitationScreen.invitationQrPayload) and the scanner
 * (QrScannerScreen -> parseInvitationQr). Pure JVM, no device needed.
 */
class QrPayloadTest {

    private val now = 1_790_000_000_000L
    private val token = "3f2b8c1e-9a4d-4e77-b0c2-5d6a1f8e9b3c"

    private fun payload(
        v: Int = 1,
        t: String = token,
        e: Long = now + 60_000,
        svc: String = "FD9C",
    ): String = JSONObject().apply {
        put("v", v)
        put("t", t)
        put("e", e)
        put("svc", svc)
    }.toString()

    @Test
    fun `valid payload parses to its token, expiry and service`() {
        val outcome = parseInvitationQr(payload(), nowMs = now)
        assertTrue(outcome is QrScanOutcome.Success)
        outcome as QrScanOutcome.Success
        assertEquals(token, outcome.token)
        assertEquals(now + 60_000, outcome.expiresAt)
        assertEquals("FD9C", outcome.serviceUuid)
    }

    @Test
    fun `service uuid is optional`() {
        val withoutSvc = JSONObject().apply {
            put("v", 1)
            put("t", token)
            put("e", now + 60_000)
        }.toString()
        val outcome = parseInvitationQr(withoutSvc, nowMs = now)
        assertTrue(outcome is QrScanOutcome.Success)
        assertEquals(null, (outcome as QrScanOutcome.Success).serviceUuid)
    }

    @Test
    fun `expired invitation is rejected`() {
        val outcome = parseInvitationQr(payload(e = now - 1), nowMs = now)
        assertTrue(outcome is QrScanOutcome.Invalid)
        assertEquals("This invitation has expired", (outcome as QrScanOutcome.Invalid).reason)
    }

    @Test
    fun `unsupported version is rejected`() {
        val outcome = parseInvitationQr(payload(v = 2), nowMs = now)
        assertTrue(outcome is QrScanOutcome.Invalid)
    }

    @Test
    fun `blank token is rejected`() {
        val outcome = parseInvitationQr(payload(t = "  "), nowMs = now)
        assertTrue(outcome is QrScanOutcome.Invalid)
    }

    @Test
    fun `implausible expiry is rejected`() {
        val outcome = parseInvitationQr(payload(e = 42L), nowMs = now)
        assertTrue(outcome is QrScanOutcome.Invalid)
    }

    @Test
    fun `plain text qr is not a ghostlink payload`() {
        assertTrue(parseInvitationQr("https://example.com", nowMs = now) is QrScanOutcome.NotGhostLink)
    }

    @Test
    fun `json without required keys is not a ghostlink payload`() {
        assertTrue(parseInvitationQr("{\"hello\":\"world\"}", nowMs = now) is QrScanOutcome.NotGhostLink)
    }

    @Test
    fun `malformed json is not a ghostlink payload`() {
        assertTrue(parseInvitationQr("{not json", nowMs = now) is QrScanOutcome.NotGhostLink)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val outcome = parseInvitationQr("\n  ${payload()}  \n", nowMs = now)
        assertTrue(outcome is QrScanOutcome.Success)
    }
}
