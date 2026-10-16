package com.janreins.vaultlock.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TotpDisplayTest {
    @Test
    fun `codes split into two readable halves`() {
        assertEquals("123 456", groupTotpCode("123456"))
        assertEquals("123 4567", groupTotpCode("1234567"))
        assertEquals("1234 5678", groupTotpCode("12345678"))
    }

    @Test
    fun `short values are left alone`() {
        assertEquals("", groupTotpCode(""))
        assertEquals("12345", groupTotpCode("12345"))
    }
}
