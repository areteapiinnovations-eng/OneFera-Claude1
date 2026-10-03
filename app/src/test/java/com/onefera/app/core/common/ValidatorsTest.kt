package com.onefera.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ValidatorsTest {

    @Test
    fun `accepts well formed emails and rejects junk`() {
        assertNull(Validators.email("future@onefera.app"))
        assertNull(Validators.email("  a.b+c@mail.co.in "))
        assertNotNull(Validators.email(""))
        assertNotNull(Validators.email("not-an-email"))
        assertNotNull(Validators.email("a@b"))
    }

    @Test
    fun `password needs length plus letters and digits`() {
        assertNotNull(Validators.password("short1"))
        assertNotNull(Validators.password("onlyletters"))
        assertNotNull(Validators.password("12345678"))
        assertNull(Validators.password("onefera123"))
    }

    @Test
    fun `usernames follow handle rules`() {
        assertNull(Validators.username("future.you"))
        assertNull(Validators.username("gen_alpha_01"))
        assertNotNull(Validators.username("ab"))
        assertNotNull(Validators.username(".dotfirst"))
        assertNotNull(Validators.username("dotlast."))
        assertNotNull(Validators.username("two..dots"))
        assertNotNull(Validators.username("UPPER"))
    }

    @Test
    fun `normalizes typed handles`() {
        assertEquals("future.you", Validators.normalizeUsername(" @Future.You! "))
    }

    @Test
    fun `enforces minimum age of 13`() {
        val today = LocalDate.of(2026, 10, 3)
        assertNull(Validators.birthDate(LocalDate.of(2013, 10, 3), today))
        assertNotNull(Validators.birthDate(LocalDate.of(2013, 10, 4), today))
        assertNotNull(Validators.birthDate(LocalDate.of(2027, 1, 1), today))
        assertNotNull(Validators.birthDate(null, today))
        assertEquals(17, Validators.ageOn(LocalDate.of(2009, 1, 1), today))
    }

    @Test
    fun `rates password strength`() {
        assertEquals(PasswordStrength.None, Validators.passwordStrength(""))
        assertEquals(PasswordStrength.Weak, Validators.passwordStrength("abc"))
        assertEquals(PasswordStrength.Strong, Validators.passwordStrength("Future-Era-2026!"))
    }
}
