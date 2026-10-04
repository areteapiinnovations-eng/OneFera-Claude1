package com.onefera.app.feature.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.model.Post
import com.onefera.app.data.social.PostRepository
import com.onefera.app.feature.post.postGridItems
import com.onefera.app.navigation.TagRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class TagUiState(val loading: Boolean = true, val posts: List<Post> = emptyList())

@HiltViewModel
class TagViewModel @Inject constructor(savedStateHandle: SavedStateHandle, posts: PostRepository) : ViewModel() {
    val tag: String = savedStateHandle.toRoute<TagRoute>().tag
    val state: StateFlow<TagUiState> = posts.postsWithTag(tag).map { TagUiState(false, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TagUiState())
}

@Composable
fun TagScreen(onBack: () -> Unit, viewModel: TagViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.45f) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "#${viewModel.tag}", onBack = onBack)
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                item {
                    Text(
                        if (state.loading) "Loading…" else "${state.posts.size} ${if (state.posts.size == 1) "post" else "posts"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OneFeraTheme.extras.muted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                    )
                }
                if (!state.loading && state.posts.isEmpty()) {
                    item {
                        EmptyState(R.drawable.ic_tag, "No posts with #${viewModel.tag} yet", "Start the trend ✨", Modifier.fillMaxWidth())
                    }
                }
                postGridItems(state.posts, onOpen = { actions.openPost(it.id) })
            }
        }
    }
}
