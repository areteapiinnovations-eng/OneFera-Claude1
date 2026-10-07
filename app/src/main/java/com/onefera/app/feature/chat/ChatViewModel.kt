package com.onefera.app.feature.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.chat.ChatRepository
import com.onefera.app.data.chat.OutgoingMessage
import com.onefera.app.data.model.AttachmentType
import com.onefera.app.data.model.Conversation
import com.onefera.app.data.model.Message
import com.onefera.app.data.push.ActiveConversation
import com.onefera.app.navigation.ChatRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PendingAttachment(val uri: Uri, val type: AttachmentType, val label: String)

data class ChatUiState(
    val loading: Boolean = true,
    val myUid: String = "",
    val conversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val otherLastActive: Long = 0L,
    val draft: String = "",
    val replyTo: Message? = null,
    val attachment: PendingAttachment? = null,
    val sending: Boolean = false,
    val uploadProgress: Float? = null,
    val now: Long = System.currentTimeMillis(),
    /** The message whose text is being edited in the composer. */
    val editing: Message? = null,
    /** Elapsed time of the voice note being recorded, or null when not recording. */
    val recordingMs: Long? = null,
    /** Chats a message can be forwarded to. */
    val forwardTargets: List<Conversation> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chat: ChatRepository,
    private val active: ActiveConversation,
    auth: AuthRepository,
) : ViewModel() {
    val conversationId: String = savedStateHandle.toRoute<ChatRoute>().conversationId

    private val form = MutableStateFlow(ChatUiState())
    private val clock = MutableStateFlow(System.currentTimeMillis())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toasts: SharedFlow<String> = _messages

    private val myUid = auth.session.map { (it as? SessionState.SignedIn)?.uid.orEmpty() }.distinctUntilChanged()
    private val conversation = chat.conversation(conversationId)

    private val otherLastActive = combine(conversation.filterNotNull(), myUid) { c, me -> c.other(me).uid }
        .distinctUntilChanged()
        .flatMapLatest { chat.lastActive(it) }

    val state: StateFlow<ChatUiState> = combine(
        combine(myUid, conversation, chat.messages(conversationId)) { me, c, m -> Triple(me, c, m) },
        otherLastActive,
        form,
        clock,
        chat.conversations(),
    ) { (me, c, msgs), lastActive, f, now, all ->
        f.copy(
            loading = false,
            myUid = me,
            conversation = c,
            messages = msgs,
            otherLastActive = lastActive,
            now = now,
            forwardTargets = all.filter { it.id != conversationId },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    private var recorder: VoiceRecorder? = null
    private var recordingJob: Job? = null

    private var typingJob: Job? = null

    init {
        // Re-evaluate "typing…" / "Active now" labels as time passes.
        viewModelScope.launch {
            while (isActive) {
                delay(2_000)
                clock.value = System.currentTimeMillis()
            }
        }
        // Mark as read whenever new messages arrive while the chat is open.
        viewModelScope.launch {
            chat.messages(conversationId).map { it.firstOrNull()?.id }.distinctUntilChanged().collect {
                if (active.id == conversationId) chat.markRead(conversationId)
            }
        }
    }

    /** Called when the chat screen starts/stops being visible (drives read state and notifications). */
    fun onVisible(visible: Boolean) {
        if (visible) {
            active.id = conversationId
            viewModelScope.launch { chat.markRead(conversationId) }
        } else if (active.id == conversationId) {
            active.id = null
        }
    }

    fun onDraftChange(text: String) {
        form.update { it.copy(draft = text.take(2000)) }
        if (typingJob?.isActive != true && text.isNotBlank()) {
            typingJob = viewModelScope.launch {
                chat.setTyping(conversationId, true)
                delay(4_000)
            }
        }
    }

    fun reply(message: Message) = form.update { it.copy(replyTo = message) }
    fun cancelReply() = form.update { it.copy(replyTo = null) }
    fun attach(uri: Uri, type: AttachmentType, label: String) = form.update { it.copy(attachment = PendingAttachment(uri, type, label)) }
    fun removeAttachment() = form.update { it.copy(attachment = null) }

    fun startEdit(message: Message) = form.update { it.copy(editing = message, draft = message.text, replyTo = null, attachment = null) }
    fun cancelEdit() = form.update { it.copy(editing = null, draft = "") }

    fun send(text: String = form.value.draft) {
        val f = form.value
        f.editing?.let { editing ->
            if (text.isBlank() || text.trim() == editing.text) { cancelEdit(); return }
            viewModelScope.launch {
                chat.edit(conversationId, editing.id, text)
                    .onSuccess { form.update { it.copy(editing = null, draft = "") } }
                    .onFailure { _messages.emit(it.message ?: "Couldn't edit that message") }
            }
            return
        }
        if (f.sending || (text.isBlank() && f.attachment == null)) return
        form.update { it.copy(sending = true, uploadProgress = if (it.attachment != null) 0f else null) }
        viewModelScope.launch {
            val outgoing = OutgoingMessage(text, f.replyTo, f.attachment?.uri, f.attachment?.type ?: AttachmentType.Image)
            chat.send(conversationId, outgoing) { p -> form.update { it.copy(uploadProgress = p) } }
                .onSuccess {
                    form.update {
                        it.copy(
                            sending = false,
                            uploadProgress = null,
                            draft = if (text == it.draft) "" else it.draft,
                            replyTo = null,
                            attachment = null,
                        )
                    }
                }
                .onFailure { e ->
                    form.update { it.copy(sending = false, uploadProgress = null) }
                    _messages.emit(e.message ?: "Couldn't send. Try again.")
                }
        }
    }

    fun unsend(message: Message) = viewModelScope.launch {
        chat.unsend(conversationId, message.id).onFailure { _messages.emit(it.message ?: "Couldn't unsend") }
    }

    fun deleteForMe(message: Message) = viewModelScope.launch {
        chat.deleteForMe(conversationId, message.id)
            .onSuccess { _messages.emit("Deleted for you") }
            .onFailure { _messages.emit(it.message ?: "Couldn't delete") }
    }

    fun forward(message: Message, to: Conversation) = viewModelScope.launch {
        chat.forward(message, to.id)
            .onSuccess { _messages.emit("Forwarded to ${to.other(state.value.myUid).displayName}") }
            .onFailure { _messages.emit(it.message ?: "Couldn't forward") }
    }

    /** Starts a voice note. The screen has already obtained RECORD_AUDIO. */
    fun startRecording(context: Context) {
        if (recorder?.isRecording == true || form.value.sending) return
        val r = VoiceRecorder(context.applicationContext)
        if (!r.start()) {
            viewModelScope.launch { _messages.emit("Couldn't use the microphone. Is another app recording?") }
            return
        }
        recorder = r
        form.update { it.copy(recordingMs = 0L) }
        recordingJob = viewModelScope.launch {
            while (isActive && r.isRecording) {
                form.update { it.copy(recordingMs = r.elapsedMs()) }
                if (r.elapsedMs() >= VoiceRecorder.MAX_DURATION_MS) { stopAndSendRecording(); break }
                delay(200)
            }
        }
    }

    fun cancelRecording() {
        recordingJob?.cancel()
        recorder?.cancel()
        recorder = null
        form.update { it.copy(recordingMs = null) }
    }

    fun stopAndSendRecording() {
        recordingJob?.cancel()
        val result = recorder?.stop()
        recorder = null
        form.update { it.copy(recordingMs = null) }
        if (result == null) {
            viewModelScope.launch { _messages.emit("Hold on a little longer to record a voice note") }
            return
        }
        val (file, duration) = result
        form.update { it.copy(sending = true, uploadProgress = 0f) }
        viewModelScope.launch {
            val outgoing = OutgoingMessage("", form.value.replyTo, Uri.fromFile(file), AttachmentType.Audio, durationMs = duration)
            chat.send(conversationId, outgoing) { p -> form.update { it.copy(uploadProgress = p) } }
                .onSuccess { form.update { it.copy(sending = false, uploadProgress = null, replyTo = null) } }
                .onFailure { e ->
                    form.update { it.copy(sending = false, uploadProgress = null) }
                    _messages.emit(e.message ?: "Couldn't send the voice note")
                }
            file.delete()
        }
    }


    override fun onCleared() {
        recorder?.cancel()
        if (active.id == conversationId) active.id = null
    }
}
