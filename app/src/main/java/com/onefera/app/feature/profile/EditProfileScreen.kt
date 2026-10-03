package com.onefera.app.feature.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onefera.app.R
import com.onefera.app.core.common.Validators
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.component.SelectChip
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.model.ProfileVibes
import com.onefera.app.feature.auth.FormError

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditProfileScreen(onBack: () -> Unit, viewModel: EditProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val extras = OneFeraTheme.extras
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onPhotoPicked(uri)
    }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.5f) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = if (state.existing == null && !state.loading) "Set up profile" else "Edit profile", onBack = onBack)
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally)) {
                    Avatar(
                        imageUrl = state.avatarUrl,
                        name = state.displayName.ifBlank { "You" },
                        size = 108.dp,
                        ring = true,
                        modifier = Modifier.clip(CircleShape).clickable {
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(extras.gradientBrush()),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.uploadingPhoto) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(painterResource(R.drawable.ic_camera), contentDescription = "Change photo", tint = extras.onGradient, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                OneFeraTextField(
                    value = state.displayName,
                    onValueChange = viewModel::onNameChange,
                    label = "Display name",
                    leadingIcon = R.drawable.ic_person,
                    error = state.nameError,
                    maxLength = Validators.NAME_MAX,
                )
                Spacer(Modifier.height(10.dp))
                OneFeraTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChange,
                    label = "Username",
                    leadingIcon = R.drawable.ic_at,
                    error = state.usernameError,
                )
                Spacer(Modifier.height(10.dp))
                OneFeraTextField(
                    value = state.bio,
                    onValueChange = viewModel::onBioChange,
                    label = "Bio",
                    singleLine = false,
                    minLines = 3,
                    maxLength = Validators.BIO_MAX,
                    imeAction = ImeAction.Default,
                )
                Spacer(Modifier.height(10.dp))
                OneFeraTextField(
                    value = state.city,
                    onValueChange = viewModel::onCityChange,
                    label = "City",
                    leadingIcon = R.drawable.ic_location,
                    imeAction = ImeAction.Done,
                )
                Spacer(Modifier.height(18.dp))
                Text("YOUR VIBE", style = MaterialTheme.typography.labelMedium, color = extras.muted)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileVibes.forEach { vibe ->
                        SelectChip(text = vibe, selected = state.vibe == vibe, onClick = { viewModel.onVibeChange(vibe) })
                    }
                }
                FormError(state.formError)
                Spacer(Modifier.height(24.dp))
                GradientButton(text = "Save changes", loading = state.saving, onClick = viewModel::save, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
