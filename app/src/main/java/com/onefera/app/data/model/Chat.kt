package com.onefera.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AttachmentType { Image, File, Audio }

@Serializable
data class Attachment(
    val url: String = "",
    val type: AttachmentType = AttachmentType.Image,
    val name: String = "",
    val sizeBytes: Long = 0L,
    val aspectRatio: Float = 1f,
    /** Length of a voice message, in milliseconds. */
    val durationMs: Long = 0L,
)

/** Snapshot of the message being replied to, stored on the reply so it renders without a lookup. */
@Serializable
data class ReplyPreview(
    val messageId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val text: String = "",
)

@Serializable
data class Message(
    val id: String = "",
    val conversationId: String = "",
    val senderId: String = "",
    val text: String = "",
    val attachment: Attachment? = null,
    val replyTo: ReplyPreview? = null,
    val createdAt: Long = 0L,
    val unsent: Boolean = false,
    /** The sender changed the text after sending. */
    val edited: Boolean = false,
    /** Sent with "Forward" from another chat. */
    val forwarded: Boolean = false,
    /** Members who deleted this message for themselves only. */
    val deletedFor: List<String> = emptyList(),
) {
    /** One-line summary for previews and notifications. */
    val preview: String
        get() = when {
            unsent -> "Message unsent"
            text.isNotBlank() -> text
            attachment?.type == AttachmentType.Image -> "📷 Photo"
            attachment?.type == AttachmentType.Audio -> "🎤 Voice message"
            attachment != null -> "📎 ${attachment.name.ifBlank { "File" }}"
            else -> ""
        }

    /** Senders may fix a message's text for this long after sending. */
    fun canEdit(me: String, now: Long = System.currentTimeMillis()): Boolean =
        senderId == me && !unsent && text.isNotBlank() && now - createdAt <= EDIT_WINDOW_MS

    companion object {
        const val EDIT_WINDOW_MS = 15 * 60 * 1000L
    }
}

/** Delivery state of one of my messages, from the other member's read time. */
enum class MessageStatus { Sent, Seen }

/** A one-to-one conversation. The id is both member uids, sorted and joined with "_". */
@Serializable
data class Conversation(
    val id: String = "",
    val memberIds: List<String> = emptyList(),
    val members: Map<String, UserSummary> = emptyMap(),
    val lastMessage: String = "",
    val lastSenderId: String = "",
    val lastMessageAt: Long = 0L,
    val unreadCounts: Map<String, Int> = emptyMap(),
    val lastReadAt: Map<String, Long> = emptyMap(),
    /** uid -> time the user last typed (millis). */
    val typing: Map<String, Long> = emptyMap(),
) {
    fun other(me: String): UserSummary = members.entries.firstOrNull { it.key != me }?.value ?: UserSummary()
    fun unreadFor(me: String): Int = unreadCounts[me] ?: 0
    fun isTyping(uid: String, now: Long = System.currentTimeMillis()): Boolean = (typing[uid] ?: 0L) > now - TYPING_TTL_MS

    companion object {
        const val TYPING_TTL_MS = 6_000L
        fun idFor(a: String, b: String): String = listOf(a, b).sorted().joinToString("_")
    }
}

/** Gen Z quick replies, shown as chips above the chat composer. */
val QuickReplies = listOf("no cap 🔥", "lowkey yes", "understood 👍", "bussin fr", "slay bestie 💅", "W 🏆", "say less", "😂😂")

/** "Active now", "Active 5m ago"… from a last-active timestamp; null when unknown or older than a day. */
fun presenceLabel(lastActiveAt: Long, now: Long = System.currentTimeMillis()): String? {
    if (lastActiveAt <= 0L) return null
    val minutes = (now - lastActiveAt) / 60_000
    return when {
        minutes < 3 -> "Active now"
        minutes < 60 -> "Active ${minutes}m ago"
        minutes < 24 * 60 -> "Active ${minutes / 60}h ago"
        else -> null
    }
}
