package com.onefera.app.data.chat

import android.net.Uri
import com.onefera.app.data.model.AttachmentType
import com.onefera.app.data.model.Conversation
import com.onefera.app.data.model.Message
import com.onefera.app.data.model.UserSummary
import kotlinx.coroutines.flow.Flow

/** What the user is sending: text and/or one attachment, optionally as a reply. */
data class OutgoingMessage(
    val text: String,
    val replyTo: Message? = null,
    val attachment: Uri? = null,
    val attachmentType: AttachmentType = AttachmentType.Image,
)

interface ChatRepository {
    /** The signed-in user's conversations, most recent first. */
    fun conversations(): Flow<List<Conversation>>
    fun conversation(conversationId: String): Flow<Conversation?>
    fun messages(conversationId: String): Flow<List<Message>>
    fun totalUnread(): Flow<Int>

    /** Finds or creates the one-to-one conversation with [other] and returns its id. */
    suspend fun openConversation(other: UserSummary): Result<String>

    suspend fun send(conversationId: String, message: OutgoingMessage, onProgress: (Float) -> Unit = {}): Result<Unit>
    suspend fun unsend(conversationId: String, messageId: String): Result<Unit>
    suspend fun markRead(conversationId: String)
    suspend fun setTyping(conversationId: String, typing: Boolean)

    /** Last-active time of a user (millis), for "Active now". */
    fun lastActive(uid: String): Flow<Long>
}
