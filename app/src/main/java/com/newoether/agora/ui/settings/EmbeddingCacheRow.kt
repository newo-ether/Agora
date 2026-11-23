package com.newoether.agora.ui.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.viewmodel.EmbeddingCacheRowPhase
import com.newoether.agora.viewmodel.EmbeddingCacheRowSnapshot
import com.newoether.agora.viewmodel.rowPhase

/**
 * Fixed slot size for the cache action, measured from every label it can show so the row
 * does not jump while loading resolves, including localized text and font scaling.
 */
@Composable
internal fun rememberEmbeddingCacheActionSize(): DpSize {
    val measurer = rememberTextMeasurer()
    val labelSizes = listOf(
        stringResource(R.string.recache_action),
        stringResource(R.string.cache_action),
    ).map { label ->
        measurer.measure(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
        ).size
    }
    val padding = ButtonDefaults.TextButtonContentPadding
    val layoutDirection = LocalLayoutDirection.current
    return with(LocalDensity.current) {
        DpSize(
            width = (labelSizes.maxOf { it.width }.toDp() +
                padding.calculateLeftPadding(layoutDirection) +
                padding.calculateRightPadding(layoutDirection)).coerceAtLeast(76.dp),
            height = (labelSizes.maxOf { it.height }.toDp() +
                padding.calculateTopPadding() +
                padding.calculateBottomPadding()).coerceAtLeast(48.dp),
        )
    }
}

/** Cache progress text for one model, without the model type prefix. */
@Composable
internal fun embeddingCacheLabel(cacheRow: EmbeddingCacheRowSnapshot?): String? =
    when (cacheRow.rowPhase()) {
        EmbeddingCacheRowPhase.LOADING -> stringResource(R.string.loading_label)
        // Caching states its own progress as a percentage of the same pair the indicator draws.
        EmbeddingCacheRowPhase.CACHING -> cacheRow?.cachingCached?.let { shown ->
            val total = requireNotNull(cacheRow.indexableTotal)
            val percent = ((cacheRow.cachingFraction ?: 0f) * 100).toInt()
            "$shown/$total ($percent%)"
        }
            // A run that started before its counts arrived has no progress to state yet. It
            // still has to say it is working, or the row would show only a spinning indicator.
            ?: stringResource(R.string.loading_label)
        EmbeddingCacheRowPhase.CACHE -> {
            val cached = requireNotNull(cacheRow?.cached)
            val total = requireNotNull(cacheRow.indexableTotal)
            "${total - cached} ${stringResource(R.string.not_cached)} ($cached/$total)"
        }
        EmbeddingCacheRowPhase.RECACHE -> {
            val cached = cacheRow?.cached
            "${stringResource(R.string.cached)} ($cached/${cacheRow?.indexableTotal})"
        }
        null -> null
    }

/** "Remote · 12/40 (30%)" style status line shown under a model in the list. */
@Composable
internal fun EmbeddingModelStatusText(model: EmbeddingModelConfig, cacheRow: EmbeddingCacheRowSnapshot?) {
    val typeLabel = if (model.type == EmbeddingModelType.REMOTE) {
        stringResource(R.string.embedding_type_remote)
    } else {
        stringResource(R.string.embedding_type_local)
    }
    Crossfade(
        targetState = listOfNotNull(typeLabel, embeddingCacheLabel(cacheRow)).joinToString(" · "),
        animationSpec = tween(250),
        label = "embeddingCacheStatus-${model.id}",
    ) { status ->
        Text(status)
    }
}

/** Progress ring for the model row; the Cache / Re-cache actions live in the row's menu. */
@Composable
internal fun EmbeddingCacheProgress(cacheRow: EmbeddingCacheRowSnapshot?) {
    val phase = cacheRow.rowPhase()
    if (phase != EmbeddingCacheRowPhase.LOADING && phase != EmbeddingCacheRowPhase.CACHING) return
    val fraction = cacheRow?.cachingFraction
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (phase == EmbeddingCacheRowPhase.CACHING && fraction != null) {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.5.dp,
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
        }
    }
}

/** Cache / Re-cache button, or a progress ring while counts load or caching runs. */
@Composable
internal fun EmbeddingCacheAction(
    modelId: String,
    cacheRow: EmbeddingCacheRowSnapshot?,
    size: DpSize,
    onCache: () -> Unit,
    onRecache: () -> Unit,
) {
    val visualPhase = cacheRow.rowPhase()
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Crossfade(
            targetState = visualPhase,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(250),
            label = "embeddingCacheAction-$modelId",
        ) { phase ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (phase) {
                    EmbeddingCacheRowPhase.CACHING -> {
                        // Determinate only when the row has real counts to divide; otherwise
                        // the fraction would be invented.
                        val fraction = cacheRow?.cachingFraction
                        if (fraction == null) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        } else {
                            CircularProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 3.dp,
                            )
                        }
                    }
                    EmbeddingCacheRowPhase.LOADING ->
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    null -> Unit
                    EmbeddingCacheRowPhase.RECACHE -> TextButton(
                        enabled = visualPhase == phase,
                        onClick = onRecache,
                    ) {
                        Text(stringResource(R.string.recache_action), maxLines = 1, softWrap = false)
                    }
                    EmbeddingCacheRowPhase.CACHE -> TextButton(
                        enabled = visualPhase == phase,
                        onClick = onCache,
                    ) {
                        Text(stringResource(R.string.cache_action), maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
