package com.lockkeeper.app

import com.lockkeeper.app.security.CredentialStore
import com.lockkeeper.app.security.KeystoreCredentialStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CredentialStoreTest {

    private lateinit var store: CredentialStore
    private lateinit var prefs: InMemorySharedPreferences

    @Before
    fun setUp() {
        prefs = InMemorySharedPreferences()
        store = KeystoreCredentialStore(prefs)
    }

    @Test
    fun `PIN setup and verification succeeds for valid 4, 5, 6, 7, and 8 digit PINs`() {
        val testPins = listOf("1234", "12345", "123456", "1234567", "12345678")
        for (pin in testPins) {
            assertTrue("Setup failed for pin $pin", store.setPin(pin))
            assertTrue(store.hasPin())
            org.junit.Assert.assertEquals(pin.length, store.getPinLength())
            assertTrue("Verification failed for pin $pin", store.verifyPin(pin))
            assertFalse("Verification should fail for incorrect pin", store.verifyPin(pin.reversed()))
        }
    }

    @Test
    fun `invalid PIN rejected during setup`() {
        assertFalse("Too short", store.setPin("123"))
        assertFalse("Too long", store.setPin("123456789"))
        assertFalse("Non-numeric", store.setPin("abcd"))
        assertFalse("Contains symbol", store.setPin("12#4"))
    }

    @Test
    fun `admin password setup and verification works`() {
        assertTrue(store.setAdminPassword("SuperAdminPass!2026"))
        assertTrue(store.hasAdminPassword())
        assertTrue(store.verifyAdminPassword("SuperAdminPass!2026"))
        assertFalse(store.verifyAdminPassword("WrongPassword"))
    }

    @Test
    fun `PIN and Admin credentials are completely separate`() {
        store.setPin("5678")
        store.setAdminPassword("AdminSecret5678")

        // PIN should never verify admin password
        assertFalse(store.verifyPin("AdminSecret5678"))
        // Admin password should never verify PIN
        assertFalse(store.verifyAdminPassword("5678"))
    }

    @Test
    fun `no plaintext credentials stored in preferences`() {
        store.setPin("9876")
        store.setAdminPassword("TopSecretPass")

        val allValues = prefs.getAll().values.map { it.toString() }
        for (v in allValues) {
            assertFalse("Plaintext PIN found in storage", v.contains("9876"))
            assertFalse("Plaintext Admin Password found in storage", v.contains("TopSecretPass"))
        }
    }
}
