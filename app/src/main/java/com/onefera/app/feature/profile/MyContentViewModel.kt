package com.onefera.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.model.Post
import com.onefera.app.data.model.PostType
import com.onefera.app.data.social.PostRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class MyContent(
    val loading: Boolean = true,
    val posts: List<Post> = emptyList(),
    val reels: List<Post> = emptyList(),
    val saved: List<Post> = emptyList(),
)

/** The signed-in user's own posts, reels and saved posts for the profile tab. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MyContentViewModel @Inject constructor(posts: PostRepository, auth: AuthRepository) : ViewModel() {
    val content: StateFlow<MyContent> = auth.session
        .map { (it as? SessionState.SignedIn)?.uid }
        .flatMapLatest { uid ->
            if (uid == null) {
                flowOf(MyContent(loading = false))
            } else {
                combine(posts.userPosts(uid), posts.savedPosts()) { mine, saved ->
                    MyContent(
                        loading = false,
                        posts = mine.filter { it.type == PostType.Post },
                        reels = mine.filter { it.type == PostType.Reel },
                        saved = saved,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyContent())
}
