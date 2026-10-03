package com.onefera.app.feature.story

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil3.compose.AsyncImage
import com.onefera.app.R
import com.onefera.app.core.common.timeAgo
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.data.model.StoryGroup
import com.onefera.app.data.social.StoryRepository
import com.onefera.app.navigation.StoryViewerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class StoryViewerUiState(val loading: Boolean = true, val groups: List<StoryGroup> = emptyList(), val startIndex: Int = 0)

@HiltViewModel
class StoryViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    stories: StoryRepository,
    private val seen: SeenStories,
) : ViewModel() {
    private val authorUid = savedStateHandle.toRoute<StoryViewerRoute>().authorUid

    val state: StateFlow<StoryViewerUiState> = stories.storyGroups().map { groups ->
        StoryViewerUiState(false, groups, groups.indexOfFirst { it.author.uid == authorUid }.coerceAtLeast(0))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoryViewerUiState())

    fun markSeen(id: String) = seen.markSeen(id)
}

private const val STORY_DURATION_MS = 5_000

@Composable
fun StoryViewerScreen(onClose: () -> Unit, viewModel: StoryViewerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            return@Box
        }
        if (state.groups.isEmpty()) {
            LaunchedEffect(Unit) { onClose() }
            return@Box
        }
        // Snapshot the groups when the viewer opens so new stories don't shift the position.
        val groups = remember { state.groups }
        var groupIndex by remember { mutableIntStateOf(state.startIndex.coerceIn(0, groups.lastIndex)) }
        var storyIndex by remember { mutableIntStateOf(0) }
        var paused by remember { mutableStateOf(false) }
        val progress = remember { Animatable(0f) }
        val group = groups[groupIndex]
        val story = group.stories[storyIndex.coerceIn(0, group.stories.lastIndex)]

        fun next() {
            when {
                storyIndex < group.stories.lastIndex -> storyIndex++
                groupIndex < groups.lastIndex -> { groupIndex++; storyIndex = 0 }
                else -> onClose()
            }
        }
        fun previous() {
            when {
                storyIndex > 0 -> storyIndex--
                groupIndex > 0 -> { groupIndex--; storyIndex = 0 }
            }
        }

        LaunchedEffect(story.id) {
            viewModel.markSeen(story.id)
            progress.snapTo(0f)
        }
        LaunchedEffect(story.id, paused) {
            if (!paused) {
                val remaining = ((1f - progress.value) * STORY_DURATION_MS).toInt()
                progress.animateTo(1f, tween(remaining, easing = LinearEasing))
                next()
            }
        }

        AsyncImage(
            model = story.mediaUrl,
            contentDescription = "Story by ${story.author.displayName}",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(story.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val pressedAt = System.currentTimeMillis()
                        paused = true
                        val up = waitForUpOrCancellation()
                        paused = false
                        val wasTap = System.currentTimeMillis() - pressedAt < 250
                        if (up != null && wasTap) {
                            if (down.position.x < size.width / 3f) previous() else next()
                        }
                    }
                },
        )
        Box(
            Modifier.fillMaxWidth().height(160.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))),
        )
        Column(Modifier.statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                group.stories.forEachIndexed { i, _ ->
                    val fill = when {
                        i < storyIndex -> 1f
                        i == storyIndex -> progress.value
                        else -> 0f
                    }
                    Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f))) {
                        Box(Modifier.fillMaxWidth(fill).height(3.dp).background(Color.White))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(story.author.avatarUrl, story.author.displayName, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (group.isMine) "Your story" else story.author.username, color = Color.White, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                Text(timeAgo(story.createdAt), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Close stories", tint = Color.White)
                }
            }
        }
    }
}
