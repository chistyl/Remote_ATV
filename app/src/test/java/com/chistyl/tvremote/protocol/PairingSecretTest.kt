package com.chistyl.tvremote.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSecretTest {
    @Test fun acceptsOnlySixHexadecimalCharacters() {
        assertTrue(PairingSecret.validateCode("A1B2C3"))
        assertTrue(PairingSecret.validateCode("123abc"))
        assertFalse(PairingSecret.validateCode("12345"))
        assertFalse(PairingSecret.validateCode("12345G"))
        assertFalse(PairingSecret.validateCode("12 456"))
    }
}
