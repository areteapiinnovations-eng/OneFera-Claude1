package com.onefera.app.data.model

/** Where a user stands in seller onboarding. Only `Approved` users can switch to Seller mode. */
enum class SellerStatus { None, Pending, Approved, Rejected }

enum class SellerBusinessType(val label: String) {
    Individual("Individual / sole proprietor"),
    Partnership("Partnership / LLP"),
    Company("Private / public limited company"),
}

enum class PayoutMethod(val label: String) { Upi("UPI"), Bank("Bank account") }

/**
 * Seller registration, stored at `sellerApplications/{uid}` (private to the applicant; written
 * only by the `submitSellerApplication` Cloud Function after server-side validation).
 */
data class SellerApplication(
    val storeName: String = "",
    val legalName: String = "",
    val businessType: SellerBusinessType = SellerBusinessType.Individual,
    val category: ProductCategory = ProductCategory.Fashion,
    val description: String = "",
    val phone: String = "",
    val email: String = "",
    val addressLine: String = "",
    val city: String = "",
    val state: String = "",
    val pincode: String = "",
    /** Optional for small sellers under the GST threshold. */
    val gstin: String = "",
    val pan: String = "",
    val payoutMethod: PayoutMethod = PayoutMethod.Upi,
    val upiId: String = "",
    val accountHolder: String = "",
    val accountNumber: String = "",
    val ifsc: String = "",
    val acceptedTerms: Boolean = false,
    val status: SellerStatus = SellerStatus.None,
    val rejectionReason: String = "",
    val submittedAt: Long = 0L,
)

/**
 * Format checks for seller registration. The same rules run again in the Cloud Function, so the
 * form gives instant feedback but the server decides.
 */
object SellerValidation {
    private val GSTIN = Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$")
    private val PAN = Regex("^[A-Z]{5}[0-9]{4}[A-Z]$")
    private val IFSC = Regex("^[A-Z]{4}0[A-Z0-9]{6}$")
    private val UPI = Regex("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z][a-zA-Z0-9]{1,64}$")
    private val PHONE = Regex("^[6-9][0-9]{9}$")
    private val PINCODE = Regex("^[1-9][0-9]{5}$")
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val ACCOUNT = Regex("^[0-9]{9,18}$")
    private const val GST_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** GSTIN check digit (mod-36 weighted sum), so typos are caught before submitting. */
    fun gstinValid(value: String): Boolean {
        val g = value.uppercase()
        if (!GSTIN.matches(g)) return false
        var sum = 0
        for (i in 0 until 14) {
            val product = GST_CHARS.indexOf(g[i]) * (if (i % 2 == 0) 1 else 2)
            sum += product / 36 + product % 36
        }
        val check = (36 - sum % 36) % 36
        return GST_CHARS[check] == g[14]
    }

    fun panValid(value: String) = PAN.matches(value.uppercase())
    fun ifscValid(value: String) = IFSC.matches(value.uppercase())
    fun upiValid(value: String) = UPI.matches(value.trim())
    fun phoneValid(value: String) = PHONE.matches(value.trim())
    fun pincodeValid(value: String) = PINCODE.matches(value.trim())
    fun emailValid(value: String) = EMAIL.matches(value.trim())
    fun accountNumberValid(value: String) = ACCOUNT.matches(value.trim())

    /** Field name -> problem, empty when the application can be submitted. */
    fun errors(a: SellerApplication): Map<String, String> = buildMap {
        if (a.storeName.trim().length !in 3..40) put("storeName", "Store name must be 3-40 characters")
        if (a.legalName.trim().length !in 3..80) put("legalName", "Enter the name on your PAN or business registration")
        if (a.description.trim().length > 300) put("description", "Keep it under 300 characters")
        if (!phoneValid(a.phone)) put("phone", "Enter a 10-digit Indian mobile number")
        if (!emailValid(a.email)) put("email", "Enter a valid email")
        if (a.addressLine.trim().length !in 5..120) put("addressLine", "Enter your pickup address")
        if (a.city.trim().length !in 2..40) put("city", "Enter your city")
        if (a.state.trim().length !in 2..40) put("state", "Enter your state")
        if (!pincodeValid(a.pincode)) put("pincode", "Enter a 6-digit pincode")
        if (!panValid(a.pan)) put("pan", "Enter a valid PAN (e.g. ABCDE1234F)")
        if (a.gstin.isNotBlank()) {
            if (!gstinValid(a.gstin)) put("gstin", "That GSTIN doesn't look right")
            else if (a.gstin.uppercase().substring(2, 12) != a.pan.uppercase()) put("gstin", "GSTIN must contain your PAN")
        }
        when (a.payoutMethod) {
            PayoutMethod.Upi -> if (!upiValid(a.upiId)) put("upiId", "Enter a UPI ID like name@bank")
            PayoutMethod.Bank -> {
                if (a.accountHolder.trim().length !in 3..80) put("accountHolder", "Enter the account holder's name")
                if (!accountNumberValid(a.accountNumber)) put("accountNumber", "Account numbers are 9-18 digits")
                if (!ifscValid(a.ifsc)) put("ifsc", "Enter a valid IFSC (e.g. HDFC0001234)")
            }
        }
        if (!a.acceptedTerms) put("acceptedTerms", "Accept the seller terms to continue")
    }
}
