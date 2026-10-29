package com.newoether.agora.ui.chat.message

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.newoether.agora.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Exports the original source only. Generated JavaScript never receives an Android export API. */
@Composable
internal fun rememberHtmlPreviewExport(code: String, onFailure: () -> Unit): (Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val failure by rememberUpdatedState(onFailure)
    var pendingSource by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        val source = pendingSource
        pendingSource = null
        if (uri != null && source != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).use {
                        it.write(source.toByteArray(Charsets.UTF_8))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure() }
        }
    }
    return { share ->
        if (share) scope.launch {
            if (!shareHtmlPreview(context, code)) failure()
        } else {
            pendingSource = code
            save.launch("agora-preview.html")
        }
    }
}

private suspend fun shareHtmlPreview(context: Context, code: String): Boolean {
    var destination: File? = null
    return try {
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "shared").apply { mkdirs() }
            File.createTempFile("agora_preview_", ".html", directory).also {
                destination = it
                it.writeText(code, Charsets.UTF_8)
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.html_preview_share)))
        true
    } catch (failure: Exception) {
        withContext(Dispatchers.IO) { destination?.delete() }
        if (failure is CancellationException) throw failure
        false
    }
}
