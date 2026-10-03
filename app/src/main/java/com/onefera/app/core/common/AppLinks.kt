package com.onefera.app.core.common

/** Public web links. Point onefera.app at the marketing site / deep-link handler when it is live. */
object AppLinks {
    const val WEB_BASE = "https://onefera.app"
    fun profile(username: String) = "$WEB_BASE/@$username"
    const val TERMS = "$WEB_BASE/terms"
    const val PRIVACY = "$WEB_BASE/privacy"
    const val GUIDELINES = "$WEB_BASE/guidelines"
}
