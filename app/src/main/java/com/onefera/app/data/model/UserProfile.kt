package com.onefera.app.data.model

import kotlinx.serialization.Serializable

enum class AccountMode { Personal, Seller }

/** A OneFera member's public profile, stored at `users/{uid}`. */
@Serializable
data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val username: String = "",
    val email: String = "",
    val bio: String = "",
    val avatarUrl: String? = null,
    val vibe: String = "",
    val city: String = "",
    /** ISO-8601 date (yyyy-MM-dd). Never shown publicly. */
    val birthDate: String = "",
    val isPrivate: Boolean = false,
    val isMinor: Boolean = false,
    val accountMode: AccountMode = AccountMode.Personal,
    val verified: Boolean = false,
    val auraPoints: Int = 0,
    val streakDays: Int = 0,
    val postsCount: Int = 0,
    val followersCount: Int = 0,
    val followingCount: Int = 0,
    val profileViews: Int = 0,
    val createdAt: Long = 0L,
) {
    val auraGrade: AuraGrade get() = AuraGrade.forPoints(auraPoints)
}

/** Fields a user can change from Edit Profile. */
data class ProfileUpdate(
    val displayName: String,
    val username: String,
    val bio: String,
    val vibe: String,
    val city: String,
)

/** Aura grades: C 1+, B 300+, A 500+, S 700+, SSS 900+ (out of 1000). */
enum class AuraGrade(val label: String, val minPoints: Int, val description: String) {
    C("C", 0, "Just getting started"),
    B("B", 300, "Active creator"),
    A("A", 500, "Community favourite"),
    S("S", 700, "Top-tier presence"),
    SSS("SSS", 900, "Legend status");

    companion object {
        const val MAX_POINTS = 1000

        fun forPoints(points: Int): AuraGrade = entries.last { points >= it.minPoints }
    }
}

/** Fun "vibe" tags a user can pin to their profile. */
val ProfileVibes = listOf(
    "Main Character",
    "Soft Life",
    "Grindset",
    "Chaotic Good",
    "Touch Grass",
    "Night Owl",
    "Plant Parent",
    "Gamer Mode",
)
