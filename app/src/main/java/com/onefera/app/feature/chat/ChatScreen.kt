package com.onefera.app.feature.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.CircleIconButton
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.component.SelectChip
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.model.AttachmentType
import com.onefera.app.data.model.Message
import com.onefera.app.data.model.QuickReplies
import com.onefera.app.data.model.presenceLabel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun ChatScreen(onBack: () -> Unit, viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val snackbar = remember { SnackbarHostState() }
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.attach(uri, AttachmentType.Image, "Photo")
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attach(uri, AttachmentType.File, uri.lastPathSegment?.substringAfterLast('/') ?: "File")
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onVisible(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.onVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onVisible(false)
        }
    }
    LaunchedEffect(viewModel) { viewModel.toasts.collect { snackbar.showSnackbar(it) } }
    // Jump to the newest message when one arrives.
    LaunchedEffect(state.messages.firstOrNull()?.id) { if (state.messages.isNotEmpty()) listState.animateScrollToItem(0) }

    val conversation = state.conversation
    val other = conversation?.other(state.myUid)
    val otherTyping = other != null && conversation?.isTyping(other.uid, state.now) == true
    val status = when {
        otherTyping -> "typing…"
        else -> presenceLabel(state.otherLastActive, state.now) ?: other?.username?.let { "@$it" }.orEmpty()
    }
    val otherReadAt = other?.let { conversation?.lastReadAt?.get(it.uid) } ?: 0L
    val lastMineId = state.messages.firstOrNull { it.senderId == state.myUid }?.id

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.4f) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // Header
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(icon = R.drawable.ic_arrow_back, contentDescription = "Back", onClick = onBack)
                Spacer(Modifier.width(10.dp))
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(enabled = other != null) { other?.let { actions.openUser(it.uid) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Avatar(other?.avatarUrl, other?.displayName ?: "", size = 42.dp)
                        if (status == "Active now" || otherTyping) OnlineDot(Modifier.align(Alignment.BottomEnd))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(other?.displayName.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            status,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (status == "Active now" || otherTyping) OneFeraTheme.extras.success else OneFeraTheme.extras.muted,
                        )
                    }
                }
                IconButton(onClick = { viewModel.comingSoon("Voice") }) { Icon(painterResource(R.drawable.ic_call), contentDescription = "Voice call") }
                IconButton(onClick = { viewModel.comingSoon("Video") }) { Icon(painterResource(R.drawable.ic_video), contentDescription = "Video call") }
            }
            HorizontalDivider(color = OneFeraTheme.extras.glassBorder)

            // Messages, newest at the bottom.
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (otherTyping) item(key = "typing") { TypingBubble() }
                itemsIndexed(state.messages, key = { _, m -> m.id }) { index, message ->
                    val mine = message.senderId == state.myUid
                    Column {
                        val older = state.messages.getOrNull(index + 1)
                        if (older == null || !sameDay(older.createdAt, message.createdAt)) DaySeparator(message.createdAt)
                        MessageBubble(
                            message = message,
                            mine = mine,
                            replyName = message.replyTo?.let { if (it.senderId == state.myUid) "You" else other?.displayName.orEmpty() },
                            onReply = { viewModel.reply(message) },
                            onUnsend = { viewModel.unsend(message) },
                            onImage = { viewerUrl = it },
                        )
                        if (mine && message.id == lastMineId && otherReadAt >= message.createdAt) {
                            Text("Seen ✓", style = MaterialTheme.typography.labelSmall, color = OneFeraTheme.extras.muted, modifier = Modifier.align(Alignment.End).padding(end = 6.dp))
                        }
                    }
                }
                if (state.messages.isEmpty() && !state.loading && other != null) {
                    item(key = "hello") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(other.avatarUrl, other.displayName, size = 88.dp, ring = true)
                            Spacer(Modifier.height(10.dp))
                            Text(other.displayName, style = MaterialTheme.typography.titleLarge)
                            Text("Say hi 👋 and start the convo", color = OneFeraTheme.extras.muted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // Composer
            Column(Modifier.navigationBarsPadding()) {
                state.uploadProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(3.dp)) }
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(QuickReplies) { reply -> SelectChip(reply, selected = false, onClick = { viewModel.send(reply) }) }
                }
                state.replyTo?.let { reply ->
                    ComposerBanner(
                        title = "Replying to ${if (reply.senderId == state.myUid) "yourself" else other?.displayName.orEmpty()}",
                        body = reply.preview,
                        onClose = viewModel::cancelReply,
                    )
                }
                state.attachment?.let { att ->
                    ComposerBanner(title = if (att.type == AttachmentType.Image) "📷 Photo attached" else "📎 File attached", body = att.label, onClose = viewModel::removeAttachment)
                }
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        CircleIconButton(icon = R.drawable.ic_add, contentDescription = "Attach", onClick = { attachMenu = true }, size = 44.dp)
                        DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Photo") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_image), null) },
                                onClick = {
                                    attachMenu = false
                                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("File") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_attach), null) },
                                onClick = {
                                    attachMenu = false
                                    pickFile.launch(arrayOf("*/*"))
                                },
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OneFeraTextField(
                        value = state.draft,
                        onValueChange = viewModel::onDraftChange,
                        label = "Type a message…",
                        singleLine = false,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Default,
                        modifier = Modifier.weight(1f).heightIn(max = 150.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    CircleIconButton(
                        icon = R.drawable.ic_send,
                        contentDescription = "Send",
                        onClick = { viewModel.send() },
                        filled = state.draft.isNotBlank() || state.attachment != null,
                        size = 48.dp,
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp))
    }

    viewerUrl?.let { ImageViewer(url = it, onDismiss = { viewerUrl = null }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    mine: Boolean,
    replyName: String?,
    onReply: () -> Unit,
    onUnsend: () -> Unit,
    onImage: (String) -> Unit,
) {
    val extras = OneFeraTheme.extras
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    var dragX by remember { mutableFloatStateOf(0f) }
    val shownX by animateFloatAsState(dragX, label = "swipe")
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 22.dp,
        bottomStart = if (mine) 22.dp else 6.dp,
        bottomEnd = if (mine) 6.dp else 22.dp,
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        // Reply hint revealed while swiping.
        if (shownX > 8f) {
            Icon(
                painterResource(R.drawable.ic_reply),
                contentDescription = null,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp).size(22.dp).gradientTint(extras.gradientBrush()),
            )
        }
        Column(
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            modifier = Modifier
                .offset { IntOffset(shownX.roundToInt(), 0) }
                .widthIn(max = 300.dp)
                .pointerInput(message.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (dragX > threshold && !message.unsent) onReply()
                            dragX = 0f
                        },
                        onDragCancel = { dragX = 0f },
                    ) { _, delta -> dragX = (dragX + delta).coerceIn(0f, threshold * 1.4f) }
                },
        ) {
            Box {
                Column(
                    Modifier
                        .clip(shape)
                        .then(
                            if (mine && !message.unsent) Modifier.background(extras.gradientBrush())
                            else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).border(1.dp, extras.glassBorder, shape),
                        )
                        .combinedClickable(onClick = {}, onLongClick = { if (!message.unsent) menu = true })
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    val textColor = if (mine && !message.unsent) extras.onGradient else MaterialTheme.colorScheme.onSurface
                    message.replyTo?.let { r ->
                        Column(
                            Modifier
                                .padding(bottom = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.18f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(replyName.orEmpty(), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = textColor)
                            Text(r.text, style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (message.unsent) {
                        Text("Message unsent", style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = extras.muted)
                    } else {
                        message.attachment?.let { att ->
                            if (att.type == AttachmentType.Image) {
                                AsyncImage(
                                    model = att.url,
                                    contentDescription = "Photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(220.dp)
                                        .aspectRatio(att.aspectRatio.coerceIn(0.6f, 1.6f))
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { onImage(att.url) },
                                )
                            } else {
                                Row(
                                    Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, att.url.toUri()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                        } catch (_: ActivityNotFoundException) {
                                        }
                                    }.padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(painterResource(R.drawable.ic_attach), contentDescription = null, tint = textColor, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(att.name, color = textColor, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(formatSize(att.sizeBytes), color = textColor.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            if (message.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                        }
                        if (message.text.isNotBlank()) {
                            Text(message.text, color = textColor, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Reply") }, leadingIcon = { Icon(painterResource(R.drawable.ic_reply), null) }, onClick = { menu = false; onReply() })
                    if (message.text.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("Copy") },
                            onClick = {
                                menu = false
                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", message.text))) }
                            },
                        )
                    }
                    if (mine) {
                        DropdownMenuItem(text = { Text("Unsend", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onUnsend() })
                    }
                }
            }
            Text(
                timeOf(message.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = extras.muted,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun TypingBubble() {
    val extras = OneFeraTheme.extras
    Row(
        Modifier.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { Box(Modifier.size(8.dp).clip(CircleShape).background(extras.gradientBrush())) }
    }
}

@Composable
private fun DaySeparator(millis: Long) {
    val label = when (val day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()) {
        LocalDate.now() -> "Today"
        LocalDate.now().minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = OneFeraTheme.extras.muted,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    )
}

@Composable
private fun ComposerBanner(title: String, body: String, onClose: () -> Unit) {
    val extras = OneFeraTheme.extras
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)).background(extras.glass).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(32.dp).clip(CircleShape).background(extras.gradientBrush()))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = extras.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Cancel", modifier = Modifier.size(18.dp)) }
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}

private fun timeOf(millis: Long): String =
    DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    bytes > 0 -> "$bytes B"
    else -> ""
}
