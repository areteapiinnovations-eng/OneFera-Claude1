package com.onefera.app.feature.post

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.data.model.Post
import com.onefera.app.data.model.PostEdit

/** Edits the caption and location of one of the user's own posts. */
@Composable
fun EditPostDialog(post: Post, saving: Boolean, onSave: (PostEdit) -> Unit, onDismiss: () -> Unit) {
    var caption by rememberSaveable(post.id) { mutableStateOf(post.caption) }
    var location by rememberSaveable(post.id) { mutableStateOf(post.location) }
    val changed = caption.trim() != post.caption || location.trim() != post.location
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Edit post") },
        text = {
            Column {
                OneFeraTextField(
                    value = caption,
                    onValueChange = { caption = it.take(MAX_CAPTION) },
                    label = "Caption",
                    singleLine = false,
                    modifier = Modifier.heightIn(min = 96.dp, max = 220.dp),
                )
                Spacer(Modifier.height(10.dp))
                OneFeraTextField(value = location, onValueChange = { location = it.take(MAX_LOCATION) }, label = "Location")
                Spacer(Modifier.height(6.dp))
                Text("${caption.length}/$MAX_CAPTION · #hashtags in the caption are updated too", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(enabled = changed && !saving, onClick = { onSave(PostEdit(caption.trim(), location.trim())) }) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MAX_CAPTION = 2200
private const val MAX_LOCATION = 80
