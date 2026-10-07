package com.onefera.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SellerValidationTest {
    private val valid = SellerApplication(
        storeName = "Vibe Threads",
        legalName = "Vineel Chalam",
        phone = "9876543210",
        email = "seller@example.com",
        addressLine = "12 MG Road, Indiranagar",
        city = "Bengaluru",
        state = "Karnataka",
        pincode = "560038",
        pan = "AAPFU0939F",
        gstin = "27AAPFU0939F1ZV",
        payoutMethod = PayoutMethod.Upi,
        upiId = "vibe.threads@okhdfc",
        acceptedTerms = true,
    )

    @Test
    fun `a complete application has no errors`() {
        assertEquals(emptyMap<String, String>(), SellerValidation.errors(valid))
    }

    @Test
    fun `gstin check digit catches typos`() {
        assertTrue(SellerValidation.gstinValid("27AAPFU0939F1ZV"))
        assertFalse(SellerValidation.gstinValid("29AAPFU0939F1ZV"))
        assertFalse(SellerValidation.gstinValid("29AAPFU0939F1Z"))
    }

    @Test
    fun `gstin is optional but must match the pan`() {
        assertEquals(emptyMap<String, String>(), SellerValidation.errors(valid.copy(gstin = "")))
        assertTrue("gstin" in SellerValidation.errors(valid.copy(pan = "ABCDE1234F")))
    }

    @Test
    fun `bank payouts need account and ifsc`() {
        val bank = valid.copy(payoutMethod = PayoutMethod.Bank, upiId = "")
        val errors = SellerValidation.errors(bank)
        assertTrue("accountNumber" in errors && "ifsc" in errors && "accountHolder" in errors)
        val ok = bank.copy(accountHolder = "Vineel Chalam", accountNumber = "123456789012", ifsc = "HDFC0001234")
        assertEquals(emptyMap<String, String>(), SellerValidation.errors(ok))
    }

    @Test
    fun `terms and contact details are required`() {
        val errors = SellerValidation.errors(valid.copy(acceptedTerms = false, phone = "12345", pincode = "012345"))
        assertTrue("acceptedTerms" in errors && "phone" in errors && "pincode" in errors)
    }
}
