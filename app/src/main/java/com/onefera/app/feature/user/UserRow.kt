package com.onefera.app.feature.user

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.model.UserSummary

/** One person in a list: avatar, name, @handle and an optional trailing control. */
@Composable
fun UserRow(
    user: UserSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val extras = OneFeraTheme.extras
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user.avatarUrl, user.displayName, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(user.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (user.verified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(painterResource(R.drawable.ic_badge_filled), contentDescription = "Verified", modifier = Modifier.size(14.dp).gradientTint(extras.gradientBrush()))
                }
                if (user.isPrivate) {
                    Spacer(Modifier.width(4.dp))
                    Icon(painterResource(R.drawable.ic_lock), contentDescription = "Private account", tint = extras.muted, modifier = Modifier.size(13.dp))
                }
            }
            Text(subtitle ?: "@${user.username}", style = MaterialTheme.typography.bodySmall, color = extras.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}
