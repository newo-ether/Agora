package com.newoether.agora.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.newoether.agora.R
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton

/*
 * The single owner of secret-field masking. Every secret text input (API keys, passwords, tokens)
 * is masked by default and has an eye button that shows or hides its value. The visible state is
 * plain `remember` state, so it starts hidden again whenever the page or dialog holding the field
 * leaves composition.
 */

/** Visible state for one secret field: hidden by default, reset when the field leaves composition. */
@Composable
internal fun rememberSecretVisible(): MutableState<Boolean> = remember { mutableStateOf(false) }

/** Masks the value unless [visible]. */
internal fun secretVisualTransformation(visible: Boolean): VisualTransformation =
    if (visible) VisualTransformation.None else PasswordVisualTransformation()

/** Trailing eye button: an open eye while the value is hidden, a crossed eye while it is shown. */
@Composable
internal fun SecretVisibilityToggle(visible: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = stringResource(if (visible) R.string.secret_hide else R.string.secret_show),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
