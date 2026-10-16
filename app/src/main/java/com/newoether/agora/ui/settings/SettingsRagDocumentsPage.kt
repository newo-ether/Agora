package com.newoether.agora.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ImportResult
import com.newoether.agora.data.UserDocument
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * Settings > Documents (RAG): import your own text/markdown/PDF files into an isolated semantic
 * index the agent can search with `search_documents`. Add via the system file picker, see each
 * document's chunk count, and remove individually.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRagDocumentsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val manager = viewModel.userDocumentManager
    val activeModel by viewModel.ragManager.activeEmbeddingModel.collectAsState()
    val scope = rememberCoroutineScope()

    var docs by remember { mutableStateOf<List<UserDocument>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() { docs = manager.list() }
    LaunchedEffect(Unit) { refresh() }

    val noModelMsg = stringResource(R.string.ragdocs_no_model)
    val unsupportedMsg = stringResource(R.string.ragdocs_status_unsupported)
    val noTextMsg = stringResource(R.string.ragdocs_status_notext)

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = null
            val result = manager.import(
                uri = uri,
                config = activeModel,
                apiKey = activeModel?.remoteApiKey?.ifBlank { null },
            )
            status = when (result) {
                is ImportResult.Success -> null
                is ImportResult.Unsupported -> "${result.fileName}: $unsupportedMsg"
                is ImportResult.NoText -> "${result.fileName}: $noTextMsg"
                is ImportResult.NoEmbeddingModel -> noModelMsg
                is ImportResult.Failed -> "${result.fileName}: ${result.reason}"
            }
            refresh()
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ragdocs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.ragdocs_desc),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (activeModel == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.ragdocs_no_model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { picker.launch(arrayOf("application/pdf", "text/*")) },
                enabled = !busy && activeModel != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.height(0.dp))
                } else {
                    Text(stringResource(R.string.ragdocs_add))
                }
            }
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
            if (docs.isEmpty()) {
                Text(
                    text = stringResource(R.string.ragdocs_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(docs, key = { it.docId }) { doc ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                                    Text(doc.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = stringResource(R.string.ragdocs_chunks, doc.chunks),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            manager.remove(doc.docId)
                                            refresh()
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.ragdocs_remove),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
