package com.newoether.agora.ui.settings

import com.newoether.agora.viewmodel.EmbeddingCacheRowPhase
import com.newoether.agora.viewmodel.EmbeddingCacheRowReducer
import com.newoether.agora.viewmodel.EmbeddingCacheRowSnapshot
import com.newoether.agora.viewmodel.EmbeddingCacheWorkSnapshot
import com.newoether.agora.viewmodel.embeddingCacheWorkSnapshotOrNull
import com.newoether.agora.viewmodel.rowPhase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingCacheActionStateTest {
    @Test
    fun fourStatesProjectOnlyRealWorkAndKnownCounts() {
        assertEquals(4, EmbeddingCacheRowPhase.entries.size)
        assertNull(EmbeddingCacheRowSnapshot().phase)
        val loading = EmbeddingCacheRowReducer.refreshRequested(null)
        assertEquals(EmbeddingCacheRowPhase.LOADING, loading.phase)
        val cached = EmbeddingCacheRowReducer.refreshed(loading, 4, 10)
        assertEquals(EmbeddingCacheRowPhase.CACHE, cached.phase)
        val running = EmbeddingCacheRowReducer.workChanged(cached, true)
        assertEquals(EmbeddingCacheRowPhase.CACHING, running.phase)
        assertNull(running.progress)
        assertEquals(EmbeddingCacheRowPhase.CACHE,
            EmbeddingCacheRowReducer.workChanged(running, false).phase)
        assertEquals(EmbeddingCacheRowPhase.RECACHE,
            EmbeddingCacheRowReducer.refreshed(cached, 10, 10).phase)
        assertEquals(EmbeddingCacheRowPhase.RECACHE,
            EmbeddingCacheRowReducer.refreshed(null, 0, 0).phase)
    }

    @Test
    fun initialFailureHasNoFabricatedRowAndLaterFailureRetainsCounts() {
        val failed = EmbeddingCacheRowReducer.refreshFailed(null)
        assertNull(failed.phase)
        assertTrue(failed.countFailed)
        assertFalse(failed.countLoading)
        assertNull(failed.cached)
        assertEquals(EmbeddingCacheRowPhase.LOADING,
            EmbeddingCacheRowReducer.refreshRequested(failed).phase)
        val stable = EmbeddingCacheRowReducer.refreshed(null, 4, 10)
        val retained = EmbeddingCacheRowReducer.refreshFailed(stable)
        assertEquals(EmbeddingCacheRowPhase.CACHE, retained.phase)
        assertEquals(4, retained.cached)
        assertEquals(10, retained.indexableTotal)
    }

    @Test
    fun anUnresolvedRowStillReadsAsLoadingAndOnlyAFailureHasNoPhase() {
        assertEquals(
            EmbeddingCacheRowPhase.LOADING,
            (null as EmbeddingCacheRowSnapshot?).rowPhase(),
        )
        assertEquals(EmbeddingCacheRowPhase.LOADING, EmbeddingCacheRowSnapshot().rowPhase())
        assertNull(EmbeddingCacheRowReducer.refreshFailed(null).rowPhase())
        assertEquals(
            EmbeddingCacheRowPhase.CACHE,
            EmbeddingCacheRowReducer.refreshed(null, 4, 10).rowPhase(),
        )
    }

    @Test
    fun workAndCountCompletionCannotOverrideEachOthersFacts() {
        val loading = EmbeddingCacheRowReducer.refreshRequested(null)
        val running = EmbeddingCacheRowReducer.workChanged(loading, true, progress(7, 20))
        val known = EmbeddingCacheRowReducer.refreshed(running, 15, 20)
        assertEquals(EmbeddingCacheRowPhase.CACHING, known.phase)
        assertEquals(15, known.cached)
        val stopped = EmbeddingCacheRowReducer.workChanged(known, false)
        assertEquals(EmbeddingCacheRowPhase.CACHE, stopped.phase)
        assertNull(stopped.progress)
        assertEquals(EmbeddingCacheRowPhase.LOADING,
            EmbeddingCacheRowReducer.workChanged(running, false).phase)
        val failedCount = EmbeddingCacheRowReducer.refreshFailed(running)
        assertEquals(EmbeddingCacheRowPhase.CACHING, failedCount.phase)
        assertNull(EmbeddingCacheRowReducer.workChanged(failedCount, false).phase)
    }

    @Test
    fun cachingNumbersAndIndicatorShareOneDenominator() {
        val known = EmbeddingCacheRowReducer.refreshed(null, 15, 20)
        val exact = EmbeddingCacheRowReducer.workChanged(known, true, progress(3, 12))
        assertEquals(EmbeddingCacheRowPhase.CACHING, exact.phase)
        // The aggregate snapshot is not re-queried while the run works, so its own processed count
        // advances the displayed pair instead of a second batch denominator.
        assertEquals(18, exact.cachingCached)
        assertEquals(0.9f, requireNotNull(exact.cachingFraction), 0.0001f)

        val reconcile = EmbeddingCacheRowReducer.workChanged(
            known,
            true,
            progress(3, 12, kind = "RECONCILE"),
        )
        assertEquals(15, reconcile.cachingCached)
        assertEquals(0.75f, requireNotNull(reconcile.cachingFraction), 0.0001f)

        val nearlyDone = EmbeddingCacheRowReducer.workChanged(
            EmbeddingCacheRowReducer.refreshed(null, 19, 20),
            true,
            progress(5, 5),
        )
        assertEquals(20, nearlyDone.cachingCached)
        assertEquals(1f, requireNotNull(nearlyDone.cachingFraction), 0.0001f)

        val unknownCounts = EmbeddingCacheRowReducer.workChanged(null, true, progress(3, 12))
        assertEquals(EmbeddingCacheRowPhase.CACHING, unknownCounts.phase)
        assertNull(unknownCounts.cachingCached)
        assertNull(unknownCounts.cachingFraction)
    }

    @Test
    fun cachingRowDrawsTheStatusPairAndNeverTheWorkerBatchFraction() {
        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val root = generateSequence(start) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        val source = File(
            root,
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsSearchPage.kt",
        ).readText().replace("\r\n", "\n")

        assertTrue(source.contains("cacheRow?.cachingCached?.let { shown ->"))
        assertTrue(source.contains("\"\$shown/\$total (\$percent%)\""))
        assertTrue(source.contains("val fraction =\n                                                                    cacheRow?.cachingFraction"))
        assertFalse(source.contains("progress = { progress.fraction }"))
    }

    @Test
    fun incoherentCountsAreRejectedInsteadOfClampedToComplete() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            EmbeddingCacheRowReducer.refreshed(null, 11, 10)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            EmbeddingCacheRowReducer.refreshed(null, -1, 10)
        }
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

    private fun progress(processed: Int, total: Int, kind: String = "EXACT") =
        EmbeddingCacheWorkSnapshot(
            7, kind, processed, total, total - processed, processed * 1000 / total,
        )
}
