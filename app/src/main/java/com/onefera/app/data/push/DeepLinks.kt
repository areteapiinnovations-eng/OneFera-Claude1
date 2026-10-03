package com.onefera.app.data.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-app targets opened from notifications, encoded as "chat:{conversationId}", "post:{postId}",
 * "user:{uid}" or "notifications". The NavHost consumes them once the user is signed in.
 */
@Singleton
class DeepLinks @Inject constructor() {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun post(link: String?) {
        if (!link.isNullOrBlank()) _pending.value = link
    }

    fun consumed() {
        _pending.value = null
    }

    companion object {
        const val EXTRA = "open"
    }
}

/** The conversation currently on screen, so we don't notify about messages the user is already reading. */
@Singleton
class ActiveConversation @Inject constructor() {
    @Volatile var id: String? = null
}
