package com.newoether.agora.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.newoether.agora.R
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton
import com.newoether.agora.ui.components.Button
import com.newoether.agora.ui.components.Surface

data class PdfPageSelection(
    val selectedPages: Set<Int>,
    val totalPages: Int
)

@Composable
fun PdfPageSelectDialog(
    totalPages: Int,
    thumbnailPaths: List<String> = emptyList(),
    isLoading: Boolean = false,
    renderProgress: Pair<Int, Int> = 0 to 0,
    selectedPages: Set<Int> = emptySet(),
    onTogglePage: ((Int) -> Unit)? = null,
    onSelectAll: ((Boolean) -> Unit)? = null,
    onPreviewPage: ((Int) -> Unit)? = null,
    onConfirm: (PdfPageSelection) -> Unit,
    onDismiss: () -> Unit
) {
    val effectiveTotal = totalPages.coerceAtLeast(1)
    val selectAll = selectedPages.size == effectiveTotal

    val hasThumbnails = thumbnailPaths.isNotEmpty() && !isLoading

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.pdf_select_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.pdf_select_subtitle, effectiveTotal),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))

                // Select all / none
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.pdf_pages_selected, selectedPages.size),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = { onSelectAll?.invoke(!selectAll) },
                        enabled = !isLoading
                    ) {
                        Text(stringResource(if (selectAll) R.string.deselect_all else R.string.select_all))
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Loading / thumbnail grid
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.5.dp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.pdf_rendering_progress, renderProgress.first, renderProgress.second),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed((0 until effectiveTotal).chunked(3)) { rowIdx, row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                for (pageIdx in row) {
                                    val page = pageIdx + 1
                                    val isSelected = pageIdx in selectedPages
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(0.75f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .then(
                                                if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                                                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                                            )
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                            )
                                            .clickable { onPreviewPage?.invoke(pageIdx) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (hasThumbnails && pageIdx < thumbnailPaths.size) {
                                            coil.compose.AsyncImage(
                                                model = thumbnailPaths[pageIdx],
                                                contentDescription = stringResource(R.string.pdf_page_content_desc, page),
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Fit
                                            )
                                            // Page number badge
                                            Box(
                                                modifier = Modifier.align(Alignment.BottomEnd)
                                                    .padding(2.dp)
                                                    .background(Color.Black.copy(alpha = 0.70f), RoundedCornerShape(3.dp))
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                            ) {
                                                Text(
                                                "$page",
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                            }
                                        } else {
                                            Text(
                                                "$page",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                        // Selection checkbox overlay
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(4.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(Color.Black.copy(alpha = 0.70f))
                                                .clickable { onTogglePage?.invoke(pageIdx) }
                                                .padding(2.dp)
                                        ) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                                // Fill remaining slots in last row
                                repeat(3 - row.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, shape = RoundedCornerShape(50)) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(PdfPageSelection(selectedPages, effectiveTotal)) },
                        shape = RoundedCornerShape(50),
                        enabled = selectedPages.isNotEmpty() && !isLoading
                    ) {
                        Text(stringResource(R.string.pdf_send_pages, selectedPages.size))
                    }
                }
            }
        }
    }
}
