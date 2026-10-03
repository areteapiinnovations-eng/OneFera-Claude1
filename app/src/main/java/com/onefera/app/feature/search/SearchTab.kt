package com.onefera.app.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.component.SelectChip
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.model.TagSummary
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.settings.SettingsRepository
import com.onefera.app.data.social.PostRepository
import com.onefera.app.data.social.SocialRepository
import com.onefera.app.feature.user.UserRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchSection(val label: String) { Top("Top"), Accounts("Accounts"), Tags("Tags"), Shop("Shop") }

data class SearchResults(val query: String = "", val searching: Boolean = false, val users: List<UserSummary> = emptyList(), val tags: List<TagSummary> = emptyList())

data class SearchUiState(
    val query: String = "",
    val section: SearchSection = SearchSection.Top,
    val results: SearchResults = SearchResults(),
    val recent: List<String> = emptyList(),
    val trending: List<TagSummary> = emptyList(),
    val suggestions: List<UserSummary> = emptyList(),
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val posts: PostRepository,
    private val social: SocialRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val section = MutableStateFlow(SearchSection.Top)

    private val results = query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce(300)
        .transformLatest { q ->
            if (q.isEmpty()) {
                emit(SearchResults())
            } else {
                emit(SearchResults(query = q, searching = true))
                emit(SearchResults(query = q, users = social.searchUsers(q), tags = posts.searchTags(q)))
            }
        }

    private val trending = flow { emit(posts.trendingTags()) }

    val state: StateFlow<SearchUiState> = combine(
        combine(query, section) { q, s -> q to s },
        results,
        settings.settings.map { it.recentSearches },
        trending,
        social.suggestions(),
    ) { (q, s), r, recent, trend, suggested ->
        SearchUiState(q, s, r, recent, trend, suggested.take(8))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onQueryChange(value: String) {
        query.value = value.take(60)
    }

    fun onSection(value: SearchSection) {
        section.value = value
    }

    fun remember(term: String) = viewModelScope.launch { settings.addRecentSearch(term) }
    fun forget(term: String) = viewModelScope.launch { settings.removeRecentSearch(term) }
    fun clearRecent() = viewModelScope.launch { settings.clearRecentSearches() }
}

@Composable
fun SearchTab(viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val focus = LocalFocusManager.current
    val openUser: (UserSummary) -> Unit = { user ->
        viewModel.remember("@${user.username}")
        focus.clearFocus()
        actions.openUser(user.uid)
    }
    val openTag: (String) -> Unit = { tag ->
        viewModel.remember("#$tag")
        focus.clearFocus()
        actions.openTag(tag)
    }

    val clearButton: (@Composable () -> Unit)? = if (state.query.isNotEmpty()) {
        { IconButton(onClick = { viewModel.onQueryChange("") }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search", modifier = Modifier.size(20.dp)) } }
    } else {
        null
    }
    Column(Modifier.fillMaxSize()) {
        OneFeraTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            label = "Search people, #tags, drops…",
            leadingIcon = R.drawable.ic_search,
            imeAction = ImeAction.Search,
            onImeAction = {
                if (state.query.isNotBlank()) viewModel.remember(state.query.trim())
                focus.clearFocus()
            },
            trailing = clearButton,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SearchSection.entries) { s -> SelectChip(s.label, selected = state.section == s, onClick = { viewModel.onSection(s) }) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (state.section == SearchSection.Shop) {
                item {
                    EmptyState(
                        icon = R.drawable.ic_shop_filled,
                        title = "Shop search is coming 🛍️",
                        message = "Find products, brands and creator stores once the shop opens.",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (state.query.isBlank()) {
                discover(state, onRecent = viewModel::onQueryChange, onForget = { viewModel.forget(it) }, onClear = { viewModel.clearRecent() }, openUser = openUser, openTag = openTag)
            } else {
                results(state, openUser, openTag)
            }
        }
    }
}

private fun LazyListScope.discover(
    state: SearchUiState,
    onRecent: (String) -> Unit,
    onForget: (String) -> Unit,
    onClear: () -> Unit,
    openUser: (UserSummary) -> Unit,
    openTag: (String) -> Unit,
) {
    if (state.recent.isNotEmpty()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Recent", Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
        items(state.recent, key = { "recent-$it" }) { term ->
            Row(
                Modifier.fillMaxWidth().clickable { onRecent(term) }.padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_history), contentDescription = null, tint = OneFeraTheme.extras.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(term, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { onForget(term) }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove $term", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    if (state.trending.isNotEmpty()) {
        item { SectionTitle("Trending rn 🔥", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)) }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.trending.forEach { tag -> SelectChip("#${tag.tag}", selected = false, onClick = { openTag(tag.tag) }) }
            }
        }
    }
    if (state.suggestions.isNotEmpty()) {
        item { SectionTitle("People to vibe with", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
        items(state.suggestions, key = { "suggest-${it.uid}" }) { user -> UserRow(user, onClick = { openUser(user) }) }
    }
}

private fun LazyListScope.results(state: SearchUiState, openUser: (UserSummary) -> Unit, openTag: (String) -> Unit) {
    val r = state.results
    val showUsers = state.section == SearchSection.Top || state.section == SearchSection.Accounts
    val showTags = state.section == SearchSection.Top || state.section == SearchSection.Tags
    val users = if (state.section == SearchSection.Top) r.users.take(5) else r.users
    val tags = if (state.section == SearchSection.Top) r.tags.take(5) else r.tags
    if (r.searching) {
        item { Text("Searching…", color = OneFeraTheme.extras.muted, modifier = Modifier.padding(16.dp)) }
        return
    }
    if ((!showUsers || users.isEmpty()) && (!showTags || tags.isEmpty())) {
        item {
            EmptyState(
                icon = R.drawable.ic_search,
                title = "No matches for \"${state.query.trim()}\"",
                message = "Try a different name, @handle or #tag.",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }
    if (showUsers && users.isNotEmpty()) {
        item { SectionTitle("Accounts", Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)) }
        items(users, key = { "user-${it.uid}" }) { user -> UserRow(user, onClick = { openUser(user) }) }
    }
    if (showTags && tags.isNotEmpty()) {
        item { SectionTitle("Tags", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
        items(tags, key = { "tag-${it.tag}" }) { tag ->
            Row(
                Modifier.fillMaxWidth().clickable { openTag(tag.tag) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassCard(contentPadding = 10.dp) {
                    Icon(painterResource(R.drawable.ic_tag), contentDescription = null, modifier = Modifier.size(22.dp).gradientTint(OneFeraTheme.extras.gradientBrush()))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("#${tag.tag}", style = MaterialTheme.typography.titleSmall)
                    Text("${tag.postCount} ${if (tag.postCount == 1) "post" else "posts"}", style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}
