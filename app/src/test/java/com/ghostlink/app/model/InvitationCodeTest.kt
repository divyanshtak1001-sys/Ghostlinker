package com.ghostlink.app.model

import org.junit.Assert.*
import org.junit.Test

class InvitationCodeTest {

    @Test
    fun `validates 8 to 12 alphanumeric codes with and without dashes`() {
        assertTrue(InvitationCode.isValidFormat("7K9M-4Q2X"))
        assertTrue(InvitationCode.isValidFormat("7K9M4Q2X"))
        assertTrue(InvitationCode.isValidFormat("7K9M-4Q2X-AB3C"))
        assertTrue(InvitationCode.isValidFormat("abcd-1234"))
    }

    @Test
    fun `rejects invalid lengths and special characters`() {
        assertFalse(InvitationCode.isValidFormat("SHORT"))
        assertFalse(InvitationCode.isValidFormat("TOOLONGTOBEVALIDCODE12345"))
        assertFalse(InvitationCode.isValidFormat("7K9M@4Q2X"))
        assertFalse(InvitationCode.isValidFormat("7K9M 4Q2X"))
        assertFalse(InvitationCode.isValidFormat(""))
    }

    @Test
    fun `normalises display to uppercase trimmed string`() {
        val code = InvitationCode("  7k9m-4q2x  ")
        assertEquals("7K9M-4Q2X", code.display)
    }
}
