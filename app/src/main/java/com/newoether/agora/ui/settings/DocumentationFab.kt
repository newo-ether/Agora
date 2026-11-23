package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R

@Composable
fun DocumentationFab(docPath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val langTag = locale.toLanguageTag()
    val baseUrl = "https://newo-ether.github.io/Agora/"
    // Map the resolved locale to the docs URL prefix.
    // "en" and anything unrecognised → root (English); each supported
    // language maps to its own subdirectory under the docs site.
    val langPrefix = when {
        langTag == "zh-Hant"  -> "zh-Hant/"
        langTag.startsWith("zh") -> "zh/"
        else -> {
            val lang = locale.language
            when (lang) {
                "es", "fr", "de", "ru", "ja", "ko", "ar" -> "$lang/"
                "pt" -> "pt-BR/"
                else -> ""  // en or unknown → root (English)
            }
        }
    }

    AnimatedActionFab(
        label = stringResource(R.string.documentation),
        icon = Icons.AutoMirrored.Rounded.MenuBook,
        onClick = {
            val page = docPath.removeSuffix(".md") + "/"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$baseUrl$langPrefix$page"))
            context.startActivity(intent)
        },
        modifier = modifier,
    )
}
