package com.onefera.app.feature.story

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.component.SelectChip
import com.onefera.app.data.media.MediaProcessor
import com.onefera.app.data.model.StoryAudience
import com.onefera.app.data.model.StoryOptions
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.social.SocialRepository
import com.onefera.app.data.social.StoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

data class StoryEditorUiState(
    val photo: Bitmap? = null,
    val loadingPhoto: Boolean = false,
    val publishing: Boolean = false,
    val progress: Float = 0f,
    val published: Boolean = false,
    val error: String? = null,
    val mentionResults: List<UserSummary> = emptyList(),
)

@HiltViewModel
class StoryEditorViewModel @Inject constructor(
    private val stories: StoryRepository,
    private val social: SocialRepository,
    private val media: MediaProcessor,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(StoryEditorUiState())
    val state: StateFlow<StoryEditorUiState> = _state.asStateFlow()

    /** Decodes the picked photo (rotated upright, at most 1920 px) for editing. */
    fun load(uri: Uri) {
        if (_state.value.photo != null || _state.value.loadingPhoto) return
        _state.update { it.copy(loadingPhoto = true) }
        viewModelScope.launch {
            runCatching {
                val prepared = media.prepareImage(uri, maxSide = StoryRenderer.HEIGHT)
                try {
                    withContext(Dispatchers.IO) { BitmapFactory.decodeFile(prepared.file.path) } ?: error("decode")
                } finally {
                    media.cleanUp(prepared)
                }
            }.onSuccess { bmp -> _state.update { it.copy(photo = bmp, loadingPhoto = false) } }
                .onFailure { _state.update { it.copy(loadingPhoto = false, error = "Couldn't open that photo.") } }
        }
    }

    fun searchMentions(query: String) = viewModelScope.launch {
        val results = if (query.isBlank()) emptyList() else runCatching { social.searchUsers(query) }.getOrDefault(emptyList())
        _state.update { it.copy(mentionResults = results.take(8)) }
    }

    fun publish(filter: StoryFilter, strokes: List<StoryStroke>, overlays: List<StoryOverlay>, options: StoryOptions) {
        val photo = _state.value.photo ?: return
        if (_state.value.publishing) return
        _state.update { it.copy(publishing = true, progress = 0.02f, error = null) }
        viewModelScope.launch {
            val out = File(context.cacheDir, "story-${System.currentTimeMillis()}.jpg")
            runCatching { withContext(Dispatchers.Default) { StoryRenderer.render(photo, filter, strokes, overlays, out) } }
                .onFailure { _state.update { it.copy(publishing = false, error = "Couldn't prepare your story.") }; return@launch }
            stories.addStory(Uri.fromFile(out), options) { p -> _state.update { it.copy(progress = 0.1f + 0.9f * p) } }
                .onSuccess { _state.update { it.copy(publishing = false, published = true) } }
                .onFailure { e -> _state.update { it.copy(publishing = false, error = e.message ?: "Couldn't post your story") } }
            out.delete()
        }
    }

    fun errorShown() = _state.update { it.copy(error = null) }
}

private enum class Tool { None, Draw }

private val Palette = listOf(Color.White, Color.Black, Color(0xFFFF3B6B), Color(0xFFFF9F1C), Color(0xFFFFE14D), Color(0xFF2EE59D), Color(0xFF3DA5FF), Color(0xFF8B5CF6))
private val Stickers = listOf("🔥", "✨", "💯", "😂", "😍", "🥹", "😎", "🤯", "🙌", "👏", "💅", "🎉", "❤️", "💜", "⭐", "🌈", "☕", "🍕", "🎧", "📸", "🏆", "🚀", "🌙", "🌸", "👀", "💀", "🫶", "🤝", "📍", "🛍️")

/**
 * Story editor: photo filters, text, emoji stickers, @mentions, freehand drawing, audience and
 * reply settings. Music isn't offered: adding songs needs a licensed music catalogue.
 */
@Composable
fun StoryEditorScreen(onClose: () -> Unit, viewModel: StoryEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picked by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) onClose() else viewModel.load(uri)
    }
    LaunchedEffect(Unit) {
        if (!picked && state.photo == null) {
            picked = true
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    LaunchedEffect(state.published) { if (state.published) onClose() }

    var filter by rememberSaveable { mutableStateOf(StoryFilter.Original) }
    val strokes = remember { mutableStateListOf<StoryStroke>() }
    val overlays = remember { mutableStateListOf<StoryOverlay>() }
    var tool by remember { mutableStateOf(Tool.None) }
    var brush by remember { mutableStateOf(Color.White) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var textEditor by remember { mutableStateOf<StoryOverlay?>(null) }
    var showStickers by remember { mutableStateOf(false) }
    var showMentions by remember { mutableStateOf(false) }
    var audience by rememberSaveable { mutableStateOf(StoryAudience.PUBLIC) }
    var allowReplies by rememberSaveable { mutableStateOf(true) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val edited = strokes.isNotEmpty() || overlays.isNotEmpty() || filter != StoryFilter.Original
    BackHandler(enabled = !state.publishing) { if (edited) confirmDiscard = true else onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val photo = state.photo
        if (photo == null) {
            if (state.loadingPhoto) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            state.error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center)) }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // Top bar
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (edited) confirmDiscard = true else onClose() }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Discard", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    if (tool == Tool.Draw) {
                        IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) {
                            Icon(painterResource(R.drawable.ic_undo), contentDescription = "Undo", tint = Color.White)
                        }
                        TextButton(onClick = { tool = Tool.None }) { Text("Done", color = Color.White) }
                    } else {
                        ToolButton(R.drawable.ic_title, "Add text") { textEditor = StoryOverlay(System.nanoTime(), OverlayKind.Text, "", Color.White.toArgb()) }
                        ToolButton(R.drawable.ic_add_reaction, "Add sticker") { showStickers = true }
                        ToolButton(R.drawable.ic_alternate_email, "Mention someone") { showMentions = true }
                        ToolButton(R.drawable.ic_brush, "Draw") { tool = Tool.Draw; selected = null }
                    }
                }
                // Canvas
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val frameWidth = minOf(maxWidth, maxHeight * 9f / 16f)
                    Box(
                        Modifier.width(frameWidth).aspectRatio(9f / 16f).clip(RoundedCornerShape(18.dp)),
                    ) {
                        StoryCanvas(
                            photo = photo,
                            filter = filter,
                            strokes = strokes,
                            overlays = overlays,
                            drawing = tool == Tool.Draw,
                            brush = brush,
                            selected = selected,
                            onSelect = { selected = it },
                            onStroke = { strokes += it },
                            onMove = { id, transform -> overlays.replaceAll { if (it.id == id) transform(it) else it } },
                            onEditText = { o -> if (o.kind == OverlayKind.Text) textEditor = o },
                        )
                        selected?.let { id ->
                            TextButton(
                                onClick = {
                                    overlays.removeAll { it.id == id }
                                    selected = null
                                },
                                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                            ) { Text("Remove", color = Color.White) }
                        }
                    }
                }
                // Bottom controls
                if (tool == Tool.Draw) {
                    ColorRow(brush) { brush = it }
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(StoryFilter.entries) { f -> SelectChip(f.label, selected = f == filter, onClick = { filter = f }) }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        SelectChip("Everyone", selected = audience == StoryAudience.PUBLIC, onClick = { audience = StoryAudience.PUBLIC })
                        Spacer(Modifier.width(8.dp))
                        SelectChip("Followers", selected = audience == StoryAudience.FOLLOWERS, onClick = { audience = StoryAudience.FOLLOWERS })
                        Spacer(Modifier.weight(1f))
                        Text("Replies", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(6.dp))
                        Switch(checked = allowReplies, onCheckedChange = { allowReplies = it })
                    }
                    if (state.publishing) {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(16.dp).height(4.dp))
                    }
                    GradientButton(
                        text = if (state.publishing) "Sharing…" else "Share to story",
                        onClick = {
                            val mentions = overlays.filter { it.kind == OverlayKind.Mention && it.uid != null }
                                .map { o -> UserSummary(uid = o.uid!!, username = o.text.removePrefix("@")) }
                            viewModel.publish(filter, strokes.toList(), overlays.toList(), StoryOptions(audience, mentions, allowReplies))
                        },
                        enabled = !state.publishing,
                        loading = state.publishing,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }

    textEditor?.let { draft ->
        TextOverlayDialog(
            initial = draft,
            onDone = { text, color ->
                if (text.isNotBlank()) {
                    val existing = overlays.indexOfFirst { it.id == draft.id }
                    val updated = draft.copy(text = text.trim().take(120), color = color.toArgb())
                    if (existing >= 0) overlays[existing] = updated else overlays += updated
                } else {
                    overlays.removeAll { it.id == draft.id }
                }
                textEditor = null
            },
            onDismiss = { textEditor = null },
        )
    }
    if (showStickers) {
        AlertDialog(
            onDismissRequest = { showStickers = false },
            title = { Text("Stickers") },
            text = {
                LazyVerticalGrid(GridCells.Fixed(6), modifier = Modifier.heightIn(max = 320.dp)) {
                    items(Stickers) { emoji ->
                        Text(
                            emoji,
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                                overlays += StoryOverlay(System.nanoTime(), OverlayKind.Sticker, emoji, Color.White.toArgb(), y = 0.35f)
                                showStickers = false
                            }.padding(6.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStickers = false }) { Text("Close") } },
        )
    }
    if (showMentions) {
        var query by remember { mutableStateOf("") }
        LaunchedEffect(query) { viewModel.searchMentions(query) }
        AlertDialog(
            onDismissRequest = { showMentions = false },
            title = { Text("Mention someone") },
            text = {
                Column {
                    OneFeraTextField(value = query, onValueChange = { query = it.take(30) }, label = "Search people", leadingIcon = R.drawable.ic_search)
                    Spacer(Modifier.height(8.dp))
                    state.mentionResults.forEach { user ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                if (overlays.none { it.uid == user.uid }) {
                                    overlays += StoryOverlay(System.nanoTime(), OverlayKind.Mention, "@${user.username}", Color.Black.toArgb(), y = 0.6f, uid = user.uid)
                                }
                                showMentions = false
                            }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(user.avatarUrl, user.displayName, size = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(user.displayName, style = MaterialTheme.typography.titleSmall)
                                Text("@${user.username}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMentions = false }) { Text("Close") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this story?") },
            text = { Text("Your edits will be lost.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun StoryCanvas(
    photo: Bitmap,
    filter: StoryFilter,
    strokes: List<StoryStroke>,
    overlays: List<StoryOverlay>,
    drawing: Boolean,
    brush: Color,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    onStroke: (StoryStroke) -> Unit,
    onMove: (Long, (StoryOverlay) -> StoryOverlay) -> Unit,
    onEditText: (StoryOverlay) -> Unit,
) {
    val image = remember(photo) { photo.asImageBitmap() }
    val current = remember { mutableStateListOf<Offset>() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        Image(
            bitmap = image,
            contentDescription = "Story photo",
            contentScale = ContentScale.Crop,
            colorFilter = filter.matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
            modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onSelect(null) } },
        )
        Canvas(
            Modifier.fillMaxSize().then(
                if (drawing) {
                    Modifier.pointerInput(brush) {
                        detectDragGestures(
                            onDragStart = { current.clear(); current += it },
                            onDragEnd = {
                                onStroke(StoryStroke(current.map { it.x / size.width to it.y / size.height }, brush.toArgb()))
                                current.clear()
                            },
                            onDragCancel = { current.clear() },
                        ) { change, _ -> current += change.position }
                    }
                } else {
                    Modifier
                },
            ),
        ) {
            fun pathOf(points: List<Offset>) = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
            strokes.forEach { s ->
                drawPath(
                    pathOf(s.points.map { (x, y) -> Offset(x * size.width, y * size.height) }),
                    Color(s.color),
                    style = Stroke(width = s.widthFraction * size.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            if (current.size > 1) {
                drawPath(pathOf(current), brush, style = Stroke(width = 0.012f * size.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (!drawing) {
            overlays.forEach { o ->
                OverlayView(o, widthPx, heightPx, isSelected = o.id == selected, onSelect = { onSelect(o.id) }, onMove = onMove, onEdit = { onEditText(o) })
            }
        } else {
            overlays.forEach { o -> OverlayView(o, widthPx, heightPx, isSelected = false, onSelect = {}, onMove = { _, _ -> }, onEdit = {}) }
        }
    }
}

@Composable
private fun OverlayView(
    o: StoryOverlay,
    widthPx: Float,
    heightPx: Float,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onMove: (Long, (StoryOverlay) -> StoryOverlay) -> Unit,
    onEdit: () -> Unit,
) {
    val density = LocalDensity.current
    val fontPx = o.baseSizeFraction * widthPx
    val fontSize = with(density) { fontPx.toSp() }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Box(
        Modifier
            .offset { IntOffset((o.x * widthPx - size.width / 2f).roundToInt(), (o.y * heightPx - size.height / 2f).roundToInt()) }
            .graphicsLayer { scaleX = o.scale; scaleY = o.scale }
            .onSizeChanged { size = it }
            .pointerInput(o.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onSelect()
                    onMove(o.id) {
                        it.copy(
                            x = (it.x + pan.x * it.scale / widthPx).coerceIn(0f, 1f),
                            y = (it.y + pan.y * it.scale / heightPx).coerceIn(0f, 1f),
                            scale = (it.scale * zoom).coerceIn(0.4f, 4f),
                        )
                    }
                }
            }
            .pointerInput(o.id) { detectTapGestures(onTap = { onSelect() }, onDoubleTap = { onEdit() }) }
            .then(if (isSelected) Modifier.border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(8.dp)) else Modifier),
    ) {
        val style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize, fontWeight = FontWeight.Bold)
        when (o.kind) {
            OverlayKind.Mention -> Text(
                o.text,
                style = style,
                color = Color(o.color),
                modifier = Modifier.clip(CircleShape).background(Color.White).padding(horizontal = with(density) { (fontPx * 0.5f).toDp() }, vertical = with(density) { (fontPx * 0.3f).toDp() }),
            )
            OverlayKind.Text -> Text(
                o.text,
                style = style.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, fontPx * 0.04f), fontPx * 0.12f)),
                color = Color(o.color),
                textAlign = TextAlign.Center,
            )
            OverlayKind.Sticker -> Text(o.text, style = style)
        }
    }
}

@Composable
private fun TextOverlayDialog(initial: StoryOverlay, onDone: (String, Color) -> Unit, onDismiss: () -> Unit) {
    var text by remember(initial.id) { mutableStateOf(initial.text) }
    var color by remember(initial.id) { mutableStateOf(Color(initial.color)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.text.isEmpty()) "Add text" else "Edit text") },
        text = {
            Column {
                OneFeraTextField(value = text, onValueChange = { text = it.take(120) }, label = "Say something", singleLine = false)
                Spacer(Modifier.height(10.dp))
                ColorRow(color) { color = it }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text, color) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ColorRow(selected: Color, onPick: (Color) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(Palette) { c ->
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(c)
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) Color(0xFF8B5CF6) else Color.Gray, CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}

@Composable
private fun ToolButton(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(horizontal = 2.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))) {
        Icon(painterResource(icon), contentDescription = description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
