package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.NoAutoScrollSelectionContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class JsonRenderSnapshot(
    val source: String,
    val document: StreamingJsonDocument,
)

/**
 * MCP and other protocols may emit the same structured value both as compatibility text and as
 * structured output. Collapse only adjacent, complete, structurally equal top-level documents;
 * distinct values and any value still streaming are always retained.
 */
internal fun visibleJsonRoots(
    roots: List<StreamingJsonNode>,
): List<StreamingJsonNode> = buildList {
    roots.forEach { root ->
        val duplicate = root.complete &&
            lastOrNull()?.complete == true &&
            lastOrNull() == root
        if (!duplicate) add(root)
    }
}

// A long or multi-line string value (e.g. a grep match's `content`, or a deep
// file `path`) would, when squeezed to the right of its key chip through several
// nested indents, wrap into a thin column hugging the screen's right edge. Such
// values are instead rendered on their own full-width line below the key.
private fun isBlockString(value: StreamingJsonNode): Boolean =
    value is StreamingJsonScalar &&
        value.kind == StreamingJsonScalarKind.STRING &&
        (value.content.length > 40 || value.content.contains('\n'))

@Composable
private fun JsonLabelPill(label: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = label,
            style = ChatType.meta,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun JsonNodeView(node: StreamingJsonNode, depth: Int = 0) {
    when (node) {
        is StreamingJsonObject -> JsonObjectView(node, depth)
        is StreamingJsonArray -> JsonArrayView(node, depth)
        is StreamingJsonScalar -> JsonScalarView(node)
    }
}

@Composable
private fun JsonObjectView(obj: StreamingJsonObject, depth: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        obj.entries.forEachIndexed { index, entry ->
            key("json-object:$depth:$index:${entry.key}") {
                val value = entry.value
                val blockString = value?.let(::isBlockString) == true
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        if (entry.key.isNotEmpty() || entry.keyComplete) {
                            JsonLabelPill(entry.key, MaterialTheme.colorScheme.primary)
                        }
                        if (value != null && !blockString) {
                            Spacer(Modifier.width(8.dp))
                            when (value) {
                                is StreamingJsonScalar -> JsonScalarView(
                                    value,
                                    modifier = Modifier.weight(1f),
                                )
                                is StreamingJsonObject -> Text(
                                    "{…}",
                                    style = ChatType.thoughtBody,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                is StreamingJsonArray -> Text(
                                    "[…]",
                                    style = ChatType.thoughtBody,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (blockString && value is StreamingJsonScalar) {
                        JsonScalarView(
                            value,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                    when (value) {
                        is StreamingJsonObject -> {
                            Box(
                                modifier = Modifier
                                    .padding(start = ((depth + 1) * 16).dp)
                                    .padding(top = 2.dp),
                            ) {
                                JsonObjectView(value, depth + 1)
                            }
                        }
                        is StreamingJsonArray -> {
                            Box(
                                modifier = Modifier
                                    .padding(start = ((depth + 1) * 16).dp)
                                    .padding(top = 2.dp),
                            ) {
                                JsonArrayView(value, depth + 1)
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun JsonArrayView(arr: StreamingJsonArray, depth: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        arr.values.forEachIndexed { index, item ->
            key("json-array:$depth:$index") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    JsonLabelPill("${index + 1}", MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    when (item) {
                        is StreamingJsonScalar -> JsonScalarView(
                            item,
                            modifier = Modifier.weight(1f),
                        )
                        is StreamingJsonObject ->
                            Box(Modifier.weight(1f)) { JsonObjectView(item, depth) }
                        is StreamingJsonArray ->
                            Box(Modifier.weight(1f)) { JsonArrayView(item, depth) }
                    }
                }
            }
        }
    }
}

@Composable
private fun JsonScalarView(
    scalar: StreamingJsonScalar,
    modifier: Modifier = Modifier,
) {
    val color = when {
        scalar.kind == StreamingJsonScalarKind.STRING -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.tertiary
    }
    val style = if (scalar.kind == StreamingJsonScalarKind.STRING) {
        ChatType.thoughtBody
    } else {
        ChatType.thoughtCodeLarge
    }
    Text(
        text = if (
            scalar.kind == StreamingJsonScalarKind.NULL &&
            scalar.complete
        ) {
            "—"
        } else {
            scalar.content
        },
        style = style,
        color = color,
        modifier = modifier,
    )
}

internal const val FiloPreviewTruncationMarker =
    "\n[Filo preview truncated; full output remains in Codex.]"

internal fun persistenceAwareJsonSource(text: String): String =
    text.removeSuffix(MessagePersistenceGuard.TRUNCATION_MARKER)
        .removeSuffix(FiloPreviewTruncationMarker)

@Composable
internal fun JsonOrPlainView(text: String) {
    // Oversized payloads: skip JSON tree expansion (thousands of composables from a
    // 100k tool result) and show the plain preview instead.
    if (isLargeForMarkdown(text)) {
        LargeMessageView(text = text)
        return
    }
    val jsonSource = persistenceAwareJsonSource(text)
    val persistenceTruncated = jsonSource.length != text.length
    var parsed by remember { mutableStateOf<JsonRenderSnapshot?>(null) }
    LaunchedEffect(text, jsonSource) {
        val document = withContext(Dispatchers.Default) {
            StreamingJsonParser.parse(jsonSource)
        }
        parsed = JsonRenderSnapshot(source = text, document = document)
    }

    val snapshot = parsed
    val canRetainPreviousTree =
        snapshot != null && text.startsWith(snapshot.source)
    val document = snapshot
        ?.takeIf { it.source == text || canRetainPreviousTree }
        ?.document
    val visibleRoots = remember(document) {
        visibleJsonRoots(document?.roots.orEmpty())
    }
    when {
        document?.status != StreamingJsonStatus.INVALID &&
            (visibleRoots.isNotEmpty() || persistenceTruncated) -> {
            NoAutoScrollSelectionContainer {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    visibleRoots.forEachIndexed { index, root ->
                        key("json-root:$index") {
                            JsonNodeView(root)
                        }
                    }
                    if (persistenceTruncated) {
                        Text(
                            text = text.substring(jsonSource.length).trimStart(),
                            style = ChatType.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        document?.status == StreamingJsonStatus.INVALID -> {
            NoAutoScrollSelectionContainer {
                Text(
                    text = text,
                    style = ChatType.thoughtCodeLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        else -> {
            // Parsing starts off-main. Reserve a tiny stable slot instead of synchronously laying
            // out a potentially huge raw argument string on the UI thread for a single frame.
            Spacer(Modifier.height(1.dp))
        }
    }
}
