package com.onefera.app.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.onefera.app.R
import com.onefera.app.core.designsystem.theme.OneFeraTheme

/** Rounded text field styled for OneFera, with optional leading icon and inline error text. */
@Composable
fun OneFeraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    @DrawableRes leadingIcon: Int? = null,
    error: String? = null,
    supportingText: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLength: Int? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    /**
     * Free text people write (captions, comments, messages, bios): turns on the keyboard's own
     * spelling correction and sentence capitalisation. Off for handles, emails, codes and secrets.
     */
    prose: Boolean = !singleLine && keyboardType == KeyboardType.Text,
) {
    val extras = OneFeraTheme.extras
    val leading: (@Composable () -> Unit)? = leadingIcon?.let { icon ->
        { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp)) }
    }
    val supporting: (@Composable () -> Unit)? = when {
        error != null -> { { Text(error) } }
        supportingText != null -> { { Text(supportingText) } }
        maxLength != null && !singleLine -> { { Text("${value.length}/$maxLength") } }
        else -> null
    }
    OutlinedTextField(
        value = value,
        onValueChange = { if (maxLength == null || it.length <= maxLength) onValueChange(it) },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        leadingIcon = leading,
        trailingIcon = trailing,
        isError = error != null,
        supportingText = supporting,
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        readOnly = readOnly,
        shape = RoundedCornerShape(18.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
            capitalization = if (prose) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            autoCorrectEnabled = prose,
        ),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        visualTransformation = visualTransformation,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = extras.glassBorder,
            focusedContainerColor = extras.glass,
            unfocusedContainerColor = extras.glass,
            disabledContainerColor = extras.glass,
            focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
            unfocusedLeadingIconColor = extras.muted,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = extras.muted,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/** Password field with a show/hide toggle. */
@Composable
fun PasswordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OneFeraTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        leadingIcon = R.drawable.ic_lock,
        error = error,
        supportingText = supportingText,
        keyboardType = KeyboardType.Password,
        imeAction = imeAction,
        onImeAction = onImeAction,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = if (visible) "Hide password" else "Show password",
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}
