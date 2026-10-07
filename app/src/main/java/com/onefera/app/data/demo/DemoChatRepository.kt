package com.onefera.app.data.demo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.backend.ApplicationScope
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.chat.ChatRepository
import com.onefera.app.data.chat.OutgoingMessage
import com.onefera.app.data.firebase.uidFlow
import com.onefera.app.data.media.MediaProcessor
import com.onefera.app.data.model.Attachment
import com.onefera.app.data.model.AttachmentType
import com.onefera.app.data.model.Conversation
import com.onefera.app.data.model.Message
import com.onefera.app.data.model.QuickReplies
import com.onefera.app.data.model.ReplyPreview
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.model.toSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.demoChatStore: DataStore<Preferences> by preferencesDataStore(name = "demo_chat")

/**
 * On-device chat for demo mode. Seed creators "type" and reply a couple of seconds after you
 * message them, so the full chat experience can be tried before Firebase is connected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DemoChatRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val auth: AuthRepository,
    private val accounts: DemoBackend,
    private val media: MediaProcessor,
) : ChatRepository {

    @Serializable
    data class ChatState(
        val conversations: List<Conversation> = emptyList(),
        val messages: Map<String, List<Message>> = emptyMap(),
        val seededFor: Set<String> = emptySet(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val state = MutableStateFlow<ChatState?>(null)
    private val data: Flow<ChatState> = state.filterNotNull()

    init {
        scope.launch {
            state.value = context.demoChatStore.data.first()[KEY]
                ?.let { runCatching { json.decodeFromString(ChatState.serializer(), it) }.getOrNull() } ?: ChatState()
        }
    }

    private suspend fun <T> update(block: (ChatState) -> Pair<ChatState, T>): T = mutex.withLock {
        val (next, result) = block(data.first())
        state.value = next
        context.demoChatStore.edit { it[KEY] = json.encodeToString(ChatState.serializer(), next) }
        result
    }

    private fun uid(): String = (auth.session.value as? SessionState.SignedIn)?.uid ?: throw UserFacingException("Please log in again.")

    override fun conversations(): Flow<List<Conversation>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            data.map { s -> s.conversations.filter { uid in it.memberIds && it.lastMessageAt > 0 }.sortedByDescending { it.lastMessageAt } }
                .distinctUntilChanged()
                .onStart { ensureSeeded(uid) }
        }
    }

    override fun conversation(conversationId: String): Flow<Conversation?> =
        data.map { s -> s.conversations.firstOrNull { it.id == conversationId } }.distinctUntilChanged()

    override fun messages(conversationId: String): Flow<List<Message>> = auth.uidFlow().flatMapLatest { uid ->
        data.map { s -> s.messages[conversationId].orEmpty().filter { uid !in it.deletedFor }.sortedByDescending { it.createdAt } }.distinctUntilChanged()
    }

    override fun totalUnread(): Flow<Int> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) flowOf(0) else conversations().map { list -> list.count { it.unreadFor(uid) > 0 } }
    }

    override suspend fun openConversation(other: UserSummary): Result<String> = runCatching {
        val me = uid()
        if (other.uid == me) throw UserFacingException("That's you 😄")
        val mine = accounts.profileNow(me)?.toSummary() ?: throw UserFacingException("Finish setting up your profile first.")
        val id = Conversation.idFor(me, other.uid)
        update { s ->
            if (s.conversations.any { it.id == id }) return@update s to id
            val conversation = Conversation(id, listOf(me, other.uid).sorted(), mapOf(me to mine, other.uid to other))
            s.copy(conversations = s.conversations + conversation) to id
        }
    }

    override suspend fun send(conversationId: String, message: OutgoingMessage, onProgress: (Float) -> Unit): Result<Unit> = runCatching {
        val me = uid()
        val id = UUID.randomUUID().toString()
        val attachment = message.existingAttachment
            ?: message.attachment?.let { storeAttachment(id, it, message.attachmentType) }?.copy(durationMs = message.durationMs)
        onProgress(1f)
        val msg = Message(
            id = id,
            conversationId = conversationId,
            senderId = me,
            text = message.text.trim().take(2000),
            attachment = attachment,
            replyTo = message.replyTo?.let { ReplyPreview(it.id, it.senderId, "", it.preview.take(120)) },
            createdAt = System.currentTimeMillis(),
            forwarded = message.forwarded,
        )
        append(msg)
        simulateReply(conversationId, me)
    }

    private suspend fun append(msg: Message) {
        update { s ->
            val conversations = s.conversations.map { c ->
                if (c.id != msg.conversationId) {
                    c
                } else {
                    c.copy(
                        lastMessage = msg.preview,
                        lastSenderId = msg.senderId,
                        lastMessageAt = msg.createdAt,
                        unreadCounts = c.memberIds.associateWith { m -> if (m == msg.senderId) 0 else c.unreadFor(m) + 1 },
                        typing = c.typing - msg.senderId,
                    )
                }
            }
            s.copy(conversations = conversations, messages = s.messages + (msg.conversationId to (s.messages[msg.conversationId].orEmpty() + msg))) to Unit
        }
    }

    /** The other person "reads", types for a moment and answers with a Gen Z reply. */
    private fun simulateReply(conversationId: String, me: String) {
        scope.launch {
            val other = state.value?.conversations?.firstOrNull { it.id == conversationId }?.other(me) ?: return@launch
            if (!other.uid.startsWith("demo-")) return@launch
            delay(1_200)
            update { s ->
                s.copy(conversations = s.conversations.map {
                    if (it.id == conversationId) it.copy(lastReadAt = it.lastReadAt + (other.uid to System.currentTimeMillis()), typing = it.typing + (other.uid to System.currentTimeMillis())) else it
                }) to Unit
            }
            accounts.adjustProfile(other.uid) { it.copy(lastActiveAt = System.currentTimeMillis()) }
            delay(2_500)
            append(
                Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    senderId = other.uid,
                    text = DEMO_REPLIES.random(),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun unsend(conversationId: String, messageId: String): Result<Unit> = runCatching {
        val me = uid()
        update { s ->
            val list = s.messages[conversationId].orEmpty().map {
                if (it.id == messageId && it.senderId == me) it.copy(unsent = true, text = "", attachment = null) else it
            }
            s.copy(messages = s.messages + (conversationId to list)) to Unit
        }
    }

    private suspend fun updateMessage(conversationId: String, messageId: String, transform: (Message) -> Message) = update { s ->
        val list = s.messages[conversationId].orEmpty().map { if (it.id == messageId) transform(it) else it }
        s.copy(messages = s.messages + (conversationId to list)) to Unit
    }

    override suspend fun edit(conversationId: String, messageId: String, text: String): Result<Unit> = runCatching {
        val me = uid()
        val body = text.trim().take(2000)
        if (body.isEmpty()) throw UserFacingException("A message can't be empty. Use Unsend to remove it.")
        val msg = data.first().messages[conversationId].orEmpty().firstOrNull { it.id == messageId } ?: throw UserFacingException("Message not found.")
        if (!msg.canEdit(me)) throw UserFacingException("Messages can be edited for ${Message.EDIT_WINDOW_MS / 60_000} minutes after sending.")
        updateMessage(conversationId, messageId) { it.copy(text = body, edited = true) }
    }

    override suspend fun deleteForMe(conversationId: String, messageId: String): Result<Unit> = runCatching {
        val me = uid()
        updateMessage(conversationId, messageId) { it.copy(deletedFor = (it.deletedFor + me).distinct()) }
    }

    override suspend fun forward(message: Message, toConversationId: String): Result<Unit> =
        send(toConversationId, OutgoingMessage(text = message.text, existingAttachment = message.attachment, forwarded = true))

    override suspend fun markRead(conversationId: String) {
        val me = runCatching { uid() }.getOrNull() ?: return
        update { s ->
            s.copy(conversations = s.conversations.map {
                if (it.id == conversationId) it.copy(unreadCounts = it.unreadCounts + (me to 0), lastReadAt = it.lastReadAt + (me to System.currentTimeMillis())) else it
            }) to Unit
        }
    }

    override suspend fun setTyping(conversationId: String, typing: Boolean) = Unit

    override fun lastActive(uid: String): Flow<Long> = accounts.profile(uid).map { it?.lastActiveAt ?: 0L }

    private suspend fun storeAttachment(id: String, uri: Uri, type: AttachmentType): Attachment = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "demo_media").apply { mkdirs() }
        when (type) {
            AttachmentType.Image -> {
                val prepared = media.prepareImage(uri)
                val file = File(dir, "chat-$id.jpg").also { prepared.file.copyTo(it, overwrite = true) }
                media.cleanUp(prepared)
                Attachment(Uri.fromFile(file).toString(), AttachmentType.Image, "photo.jpg", file.length(), prepared.aspectRatio)
            }
            AttachmentType.File -> {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "file"
                val file = File(dir, "chat-$id-${name.replace('/', '_')}")
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                    ?: throw UserFacingException("Couldn't read that file.")
                Attachment(Uri.fromFile(file).toString(), AttachmentType.File, name, file.length())
            }
            AttachmentType.Audio -> {
                val file = File(dir, "chat-$id.m4a")
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                    ?: throw UserFacingException("Couldn't read the recording.")
                Attachment(Uri.fromFile(file).toString(), AttachmentType.Audio, "voice.m4a", file.length())
            }
        }
    }

    /** First visit: three chats with demo creators, one of them unread. */
    private suspend fun ensureSeeded(uid: String) {
        if (data.first().seededFor.contains(uid)) return
        val mine = accounts.profileNow(uid)?.toSummary() ?: return
        val now = System.currentTimeMillis()
        val creators = DemoSeed.creators.associateBy { it.uid }
        fun conv(otherId: String, lines: List<Pair<Boolean, String>>, minutesAgo: Long, unread: Int): Pair<Conversation, List<Message>> {
            val other = creators.getValue(otherId).toSummary()
            val id = Conversation.idFor(uid, otherId)
            val messages = lines.mapIndexed { i, (fromMe, text) ->
                Message(
                    id = "$id-seed-$i",
                    conversationId = id,
                    senderId = if (fromMe) uid else otherId,
                    text = text,
                    createdAt = now - (minutesAgo + (lines.size - i) * 2) * 60_000,
                )
            }
            val last = messages.last()
            return Conversation(
                id = id,
                memberIds = listOf(uid, otherId).sorted(),
                members = mapOf(uid to mine, otherId to other),
                lastMessage = last.preview,
                lastSenderId = last.senderId,
                lastMessageAt = last.createdAt,
                unreadCounts = mapOf(uid to unread, otherId to 0),
                lastReadAt = mapOf(otherId to last.createdAt),
            ) to messages
        }
        val seeds = listOf(
            conv("demo-aanya", listOf(false to "Hiii 👋", true to "Hiee", false to "saw your fit pic, the vibe is unmatched", false to "where'd you get that jacket?? 👀"), 4, 2),
            conv("demo-kabir", listOf(true to "your new single is on repeat", false to "no cap?? 🔥", true to "lowkey yes", false to "understood 👍 sending you the next one early"), 45, 0),
            conv("demo-arjun", listOf(false to "Neon Kicks Y3K restock drops Friday 👟", false to "want me to hold a pair for you?"), 180, 1),
        )
        update { s ->
            if (uid in s.seededFor) return@update s to Unit
            val existing = s.conversations.map { it.id }.toSet()
            val fresh = seeds.filter { it.first.id !in existing }
            s.copy(
                conversations = s.conversations + fresh.map { it.first },
                messages = s.messages + fresh.associate { it.first.id to it.second },
                seededFor = s.seededFor + uid,
            ) to Unit
        }
        accounts.adjustProfile("demo-aanya") { it.copy(lastActiveAt = now) }
        accounts.adjustProfile("demo-kabir") { it.copy(lastActiveAt = now - 25 * 60_000) }
    }

    private companion object {
        val KEY = stringPreferencesKey("state")
        val DEMO_REPLIES = QuickReplies + listOf("haha fr", "omg yes 😭", "bet, talk soon ✨", "that's so real", "sending it rn")
    }
}
