package com.onefera.app.data.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.firebase.currentUid
import com.onefera.app.data.firebase.runFriendly
import com.onefera.app.data.firebase.snapshotFlow
import com.onefera.app.data.firebase.toMap
import com.onefera.app.data.firebase.toUserSummary
import com.onefera.app.data.firebase.toUserSummaryDoc
import com.onefera.app.data.firebase.uidFlow
import com.onefera.app.data.media.MediaProcessor
import com.onefera.app.data.model.Attachment
import com.onefera.app.data.model.AttachmentType
import com.onefera.app.data.model.Conversation
import com.onefera.app.data.model.Message
import com.onefera.app.data.model.ReplyPreview
import com.onefera.app.data.model.UserSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore layout:
 * - `conversations/{a_b}`              members, last message, per-member unread counts / read time / typing
 * - `conversations/{a_b}/messages/{id}` messages (lastMessage + unreadCounts are updated by Cloud Functions)
 * Attachments live in Cloud Storage under `chats/{conversationId}/`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreChatRepository @Inject constructor(
    private val auth: AuthRepository,
    private val media: MediaProcessor,
    @ApplicationContext private val context: Context,
) : ChatRepository {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }
    private val conversations get() = db.collection("conversations")

    override fun conversations(): Flow<List<Conversation>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            conversations.whereArrayContains("memberIds", uid)
                .orderBy("lastMessageAt", Query.Direction.DESCENDING).limit(100)
                .snapshotFlow().map { s -> s.documents.map { it.toConversation() }.filter { it.lastMessageAt > 0L } }
        }
    }

    override fun conversation(conversationId: String): Flow<Conversation?> =
        conversations.document(conversationId).snapshotFlow().map { if (it.exists()) it.toConversation() else null }

    override fun messages(conversationId: String): Flow<List<Message>> =
        conversations.document(conversationId).collection("messages")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(MESSAGE_LIMIT)
            .snapshotFlow().map { s -> s.documents.map { it.toMessage(conversationId) } }

    override fun totalUnread(): Flow<Int> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) flowOf(0) else conversations().map { list -> list.count { it.unreadFor(uid) > 0 } }
    }

    override suspend fun openConversation(other: UserSummary): Result<String> = runFriendly {
        val uid = auth.currentUid()
        if (other.uid == uid) throw UserFacingException("That's you 😄")
        val id = Conversation.idFor(uid, other.uid)
        val ref = conversations.document(id)
        val existing = runCatching { ref.get().await() }.getOrNull()
        if (existing == null || !existing.exists()) {
            val me = db.collection("users").document(uid).get().await().toUserSummaryDoc()
            ref.set(
                mapOf(
                    "memberIds" to listOf(uid, other.uid).sorted(),
                    "members" to mapOf(uid to me.toMap(), other.uid to other.toMap()),
                    "lastMessage" to "",
                    "lastSenderId" to "",
                    "lastMessageAt" to 0L,
                    "unreadCounts" to mapOf(uid to 0, other.uid to 0),
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
        }
        id
    }

    override suspend fun send(conversationId: String, message: OutgoingMessage, onProgress: (Float) -> Unit): Result<Unit> = runFriendly {
        val uid = auth.currentUid()
        val ref = conversations.document(conversationId).collection("messages").document()
        val attachment = message.attachment?.let { uploadAttachment(conversationId, ref.id, it, message.attachmentType, onProgress) }
        val data = mutableMapOf<String, Any?>(
            "senderId" to uid,
            "text" to message.text.trim().take(MAX_TEXT),
            "createdAt" to FieldValue.serverTimestamp(),
            "unsent" to false,
        )
        if (attachment != null) {
            data["attachment"] = mapOf(
                "url" to attachment.url,
                "type" to attachment.type.name,
                "name" to attachment.name,
                "sizeBytes" to attachment.sizeBytes,
                "aspectRatio" to attachment.aspectRatio.toDouble(),
            )
        }
        message.replyTo?.let { r ->
            data["replyTo"] = mapOf("messageId" to r.id, "senderId" to r.senderId, "senderName" to "", "text" to r.preview.take(120))
        }
        ref.set(data).await()
        setTyping(conversationId, false)
    }

    override suspend fun unsend(conversationId: String, messageId: String): Result<Unit> = runFriendly {
        conversations.document(conversationId).collection("messages").document(messageId)
            .update(mapOf("unsent" to true, "text" to "", "attachment" to null)).await()
    }

    override suspend fun markRead(conversationId: String) {
        val uid = runCatching { auth.currentUid() }.getOrNull() ?: return
        runCatching {
            conversations.document(conversationId).set(
                mapOf("unreadCounts" to mapOf(uid to 0), "lastReadAt" to mapOf(uid to System.currentTimeMillis())),
                SetOptions.merge(),
            ).await()
        }
    }

    override suspend fun setTyping(conversationId: String, typing: Boolean) {
        val uid = runCatching { auth.currentUid() }.getOrNull() ?: return
        runCatching {
            conversations.document(conversationId)
                .set(mapOf("typing" to mapOf(uid to if (typing) System.currentTimeMillis() else 0L)), SetOptions.merge()).await()
        }
    }

    override fun lastActive(uid: String): Flow<Long> =
        db.collection("users").document(uid).snapshotFlow().map { it.getLong("lastActiveAt") ?: 0L }

    private suspend fun uploadAttachment(cid: String, mid: String, uri: Uri, type: AttachmentType, onProgress: (Float) -> Unit): Attachment {
        val (file, name, contentType, aspect, cleanup) = when (type) {
            AttachmentType.Image -> {
                val prepared = media.prepareImage(uri)
                Prepared(prepared.file, "photo.jpg", "image/jpeg", prepared.aspectRatio) { media.cleanUp(prepared) }
            }
            AttachmentType.File -> {
                val name = displayName(uri)
                val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                if (size > MAX_FILE_BYTES) throw UserFacingException("Files can be up to ${MAX_FILE_BYTES / 1_000_000} MB.")
                val copy = File(context.cacheDir, "upload-$mid").also { f ->
                    context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                        ?: throw UserFacingException("Couldn't read that file.")
                }
                Prepared(copy, name, context.contentResolver.getType(uri) ?: "application/octet-stream", 1f) { copy.delete() }
            }
        }
        try {
            val ref = storage.reference.child("chats/$cid/$mid/${name.replace('/', '_')}")
            ref.putFile(Uri.fromFile(file), StorageMetadata.Builder().setContentType(contentType).build())
                .addOnProgressListener { onProgress(it.bytesTransferred.toFloat() / it.totalByteCount.coerceAtLeast(1)) }
                .await()
            return Attachment(ref.downloadUrl.await().toString(), type, name, file.length(), aspect)
        } finally {
            cleanup()
        }
    }

    private data class Prepared(val file: File, val name: String, val contentType: String, val aspect: Float, val cleanup: () -> Unit)

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "file"

    companion object {
        const val MESSAGE_LIMIT = 200L
        const val MAX_TEXT = 2000
        const val MAX_FILE_BYTES = 20_000_000L
    }
}

private fun DocumentSnapshot.toConversation(): Conversation {
    @Suppress("UNCHECKED_CAST")
    fun longMap(field: String): Map<String, Long> = (get(field) as? Map<String, Any?>).orEmpty()
        .mapValues { (it.value as? Number)?.toLong() ?: 0L }
    return Conversation(
        id = id,
        memberIds = (get("memberIds") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        members = (get("members") as? Map<*, *>).orEmpty().entries.associate { (k, v) -> k.toString() to (v as? Map<*, *>).toUserSummary() },
        lastMessage = getString("lastMessage").orEmpty(),
        lastSenderId = getString("lastSenderId").orEmpty(),
        lastMessageAt = getLong("lastMessageAt") ?: 0L,
        unreadCounts = longMap("unreadCounts").mapValues { it.value.toInt() },
        lastReadAt = longMap("lastReadAt"),
        typing = longMap("typing"),
    )
}

private fun DocumentSnapshot.toMessage(conversationId: String): Message {
    val a = get("attachment") as? Map<*, *>
    val r = get("replyTo") as? Map<*, *>
    return Message(
        id = id,
        conversationId = conversationId,
        senderId = getString("senderId").orEmpty(),
        text = getString("text").orEmpty(),
        attachment = a?.let {
            Attachment(
                url = it["url"] as? String ?: "",
                type = runCatching { AttachmentType.valueOf(it["type"] as? String ?: "") }.getOrDefault(AttachmentType.File),
                name = it["name"] as? String ?: "",
                sizeBytes = (it["sizeBytes"] as? Number)?.toLong() ?: 0L,
                aspectRatio = (it["aspectRatio"] as? Number)?.toFloat() ?: 1f,
            )
        },
        replyTo = r?.let {
            ReplyPreview(it["messageId"] as? String ?: "", it["senderId"] as? String ?: "", it["senderName"] as? String ?: "", it["text"] as? String ?: "")
        },
        createdAt = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time ?: System.currentTimeMillis(),
        unsent = getBoolean("unsent") ?: false,
    )
}
