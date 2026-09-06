package com.newoether.agora.ui.settings

import com.newoether.agora.viewmodel.EmbeddingCacheFailureKind
import com.newoether.agora.viewmodel.EmbeddingCacheRowPhase
import com.newoether.agora.viewmodel.EmbeddingCacheRowReducer
import com.newoether.agora.viewmodel.EmbeddingCacheRowSnapshot
import com.newoether.agora.viewmodel.EmbeddingCacheWorkSnapshot
import com.newoether.agora.viewmodel.embeddingCacheWorkSnapshotOrNull
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingCacheActionStateTest {
    @Test
    fun successTraceNeverEmitsIdleBeforeRecache() {
        var row = EmbeddingCacheRowReducer.refreshRequested(null)
        val trace = mutableListOf(row.phase)
        row = EmbeddingCacheRowReducer.workActive(row, null); trace += row.phase
        row = EmbeddingCacheRowReducer.workActive(row, progress(0, 40)); trace += row.phase
        row = EmbeddingCacheRowReducer.workActive(row, progress(10, 40)); trace += row.phase
        row = EmbeddingCacheRowReducer.finalizing(row); trace += row.phase
        assertEquals(EmbeddingCacheRowPhase.CACHING, row.visualPhase)
        row = EmbeddingCacheRowReducer.refreshed(row, 40, 40, true); trace += row.phase
        assertEquals(
            listOf(
                EmbeddingCacheRowPhase.LOADING, EmbeddingCacheRowPhase.QUEUED,
                EmbeddingCacheRowPhase.CACHING, EmbeddingCacheRowPhase.CACHING,
                EmbeddingCacheRowPhase.FINALIZING, EmbeddingCacheRowPhase.RECACHE,
            ),
            trace,
        )
    }

    @Test
    fun followerLoadsAndWorkerFailureRetainsReliableProgress() {
        val active = EmbeddingCacheRowReducer.workActive(null, progress(10, 40))
        val follower = EmbeddingCacheRowReducer.workActive(active, null)
        assertEquals(EmbeddingCacheRowPhase.QUEUED, follower.phase)
        assertEquals(EmbeddingCacheRowPhase.LOADING, follower.visualPhase)
        assertNull(follower.progress)

        val failed = EmbeddingCacheRowReducer.failed(active, EmbeddingCacheFailureKind.WORK)
        val refreshed = EmbeddingCacheRowReducer.refreshed(failed, 30, 40, false)
        assertEquals(EmbeddingCacheRowPhase.FAILED, refreshed.phase)
        assertEquals(30, refreshed.progress?.remaining)
        assertEquals(EmbeddingCacheFailureKind.WORK, refreshed.failure)
    }

    @Test
    fun refreshFailureIsRetryableWithoutDiscardingStableState() {
        val initialFailure = EmbeddingCacheRowReducer.refreshFailed(null)
        assertEquals(EmbeddingCacheRowPhase.FAILED, initialFailure.phase)
        assertEquals(EmbeddingCacheRowPhase.LOADING,
            EmbeddingCacheRowReducer.refreshRequested(initialFailure).phase)
        val stable = EmbeddingCacheRowSnapshot(
            EmbeddingCacheRowPhase.CACHE, cached = 4, indexableTotal = 10,
        )
        assertSame(stable, EmbeddingCacheRowReducer.refreshFailed(stable))
        val finalizing = EmbeddingCacheRowReducer.finalizing(
            EmbeddingCacheRowReducer.workActive(stable, progress(10, 40)),
        )
        val failedFinalRefresh = EmbeddingCacheRowReducer.refreshFailed(finalizing)
        assertEquals(EmbeddingCacheRowPhase.FAILED, failedFinalRefresh.phase)
        assertEquals(EmbeddingCacheFailureKind.REFRESH, failedFinalRefresh.failure)
    }

    @Test
    fun staleRefreshAndIncoherentWorkerPayloadCannotCorruptActiveState() {
        val progress = progress(7, 20)
        val active = EmbeddingCacheRowReducer.workActive(null, progress)
        val refreshed = EmbeddingCacheRowReducer.refreshed(active, 20, 20, true)
        val finalizing = EmbeddingCacheRowReducer.finalizing(active)
        assertEquals(EmbeddingCacheRowPhase.FINALIZING, EmbeddingCacheRowReducer.refreshed(finalizing, 20, 20, false).phase)
        assertEquals(EmbeddingCacheRowPhase.CACHING, refreshed.phase)
        assertTrue(refreshed.progress == progress && refreshed.workActive)
        assertNull(workSnapshot(remaining = 5, permille = 400))
        assertNull(workSnapshot(remaining = 6, permille = 989))
    }

    @Test
    fun cacheActionSlotReservesAllLabelsBeforeLoadingAndKeepsChildrenCentered() {
        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val root = generateSequence(start) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        val source = File(
            root,
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsSearchPage.kt",
        ).readText().replace("\r\n", "\n")

        assertTrue(source.contains("Modifier.size(cacheActionSize)"))
        assertTrue(source.contains("actionLabelSizes.maxOf { it.width }"))
        assertTrue(source.contains("actionLabelSizes.maxOf { it.height }"))
        assertTrue(source.contains("ButtonDefaults.TextButtonContentPadding"))
        assertTrue(source.contains("coerceAtLeast(76.dp)"))
        assertTrue(source.contains("coerceAtLeast(48.dp)"))
        assertFalse(source.contains("Modifier.width(76.dp)"))
        val sizing = source.substringAfter("val actionTextMeasurer")
            .substringBefore("LaunchedEffect(embeddingModelIds)")
        assertFalse(sizing.contains("visualPhase"))
        val action = source.substringAfter("modifier = Modifier.size(cacheActionSize)")
            .substringBefore("IconButton(onClick = { showMenuForModel")
        assertEquals(2, Regex("Modifier\\.fillMaxSize\\(\\)").findAll(action).count())
        assertTrue(action.contains("animationSpec = tween(250)"))
        val child = action.substringAfter(") { phase ->")
        assertTrue(child.indexOf("contentAlignment = androidx.compose.ui.Alignment.Center") <
            child.indexOf("when (phase)"))
        listOf(
            "R.string.retry",
            "R.string.recache_action",
            "R.string.cache_action",
        ).forEach { label ->
            assertTrue("Sizing must include $label", sizing.contains("stringResource($label)"))
            val startIndex = action.indexOf("stringResource($label)")
            assertTrue("Missing $label action label", startIndex >= 0)
            val labelBlock = action.substring(
                startIndex,
                (startIndex + 350).coerceAtMost(action.length),
            )
            assertTrue("$label must stay on one line", labelBlock.contains("maxLines = 1"))
            assertTrue("$label must not wrap", labelBlock.contains("softWrap = false"))
        }
    }
    @Test
    fun ratingContentSharesFixedCenteredBoundsAndUsesNeutralFailureSurface() {
        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val root = generateSequence(start) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        val source = File(
            root,
            "app/src/main/java/com/newoether/agora/ui/settings/RatingForm.kt",
        ).readText().replace("\r\n", "\n")
        assertTrue(source.contains("Modifier.fillMaxWidth().height(52.dp)"))
        val action = source.substringAfter("targetState = submitting to submitted,")
        assertEquals(2, Regex("Modifier\\.fillMaxSize\\(\\)").findAll(action).count())
        assertTrue(action.contains("animationSpec = tween(250)"))
        assertTrue(action.indexOf("contentAlignment = Alignment.Center") <
            action.indexOf("when {"))
        assertTrue(action.contains("loading -> CircularProgressIndicator("))
        assertTrue(action.contains("done -> Text("))
        assertTrue(action.contains("else -> Text("))
        val failure = source.substringAfter("if (submitError) {").substringBefore("val isReady")
        assertTrue(failure.contains("color = MaterialTheme.colorScheme.surfaceContainerHigh"))
        assertTrue(failure.contains("color = MaterialTheme.colorScheme.onSurfaceVariant,"))
        assertFalse(failure.contains("colorScheme.errorContainer"))
        assertFalse(failure.contains("colorScheme.onErrorContainer"))
    }

    private fun workSnapshot(remaining: Int, permille: Int) =
        embeddingCacheWorkSnapshotOrNull(3, "EXACT", 4, 10, remaining, permille)

    private fun progress(processed: Int, total: Int) = EmbeddingCacheWorkSnapshot(
        7, "RECONCILE", processed, total, total - processed, processed * 1000 / total,
    )
}
