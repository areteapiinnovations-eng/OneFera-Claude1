package com.onefera.app.feature.post

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.onefera.app.R
import com.onefera.app.data.model.Post
import com.onefera.app.data.model.PostType

/**
 * A 3-column grid of post covers, emitted as rows into an enclosing LazyColumn (a lazy grid
 * can't be nested inside a vertically scrolling list).
 */
fun LazyListScope.postGridItems(posts: List<Post>, onOpen: (Post) -> Unit, keyPrefix: String = "grid") {
    val rows = posts.chunked(3)
    items(rows.size, key = { "$keyPrefix-${rows[it].first().id}" }) { index ->
        Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            rows[index].forEach { post -> PostTile(post, Modifier.weight(1f)) { onOpen(post) } }
            repeat(3 - rows[index].size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
fun PostTile(post: Post, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .aspectRatio(if (post.type == PostType.Reel) 0.62f else 0.8f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = post.coverImageUrl,
            contentDescription = post.caption.take(60).ifBlank { "Post by ${post.author.displayName}" },
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        val badge = when {
            post.type == PostType.Reel -> R.drawable.ic_reels_filled
            post.isVideo -> R.drawable.ic_play
            post.media.size > 1 -> R.drawable.ic_photo
            else -> null
        }
        if (badge != null) {
            Icon(
                painterResource(badge),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
            )
        }
    }
}
