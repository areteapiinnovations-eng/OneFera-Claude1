package com.onefera.app.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.chat.ChatRepository
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.social.SocialRepository
import com.onefera.app.feature.auth.FormError
import com.onefera.app.feature.user.UserRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NewChatUiState(
    val query: String = "",
    val people: List<UserSummary> = emptyList(),
    val opening: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class NewChatViewModel @Inject constructor(
    private val chat: ChatRepository,
    social: SocialRepository,
    auth: AuthRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val status = MutableStateFlow<Pair<Boolean, String?>>(false to null)
    private val _opened = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val opened: SharedFlow<String> = _opened

    private val myUid = auth.session.map { (it as? SessionState.SignedIn)?.uid }

    private val people = combine(query.debounce(250), myUid) { q, uid -> q to uid }.flatMapLatest { (q, uid) ->
        when {
            q.isNotBlank() -> flow { emit(social.searchUsers(q)) }
            uid == null -> flowOf(emptyList())
            // Default list: people you follow first, then suggestions.
            else -> combine(social.following(uid), social.suggestions()) { following, suggested -> (following + suggested).distinctBy { it.uid } }
        }
    }

    val state: StateFlow<NewChatUiState> = combine(query, people, status, myUid) { q, list, (opening, error), uid ->
        NewChatUiState(q, list.filter { it.uid != uid }, opening, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewChatUiState())

    fun onQuery(v: String) {
        query.value = v.take(40)
    }

    fun open(user: UserSummary) {
        if (status.value.first) return
        status.value = true to null
        viewModelScope.launch {
            chat.openConversation(user)
                .onSuccess { _opened.emit(it) }
                .onFailure { status.value = false to it.message }
            status.value = false to status.value.second
        }
    }
}

@Composable
fun NewChatScreen(onBack: () -> Unit, onOpened: (String) -> Unit, viewModel: NewChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.opened.collect { onOpened(it) } }
    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.45f) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "New message", onBack = onBack)
            OneFeraTextField(
                value = state.query,
                onValueChange = viewModel::onQuery,
                label = "To: name or @handle",
                leadingIcon = R.drawable.ic_search,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            FormError(state.error)
            if (state.people.isEmpty()) {
                EmptyState(R.drawable.ic_person, "No one found", "Try another name or @handle.", Modifier.fillMaxWidth())
            } else {
                Text(
                    if (state.query.isBlank()) "Suggested" else "Results",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(state.people, key = { it.uid }) { user -> UserRow(user, onClick = { viewModel.open(user) }) }
                }
            }
        }
    }
}
