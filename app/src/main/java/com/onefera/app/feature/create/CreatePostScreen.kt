package com.onefera.app.feature.create

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil3.compose.AsyncImage
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.media.MediaProcessor
import com.onefera.app.data.model.PostDraft
import com.onefera.app.data.model.extractHashtags
import com.onefera.app.data.social.PostRepository
import com.onefera.app.feature.auth.FormError
import com.onefera.app.navigation.CreatePostRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.onefera.app.data.model.Post
import com.onefera.app.data.model.Product
import com.onefera.app.data.model.ProductSummary
import com.onefera.app.data.shop.ShopRepository
import com.onefera.app.feature.shop.ProductPickerSheet
import com.onefera.app.feature.shop.TagProductsCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import javax.inject.Inject

data class CreatePostUiState(
    val media: List<Uri> = emptyList(),
    val isVideo: Boolean = false,
    val asReel: Boolean = false,
    val caption: String = "",
    val location: String = "",
    val publishing: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
    val published: Boolean = false,
    val products: List<ProductSummary> = emptyList(),
) {
    val canPublish: Boolean get() = media.isNotEmpty() && !publishing
}

@HiltViewModel
class CreatePostViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val posts: PostRepository,
    shop: ShopRepository,
) : ViewModel() {
    /** Catalogue for the "Tag products" picker, loaded only while the picker is open. */
    val catalogue: StateFlow<List<Product>> = shop.catalogue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val reelMode = savedStateHandle.toRoute<CreatePostRoute>().reel
    private val _state = MutableStateFlow(CreatePostUiState(asReel = reelMode))
    val state: StateFlow<CreatePostUiState> = _state.asStateFlow()
    val isReelMode: Boolean get() = reelMode

    fun onPhotosPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(media = uris.take(MediaProcessor.MAX_IMAGES), isVideo = false, asReel = false, error = null) }
    }

    fun onVideoPicked(uri: Uri?) {
        if (uri == null) return
        _state.update { it.copy(media = listOf(uri), isVideo = true, asReel = it.asReel || reelMode, error = null) }
    }

    fun removeAt(index: Int) = _state.update { s ->
        val next = s.media.toMutableList().also { it.removeAt(index) }
        s.copy(media = next, isVideo = s.isVideo && next.isNotEmpty())
    }

    fun onCaptionChange(v: String) = _state.update { it.copy(caption = v.take(2200), error = null) }
    fun onLocationChange(v: String) = _state.update { it.copy(location = v.take(60)) }
    fun onReelChange(v: Boolean) = _state.update { it.copy(asReel = v) }
    fun onProductsChange(products: List<ProductSummary>) = _state.update { it.copy(products = products.take(Post.MAX_PRODUCT_TAGS)) }

    fun publish() {
        val s = _state.value
        if (!s.canPublish) return
        _state.update { it.copy(publishing = true, progress = 0f, error = null) }
        viewModelScope.launch {
            posts.createPost(PostDraft(s.caption, s.location, s.media, s.isVideo, s.asReel, s.products)) { p ->
                _state.update { it.copy(progress = p) }
            }
                .onSuccess { _state.update { it.copy(publishing = false, published = true) } }
                .onFailure { e -> _state.update { it.copy(publishing = false, error = e.message) } }
        }
    }
}

@Composable
fun CreatePostScreen(onBack: () -> Unit, onPublished: () -> Unit, viewModel: CreatePostViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val extras = OneFeraTheme.extras
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MediaProcessor.MAX_IMAGES)) {
        viewModel.onPhotosPicked(it)
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { viewModel.onVideoPicked(it) }
    LaunchedEffect(state.published) { if (state.published) onPublished() }
    var showProductPicker by rememberSaveable { mutableStateOf(false) }

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.5f) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = if (viewModel.isReelMode) "New reel 🎬" else "New post ✨", onBack = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state.media.isEmpty()) {
                    PickerCard(
                        reel = viewModel.isReelMode,
                        onPhotos = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onVideo = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        itemsIndexed(state.media) { index, uri ->
                            Box(Modifier.width(if (state.isVideo) 170.dp else 220.dp).aspectRatio(if (state.isVideo) 0.6f else 0.8f)) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = "Selected media ${index + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                )
                                if (state.isVideo) {
                                    Icon(painterResource(R.drawable.ic_video), contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(40.dp))
                                }
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp).clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = !state.publishing) { viewModel.removeAt(index) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                OneFeraTextField(
                    value = state.caption,
                    onValueChange = viewModel::onCaptionChange,
                    label = "Write a caption… add #tags",
                    singleLine = false,
                    minLines = 3,
                    maxLength = 2200,
                    imeAction = ImeAction.Default,
                )
                val tags = extractHashtags(state.caption)
                if (tags.isNotEmpty()) {
                    Text(tags.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelLarge, modifier = Modifier.gradientTint(extras.horizontalGradient()))
                }
                OneFeraTextField(
                    value = state.location,
                    onValueChange = viewModel::onLocationChange,
                    label = "Add location",
                    leadingIcon = R.drawable.ic_location,
                    imeAction = ImeAction.Done,
                )
                TagProductsCard(
                    products = state.products,
                    onAdd = { showProductPicker = true },
                    onRemove = { removed -> viewModel.onProductsChange(state.products.filterNot { it.id == removed.id }) },
                )
                if (state.isVideo) {
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Share as a Reel", style = MaterialTheme.typography.titleMedium)
                                Text("Shows up full-screen in the Reels tab", style = MaterialTheme.typography.bodySmall, color = extras.muted)
                            }
                            Switch(
                                checked = state.asReel,
                                onCheckedChange = viewModel::onReelChange,
                                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
                FormError(state.error)
            }
            Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                if (state.publishing) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                GradientButton(
                    text = if (state.publishing) "Posting ${(state.progress * 100).toInt()}%" else "Share",
                    loading = false,
                    enabled = state.canPublish,
                    onClick = viewModel::publish,
                )
            }
        }
    }

    if (showProductPicker) {
        val catalogue by viewModel.catalogue.collectAsStateWithLifecycle()
        ProductPickerSheet(
            catalogue = catalogue,
            selected = state.products,
            max = Post.MAX_PRODUCT_TAGS,
            onDone = { picked ->
                viewModel.onProductsChange(picked)
                showProductPicker = false
            },
            onDismiss = { showProductPicker = false },
        )
    }
}

@Composable
private fun PickerCard(reel: Boolean, onPhotos: () -> Unit, onVideo: () -> Unit) {
    val extras = OneFeraTheme.extras
    GlassCard(Modifier.fillMaxWidth()) {
        Text(if (reel) "Pick a video for your reel" else "What are you sharing?", style = MaterialTheme.typography.titleLarge)
        Text(
            if (reel) "Vertical clips up to 90 seconds look best." else "Up to 10 photos, or one video up to 90 seconds.",
            style = MaterialTheme.typography.bodyMedium,
            color = extras.muted,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!reel) PickerTile(R.drawable.ic_photo, "Photos", Modifier.weight(1f), onPhotos)
            PickerTile(R.drawable.ic_video, "Video", Modifier.weight(1f), onVideo)
        }
    }
}

@Composable
private fun PickerTile(icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    val extras = OneFeraTheme.extras
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .border(1.5.dp, extras.gradientBrush(), shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(40.dp).gradientTint(extras.gradientBrush()))
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
