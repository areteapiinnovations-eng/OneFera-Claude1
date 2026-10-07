package com.onefera.app.feature.moderation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.moderation.ModerationRepository
import com.onefera.app.data.moderation.ReportReason
import com.onefera.app.data.moderation.ReportTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModerationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moderation: ModerationRepository,
) : ViewModel() {
    /** Confirmations outlive the dialog that triggered them, so they're shown as toasts. */
    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    val blockedIds: StateFlow<Set<String>> = moderation.blockedIds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun report(target: ReportTarget, targetId: String, ownerId: String, reason: ReportReason, details: String) = viewModelScope.launch {
        moderation.report(target, targetId, ownerId, reason, details)
            .onSuccess { toast("Thanks for reporting. We'll review it, and they won't know it was you.") }
            .onFailure { toast(it.message ?: "Couldn't send your report.") }
    }

    fun block(user: UserSummary) = viewModelScope.launch {
        moderation.block(user)
            .onSuccess { toast("Blocked @${user.username}. They can't follow, comment on or message you.") }
            .onFailure { toast(it.message ?: "Couldn't block right now.") }
    }

    fun unblock(user: UserSummary) = viewModelScope.launch {
        moderation.unblock(user.uid)
            .onSuccess { toast("Unblocked @${user.username}") }
            .onFailure { toast(it.message ?: "Couldn't unblock right now.") }
    }
}

/** Asks why something is being reported, then files the report. */
@Composable
fun ReportDialog(
    target: ReportTarget,
    targetId: String,
    ownerId: String,
    onDismiss: () -> Unit,
    viewModel: ModerationViewModel = hiltViewModel(),
) {
    var reason by rememberSaveable { mutableStateOf<ReportReason?>(null) }
    var details by rememberSaveable { mutableStateOf("") }
    val what = when (target) {
        ReportTarget.Post -> "post"
        ReportTarget.Comment -> "comment"
        ReportTarget.User -> "account"
        ReportTarget.Message -> "message"
        ReportTarget.Product -> "listing"
        ReportTarget.Story -> "story"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report this $what") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Why are you reporting it? Reports are anonymous.", style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted)
                ReportReason.entries.forEach { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.RadioButton) { reason = r }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == r, onClick = { reason = r })
                        Text(r.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (reason == ReportReason.Other || reason == ReportReason.SelfHarm) {
                    OneFeraTextField(details, { details = it.take(500) }, "Tell us more (optional)", singleLine = false, minLines = 2)
                }
                if (reason == ReportReason.SelfHarm) {
                    Text(
                        "If someone is in immediate danger, call 112. You can also reach Tele-MANAS on 14416, free and 24×7.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = reason != null,
                onClick = {
                    reason?.let { viewModel.report(target, targetId, ownerId, it, details) }
                    onDismiss()
                },
            ) { Text("Report", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirms blocking someone. */
@Composable
fun BlockDialog(user: UserSummary, onDismiss: () -> Unit, viewModel: ModerationViewModel = hiltViewModel()) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block @${user.username}?") },
        text = { Text("They won't be able to follow you, comment on your posts or message you, and you won't see their content. They aren't notified.") },
        confirmButton = {
            TextButton(onClick = { viewModel.block(user); onDismiss() }) { Text("Block", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
