package com.newoether.agora.ui.settings

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.ui.components.CircularBackButton
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.theme.chatCompactWidth

// ── Shared geometry for the iOS-style collapsing large title, used by the
//    Settings home page and every settings sub-page so the morph is identical. ──
internal val SettingsBarHeight = 64.dp          // fixed top bar height (below the status bar)
internal val SettingsTitleDockTop = 18.dp       // docked title's top inside the bar
// Narrow windows (see CHAT_COMPACT_WIDTH_DP) tighten the space above (8 dp) and below (10 dp) the big title.
internal val SettingsTitleBottomInset get() = if (chatCompactWidth) 60.dp else 70.dp   // big title's top-left, measured up from the header bottom
internal val SettingsTitleAreaHeight get() = if (chatCompactWidth) 72.dp else 90.dp    // big-title header room; taller = longer rise
internal val SettingsTitleExpandedFont = 33.sp
internal val SettingsTitleCollapsedFont = 22.sp
internal val SettingsContentMaxWidth = 840.dp

/** Gentle ease applied to the title's scale + horizontal tuck only — its vertical rise stays
 *  glued 1:1 to the scrolling header, so the shrink-and-dock follows a curve, not dead-linear. */
internal val SettingsTitleEasing = CubicBezierEasing(0.2f, 0f, 0.5f, 1f)

/** Travel distance (Dp) the docked title rises through, derived from the shared geometry. */
internal val settingsTitleTravel: Dp
    get() = SettingsBarHeight + SettingsTitleAreaHeight - SettingsTitleBottomInset - SettingsTitleDockTop

/**
 * iOS-style collapsing title overlay. A single title [Text] whose **vertical** position is glued 1:1
 * to a scrolling header (so the list always tracks the finger and the title never floats over content
 * rows), while its **scale + horizontal** position are an eased, non-linear function of [fraction]
 * (0 = expanded, 1 = docked). Drawn above the list — one glyph, no cross-fade. [actions] sit at the
 * top-right, aligned with the back arrow.
 */
@Composable
internal fun CollapsingSettingsTitleBar(
    title: String,
    backDescription: String,
    fraction: Float,
    statusBarTop: Dp,
    titleAreaHeight: Dp,
    titleTravel: Dp,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    Box(modifier = Modifier.fillMaxWidth()) {
        // Opaque bar (incl. the status-bar strip) hides list content scrolling underneath it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(statusBarTop + SettingsBarHeight)
                .background(MaterialTheme.colorScheme.background)
        )
        BoxWithConstraints(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = SettingsContentMaxWidth)
                .fillMaxWidth(),
        ) {
            // Expanded titles align with the shared 24dp content inset.
            val availableTitleWidth = (maxWidth - 24.dp - 16.dp).coerceAtLeast(0.dp)

        // Auto-fit the expanded font: shrink from 33sp until even long translations
        // (Spanish/French/Russian page names, etc.) fit on one line — down to a 20sp floor.
        val expandedFont = remember(title, availableTitleWidth) {
            val floor = 20f
            val availablePx = with(density) { availableTitleWidth.toPx() }
            var fontSize = SettingsTitleExpandedFont.value
            while (fontSize > floor) {
                val measured = textMeasurer.measure(
                    title,
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = fontSize.sp)
                ).size.width
                if (measured <= availablePx) break
                fontSize -= 1f
            }
            fontSize.coerceAtLeast(floor).sp
        }

        val scaleEnd = SettingsTitleCollapsedFont.value / expandedFont.value
        val eased = SettingsTitleEasing.transform(fraction)
        val titleScale = 1f - (1f - scaleEnd) * eased

        val expandedY = statusBarTop + SettingsBarHeight + titleAreaHeight - SettingsTitleBottomInset
        val titleY = expandedY - titleTravel * fraction   // linear 1:1 with scroll → docks at expandedY − travel
        val titleX = if (LocalSettingsPaneBackButtonVisible.current) {
            24.dp + (70.dp - 24.dp) * eased
        } else {
            24.dp
        }

        if (LocalSettingsPaneBackButtonVisible.current) {
            CircularBackButton(
                onClick = onBack,
                contentDescription = backDescription,
                modifier = Modifier.padding(start = 16.dp, top = statusBarTop + 12.dp)
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp, top = statusBarTop + 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = actions
        )
        // offset() and the default TopStart alignment both mirror for RTL, so the title docks
        // beside the back arrow on the correct side. Only the scale anchor must be flipped:
        // pin the start edge (top-left in LTR, top-right in RTL) so it grows out from the arrow.
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = expandedFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .widthIn(max = availableTitleWidth)
                .offset(x = titleX, y = titleY)
                .graphicsLayer {
                    scaleX = titleScale
                    scaleY = titleScale
                    transformOrigin = TransformOrigin(if (isRtl) 1f else 0f, 0f)
                }
        )
        }
    }
}

/**
 * Collapsing-large-title scaffold for **scrolling-Column** settings sub-pages. Behaviour is identical
 * to the Settings home page: the content scrolls 1:1 with the finger under a tall header that holds
 * the big title; the title rises glued to it (linear) while its scale + horizontal tuck are eased.
 */
@Composable
fun CollapsingSettingsScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberSettingsScrollState(),
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val titleAreaHeight = SettingsTitleAreaHeight
    val titleTravel = settingsTitleTravel
    val titleTravelPx = with(LocalDensity.current) { titleTravel.toPx() }
    val fraction by remember(scrollState, titleTravelPx) {
        derivedStateOf { (scrollState.value / titleTravelPx).coerceIn(0f, 1f) }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = SettingsContentMaxWidth)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(scrollState)
                .clearFocusOnTap()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(statusBarTop + SettingsBarHeight + titleAreaHeight))
            content()
            Spacer(modifier = Modifier.height(32.dp))
        }
        CollapsingSettingsTitleBar(
            title = title,
            backDescription = stringResource(R.string.back),
            fraction = fraction,
            statusBarTop = statusBarTop,
            titleAreaHeight = titleAreaHeight,
            titleTravel = titleTravel,
            onBack = onBack,
            actions = actions
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            floatingActionButton()
        }
    }
}

/** Optional pull-to-refresh; its indicator drops from the page top above the title and content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPullRefresh(
    refreshing: Boolean,
    onRefresh: (() -> Unit)?,
    indicatorTop: Dp,
    content: @Composable BoxScope.() -> Unit,
) {
    if (onRefresh == null) {
        Box(Modifier.fillMaxSize(), content = content)
        return
    }
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize().testTag("settings-pull-refresh"),
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = indicatorTop),
            )
        },
        content = content,
    )
}

/**
 * Collapsing-large-title scaffold for **LazyColumn** settings sub-pages (dynamic lists). Uses the
 * exact mechanism of the Settings home page: a tall header spacer (item 0) holds the big title and
 * scrolls away 1:1, the overlay title rises glued to it. Provide list content via [content].
 */
@Composable
fun CollapsingSettingsLazyScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberSettingsLazyListState(),
    contentHorizontalPadding: Dp = 24.dp,
    contentBottomPadding: Dp = 32.dp,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val titleAreaHeight = SettingsTitleAreaHeight
    val titleTravel = settingsTitleTravel
    val titleTravelPx = with(LocalDensity.current) { titleTravel.toPx() }
    val fraction by remember(listState, titleTravelPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / titleTravelPx).coerceIn(0f, 1f)
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsPullRefresh(refreshing, onRefresh, statusBarTop) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .widthIn(max = SettingsContentMaxWidth)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .clearFocusOnTap()
                    .padding(horizontal = contentHorizontalPadding),
                contentPadding = PaddingValues(top = statusBarTop + SettingsBarHeight)
            ) {
                item { Spacer(modifier = Modifier.height(titleAreaHeight)) }
                content()
                item { Spacer(modifier = Modifier.height(contentBottomPadding)) }
            }
            CollapsingSettingsTitleBar(
                title = title,
                backDescription = stringResource(R.string.back),
                fraction = fraction,
                statusBarTop = statusBarTop,
                titleAreaHeight = titleAreaHeight,
                titleTravel = titleTravel,
                onBack = onBack,
                actions = actions
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                floatingActionButton()
            }
        }
    }
}
