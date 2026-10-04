package com.onefera.app.feature.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.model.StoryGroup
import com.onefera.app.data.model.UserProfile

/** Horizontal stories strip: "Your story" first, then everyone with active stories. */
@Composable
fun StoriesRow(
    me: UserProfile?,
    groups: List<StoryGroup>,
    seenIds: Set<String>,
    onAddStory: () -> Unit,
    onOpen: (StoryGroup) -> Unit,
) {
    val mine = groups.firstOrNull { it.isMine }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "me") {
            StoryBubble(
                name = "Your story",
                avatarUrl = me?.avatarUrl,
                displayName = me?.displayName ?: "You",
                ringState = if (mine == null) Ring.None else if (mine.stories.all { it.id in seenIds }) Ring.Seen else Ring.New,
                showAdd = true,
                onClick = { if (mine != null) onOpen(mine) else onAddStory() },
                onAdd = onAddStory,
            )
        }
        items(groups.filterNot { it.isMine }, key = { it.author.uid }) { group ->
            StoryBubble(
                name = group.author.username,
                avatarUrl = group.author.avatarUrl,
                displayName = group.author.displayName,
                ringState = if (group.stories.all { it.id in seenIds }) Ring.Seen else Ring.New,
                showAdd = false,
                onClick = { onOpen(group) },
                onAdd = {},
            )
        }
    }
}

private enum class Ring { None, New, Seen }

@Composable
private fun StoryBubble(
    name: String,
    avatarUrl: String?,
    displayName: String,
    ringState: Ring,
    showAdd: Boolean,
    onClick: () -> Unit,
    onAdd: () -> Unit,
) {
    val extras = OneFeraTheme.extras
    Column(
        Modifier.width(72.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            val ringModifier = when (ringState) {
                Ring.New -> Modifier.border(2.5.dp, extras.gradientBrush(), CircleShape)
                Ring.Seen -> Modifier.border(1.5.dp, extras.glassBorder, CircleShape)
                Ring.None -> Modifier
            }
            Box(Modifier.size(66.dp).then(ringModifier).padding(4.dp)) {
                Avatar(avatarUrl, displayName, size = 58.dp)
            }
            if (showAdd) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(extras.gradientBrush())
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                        .clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = "Add to your story", tint = extras.onGradient, modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (ringState == Ring.Seen) extras.muted else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
