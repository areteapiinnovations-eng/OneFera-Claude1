package com.onefera.app.feature.settings

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onefera.app.BuildConfig
import com.onefera.app.R
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.core.common.AppLinks
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.GradientTag
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.component.SelectChip
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.ThemeMode
import com.onefera.app.core.designsystem.theme.ThemeSkin

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val extras = OneFeraTheme.extras
    val context = LocalContext.current
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    if (confirmDelete) {
        DeleteAccountDialog(
            busy = viewModel.deleting,
            error = viewModel.deleteError,
            onConfirm = viewModel::deleteAccount,
            onDismiss = {
                confirmDelete = false
                viewModel.clearDeleteError()
            },
        )
    }

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.45f) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "Settings", onBack = onBack)
            LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { SectionLabel("THEME SKIN") }
                items(ThemeSkin.entries, key = { it.name }) { skin ->
                    SkinRow(skin = skin, active = state.settings.skin == skin, onClick = { viewModel.setSkin(skin) })
                }
                item { SectionLabel("APPEARANCE") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            SelectChip(text = mode.label, selected = state.settings.themeMode == mode, onClick = { viewModel.setThemeMode(mode) })
                        }
                    }
                }
                item { SectionLabel("PREFERENCES") }
                item {
                    ToggleRow(
                        icon = R.drawable.ic_bell,
                        title = "Push notifications",
                        subtitle = "Likes, follows, DMs and order updates",
                        checked = state.settings.pushEnabled,
                        onCheckedChange = viewModel::setPush,
                    )
                }
                item {
                    ToggleRow(
                        icon = R.drawable.ic_shield,
                        title = "Private account",
                        subtitle = "Only approved followers see your posts",
                        checked = state.isPrivate,
                        enabled = state.canEditPrivacy,
                        onCheckedChange = viewModel::setPrivate,
                    )
                }
                item { InfoRow(icon = R.drawable.ic_language, title = "Language", subtitle = "English (India)") }
                item { SectionLabel("ACCOUNT") }
                item {
                    InfoRow(
                        icon = R.drawable.ic_shield,
                        title = "Blocked accounts",
                        subtitle = "People you've blocked",
                        onClick = LocalAppActions.current.openBlockedAccounts,
                    )
                }
                item {
                    InfoRow(
                        icon = R.drawable.ic_delete,
                        title = "Delete account",
                        subtitle = "Permanently remove your profile, posts and data",
                        onClick = { confirmDelete = true },
                    )
                }
                item { SectionLabel("ABOUT") }
                item { InfoRow(icon = R.drawable.ic_info, title = "Community guidelines", onClick = { open(AppLinks.GUIDELINES) }) }
                item { InfoRow(icon = R.drawable.ic_shield, title = "Privacy policy", onClick = { open(AppLinks.PRIVACY) }) }
                item { InfoRow(icon = R.drawable.ic_info, title = "Terms of service", onClick = { open(AppLinks.TERMS) }) }
                item {
                    Text(
                        "OneFera · One Future Era · v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = extras.muted,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = OneFeraTheme.extras.muted,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsRowContainer(onClick: (() -> Unit)?, highlighted: Boolean = false, content: @Composable () -> Unit) {
    val extras = OneFeraTheme.extras
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(extras.glass)
            .then(if (highlighted) Modifier.border(1.5.dp, extras.gradientBrush(), shape) else Modifier.border(1.dp, extras.glassBorder, shape))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) { content() }
}

@Composable
private fun SkinRow(skin: ThemeSkin, active: Boolean, onClick: () -> Unit) {
    SettingsRowContainer(onClick = onClick, highlighted = active) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(skin.gradient)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(skin.title, style = MaterialTheme.typography.titleMedium)
                Text(skin.subtitle, style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted)
            }
            if (active) GradientTag("ACTIVE")
        }
    }
}

@Composable
private fun ToggleRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SettingsRowContainer(onClick = if (enabled) ({ onCheckedChange(!checked) }) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun InfoRow(@DrawableRes icon: Int, title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    SettingsRowContainer(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted)
            }
            if (onClick != null) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = OneFeraTheme.extras.muted)
        }
    }
}

@Composable
private fun RowIcon(@DrawableRes icon: Int) {
    Icon(
        painterResource(icon),
        contentDescription = null,
        modifier = Modifier.size(22.dp).gradientTint(OneFeraTheme.extras.gradientBrush()),
    )
    Spacer(Modifier.width(14.dp))
}

@Composable
private fun DeleteAccountDialog(busy: Boolean, error: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Delete your account?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This permanently deletes your profile, posts, reels, stories, listings, cart, coupons and Aura. It can't be undone. Orders are kept anonymised for tax and refund records.")
                com.onefera.app.core.designsystem.component.PasswordTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Confirm your password",
                    error = error,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty() && !busy) {
                Text(if (busy) "Deleting…" else "Delete forever", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss, enabled = !busy) { Text("Keep my account") } },
    )
}
