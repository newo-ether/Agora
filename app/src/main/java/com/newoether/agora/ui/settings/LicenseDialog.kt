package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.clearFocusOnTap

private const val LICENSE_ASSET = "licenses/LICENSE-GPL-3.0.txt"
private const val LICENSE_URL = "https://github.com/newo-ether/Agora/blob/master/LICENSE"

/**
 * Shows the license text that ships inside the APK, so a recipient can read it without
 * network access. Falls back to an empty body if the asset is missing.
 */
@Composable
internal fun LicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val licenseText = remember {
        runCatching {
            context.assets.open(LICENSE_ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.about_license_desc),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.about_license_history),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Text(text = licenseText)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LICENSE_URL)))
            }) {
                Text(stringResource(R.string.about_github))
            }
        }
    )
}
