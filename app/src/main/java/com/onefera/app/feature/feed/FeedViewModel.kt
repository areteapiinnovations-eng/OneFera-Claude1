package com.onefera.app.feature.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.model.StoryGroup
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.social.FeedScope
import com.onefera.app.data.social.PostRepository
import com.onefera.app.data.social.SocialRepository
import com.onefera.app.data.social.StoryRepository
import com.onefera.app.feature.post.FeedItem
import com.onefera.app.feature.post.PostActions
import com.onefera.app.feature.post.withInteractions
import com.onefera.app.feature.story.SeenStories
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.onefera.app.data.moderation.ModerationRepository
import javax.inject.Inject

data class FeedUiState(
    val loading: Boolean = true,
    val scope: FeedScope = FeedScope.ForYou,
    val items: List<FeedItem> = emptyList(),
    val stories: List<StoryGroup> = emptyList(),
    val seenStoryIds: Set<String> = emptySet(),
    val suggestions: List<UserSummary> = emptyList(),
    val myUid: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val posts: PostRepository,
    stories: StoryRepository,
    private val social: SocialRepository,
    private val actions: PostActions,
    seenStories: SeenStories,
    auth: AuthRepository,
    moderation: ModerationRepository,
) : ViewModel() {
    private val blocked = moderation.blockedIds()

    private val scope = MutableStateFlow(FeedScope.ForYou)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val items = scope.flatMapLatest { s -> posts.feed(s).withInteractions(posts, blocked).map { s to it } }

    val state: StateFlow<FeedUiState> = combine(
        items,
        combine(stories.storyGroups(), blocked) { groups, hidden -> groups.filter { it.author.uid !in hidden } },
        seenStories.seen,
        social.suggestions(),
        auth.session.map { (it as? SessionState.SignedIn)?.uid },
    ) { (feedScope, list), storyGroups, seen, suggestions, uid ->
        FeedUiState(
            loading = false,
            scope = feedScope,
            items = list,
            stories = storyGroups,
            seenStoryIds = seen,
            suggestions = suggestions.take(10),
            myUid = uid,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUiState())

    private val _selectedScope = MutableStateFlow(FeedScope.ForYou)
    val selectedScope: StateFlow<FeedScope> = _selectedScope.asStateFlow()

    fun setScope(value: FeedScope) {
        _selectedScope.value = value
        scope.value = value
    }

    fun toggleLike(item: FeedItem) = launchAction { actions.toggleLike(item) }
    fun like(item: FeedItem) = launchAction { actions.like(item) }
    fun toggleSave(item: FeedItem) = launchAction(if (item.saved) "Removed from saved" else "Saved ✨") { actions.toggleSave(item) }
    fun delete(item: FeedItem) = launchAction("Post deleted") { actions.delete(item) }

    fun follow(user: UserSummary) = viewModelScope.launch {
        social.follow(user)
            .onSuccess { _messages.emit(if (user.isPrivate) "Request sent to @${user.username}" else "Following @${user.username} ✨") }
            .onFailure { _messages.emit(it.message ?: "Couldn't follow right now") }
    }

    private fun launchAction(success: String? = null, block: suspend () -> Result<Unit>) = viewModelScope.launch {
        block()
            .onSuccess { if (success != null) _messages.emit(success) }
            .onFailure { _messages.emit(it.message ?: "Something went wrong") }
    }
}
