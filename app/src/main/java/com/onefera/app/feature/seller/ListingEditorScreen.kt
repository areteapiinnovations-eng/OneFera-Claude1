package com.onefera.app.feature.seller

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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import com.onefera.app.feature.auth.FormError
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.component.SelectChip
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.data.model.ProductCategory
import com.onefera.app.data.model.formatRupees
import com.onefera.app.data.seller.ListingDraft
import com.onefera.app.data.seller.SellerRepository
import com.onefera.app.navigation.ListingEditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Text fields are kept as strings so half-typed numbers don't jump around. */
data class ListingForm(
    val title: String = "",
    val brand: String = "",
    val description: String = "",
    val category: ProductCategory = ProductCategory.Fashion,
    val price: String = "",
    val mrp: String = "",
    val stock: String = "10",
    val variants: String = "",
    val highlights: String = "",
    val images: List<String> = emptyList(),
    val newImages: List<Uri> = emptyList(),
) {
    fun toDraft(id: String?) = ListingDraft(
        id = id,
        title = title,
        brand = brand,
        description = description,
        category = category,
        price = price.toIntOrNull() ?: 0,
        mrp = mrp.toIntOrNull() ?: 0,
        stock = stock.toIntOrNull() ?: -1,
        variants = variants.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        highlights = highlights.lines().map { it.trim() }.filter { it.isNotEmpty() },
        images = images,
        newImages = newImages,
    )
}

data class ListingEditorUiState(
    val loading: Boolean = false,
    val isNew: Boolean = true,
    val form: ListingForm = ListingForm(),
    val saving: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class ListingEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val seller: SellerRepository,
) : ViewModel() {
    private val productId = savedStateHandle.toRoute<ListingEditorRoute>().productId.ifEmpty { null }
    private val _state = MutableStateFlow(ListingEditorUiState(loading = productId != null, isNew = productId == null))
    val state: StateFlow<ListingEditorUiState> = _state.asStateFlow()

    init {
        if (productId != null) {
            viewModelScope.launch {
                val p = seller.listing(productId).first()
                _state.update {
                    if (p == null) {
                        it.copy(loading = false, error = "This listing no longer exists.")
                    } else {
                        it.copy(
                            loading = false,
                            form = ListingForm(
                                title = p.title,
                                brand = p.brand,
                                description = p.description,
                                category = p.category,
                                price = p.price.toString(),
                                mrp = if (p.mrp > p.price) p.mrp.toString() else "",
                                stock = p.stock.toString(),
                                variants = p.variants.joinToString(", "),
                                highlights = p.highlights.joinToString("\n"),
                                images = p.images,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun edit(transform: (ListingForm) -> ListingForm) = _state.update { it.copy(form = transform(it.form), error = null) }

    fun addPhotos(uris: List<Uri>) = edit { f ->
        val room = ListingDraft.MAX_PHOTOS - f.images.size - f.newImages.size
        f.copy(newImages = f.newImages + uris.take(room.coerceAtLeast(0)))
    }

    fun removePhoto(index: Int) = edit { f ->
        if (index < f.images.size) {
            f.copy(images = f.images.filterIndexed { i, _ -> i != index })
        } else {
            f.copy(newImages = f.newImages.filterIndexed { i, _ -> i != index - f.images.size })
        }
    }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val draft = s.form.toDraft(productId)
        draft.problem()?.let { problem ->
            _state.update { it.copy(error = problem) }
            return
        }
        _state.update { it.copy(saving = true, progress = 0f, error = null) }
        viewModelScope.launch {
            seller.saveListing(draft) { p -> _state.update { it.copy(progress = p) } }
                .onSuccess { _state.update { it.copy(saving = false, done = true) } }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.message) } }
        }
    }

    fun delete() {
        val id = productId ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            seller.deleteListing(id)
                .onSuccess { _state.update { it.copy(saving = false, done = true) } }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.message) } }
        }
    }
}

@Composable
fun ListingEditorScreen(onBack: () -> Unit, viewModel: ListingEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form = state.form
    val extras = OneFeraTheme.extras
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ListingDraft.MAX_PHOTOS)) {
        viewModel.addPhotos(it)
    }
    LaunchedEffect(state.done) { if (state.done) onBack() }

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.45f) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(if (state.isNew) "New listing ✨" else "Edit listing", onBack = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Photos (${form.images.size + form.newImages.size}/${ListingDraft.MAX_PHOTOS})", style = MaterialTheme.typography.titleSmall)
                val photos: List<Any> = form.images + form.newImages
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(photos) { index, model ->
                        Box(Modifier.size(110.dp)) {
                            AsyncImage(
                                model = model,
                                contentDescription = "Photo ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            )
                            if (index == 0) {
                                Text(
                                    "Cover",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = !state.saving) { viewModel.removePhoto(index) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove photo ${index + 1}", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    if (photos.size < ListingDraft.MAX_PHOTOS) {
                        item {
                            val shape = RoundedCornerShape(18.dp)
                            Column(
                                Modifier
                                    .size(110.dp)
                                    .clip(shape)
                                    .background(extras.glass)
                                    .border(1.dp, extras.glassBorder, shape)
                                    .clickable { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(painterResource(R.drawable.ic_add_box), contentDescription = null, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.height(4.dp))
                                Text("Add photos", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                OneFeraTextField(form.title, { v -> viewModel.edit { it.copy(title = v.take(80)) } }, "Product name")
                OneFeraTextField(form.brand, { v -> viewModel.edit { it.copy(brand = v.take(40)) } }, "Brand (optional)")
                Text("Category", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProductCategory.entries.forEach { c ->
                        SelectChip("${c.emoji} ${c.label}", selected = c == form.category, onClick = { viewModel.edit { it.copy(category = c) } })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OneFeraTextField(
                        form.price,
                        { v -> viewModel.edit { it.copy(price = v.filter(Char::isDigit).take(6)) } },
                        "Price ₹",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    OneFeraTextField(
                        form.mrp,
                        { v -> viewModel.edit { it.copy(mrp = v.filter(Char::isDigit).take(6)) } },
                        "MRP ₹ (optional)",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
                val price = form.price.toIntOrNull() ?: 0
                val mrp = form.mrp.toIntOrNull() ?: 0
                if (price > 0 && mrp > price) {
                    Text(
                        "Shoppers see ${formatRupees(price)} with ${((mrp - price) * 100f / mrp).toInt()}% off",
                        style = MaterialTheme.typography.labelMedium,
                        color = StatusColors.Success,
                    )
                }
                OneFeraTextField(
                    form.stock,
                    { v -> viewModel.edit { it.copy(stock = v.filter(Char::isDigit).take(4)) } },
                    "Units in stock",
                    keyboardType = KeyboardType.Number,
                )
                OneFeraTextField(
                    form.variants,
                    { v -> viewModel.edit { it.copy(variants = v.take(200)) } },
                    "Sizes / options, comma separated (optional)",
                    supportingText = "e.g. S, M, L or Black, White",
                )
                OneFeraTextField(
                    form.highlights,
                    { v -> viewModel.edit { it.copy(highlights = v.take(400)) } },
                    "Highlights, one per line (optional)",
                    singleLine = false,
                    minLines = 2,
                    imeAction = ImeAction.Default,
                )
                OneFeraTextField(
                    form.description,
                    { v -> viewModel.edit { it.copy(description = v.take(1000)) } },
                    "Description",
                    singleLine = false,
                    minLines = 3,
                    maxLength = 1000,
                    imeAction = ImeAction.Default,
                )
                GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                    Text("Shoppers pay securely through OneFera. You'll see each order in the Seller hub and get a push when someone buys.", style = MaterialTheme.typography.bodySmall, color = extras.muted)
                }
                FormError(state.error)
                if (!state.isNew) {
                    GlassButton("Delete listing", onClick = { confirmDelete = true }, enabled = !state.saving, leadingIcon = R.drawable.ic_delete, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
            }
            Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                if (state.saving && state.progress > 0f) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                    Spacer(Modifier.height(10.dp))
                }
                GradientButton(
                    if (state.isNew) "Publish listing" else "Save changes",
                    onClick = viewModel::save,
                    loading = state.saving,
                    enabled = !state.loading,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this listing?") },
            text = { Text("It disappears from the Shop. Orders already placed aren't affected.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Delete", color = StatusColors.Error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}
