package com.newoether.agora.ui.settings

import android.icu.text.CompactDecimalFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.UsageBar
import com.newoether.agora.data.UsageDay
import com.newoether.agora.data.UsagePeriod
import com.newoether.agora.data.UsageRecorder
import com.newoether.agora.data.UsageShare
import com.newoether.agora.data.UsageStats
import com.newoether.agora.data.modelAliasDisplayName
import com.newoether.agora.ui.chat.bottombar.LegendRow
import com.newoether.agora.ui.chat.bottombar.SegmentedBar
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.util.horizontalEdgeFade
import com.newoether.agora.viewmodel.ChatViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private enum class UsageMetric { TOKENS, REQUESTS }

/**
 * Settings > Usage: request-time ledger figures. A daily heatmap, all-time overview cards, then a
 * period's token cards and model breakdown.
 */
@Composable
fun SettingsUsagePage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val rows by viewModel.dataControl.usageRows.collectAsState()
    val refreshing by viewModel.dataControl.usageRefreshing.collectAsState()
    val revision by UsageRecorder.revision.collectAsState()
    val aliases by viewModel.settings.modelAliases.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()

    var metric by rememberSaveable { mutableStateOf(UsageMetric.TOKENS) }
    var period by rememberSaveable { mutableStateOf(UsagePeriod.WEEK) }

    // Entry and every ledger write while the page is open reload the rows.
    LaunchedEffect(revision) { viewModel.dataControl.refreshUsage() }

    val locale = Locale.getDefault()
    val compact = remember(locale) { CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT) }
    fun count(value: Long): String = compact.format(value)
    fun percent(value: Double): String = String.format(locale, "%.1f%%", value * 100)

    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val current = rows
    val overview = remember(current) { current?.let { UsageStats.overview(it, zone, today) } }
    val summary = remember(current, period) { current?.let { UsageStats.period(it, period, zone, today) } }
    // Every ledger write reloads the rows, so showing the counting state for each fast reload made
    // the figures blink. It appears only when a scan is slow, and then stays up long enough to read.
    var countingVisible by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(COUNTING_SHOW_DELAY_MS)
            countingVisible = true
        }
    }
    LaunchedEffect(countingVisible, refreshing) {
        if (countingVisible && !refreshing) {
            delay(COUNTING_MIN_VISIBLE_MS)
            countingVisible = false
        }
    }
    val stale = countingVisible && current != null
    val staleAlpha = statsStaleAlpha(stale)
    val motion = LocalAgoraMotionPolicy.current

    CollapsingSettingsScaffold(title = stringResource(R.string.settings_usage), onBack = onBack) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.usage_activity),
                items = listOf({
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        PillTabSwitcher(
                            tabs = listOf(
                                stringResource(R.string.usage_metric_tokens),
                                stringResource(R.string.usage_metric_requests),
                            ),
                            selectedIndex = metric.ordinal,
                            onSelect = { metric = UsageMetric.entries[it] },
                            tabHeight = 40.dp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.alpha(staleAlpha)) {
                            UsageHeatmap(
                                days = overview?.days.orEmpty(),
                                today = today,
                                metric = metric,
                                locale = locale,
                                format = ::count,
                                refreshing = countingVisible,
                            )
                        }
                    }
                }),
            )

            StatsCardGrid(
                title = stringResource(R.string.usage_overview),
                stale = stale,
                cards = listOf(
                    StatsCard(
                        Icons.Rounded.DataUsage,
                        stringResource(R.string.usage_total_tokens),
                        overview?.let { count(it.totalTokens) },
                    ),
                    StatsCard(
                        Icons.Rounded.SwapHoriz,
                        stringResource(R.string.usage_total_requests),
                        overview?.let { count(it.totalRequests) },
                    ),
                    StatsCard(
                        Icons.Rounded.LocalFireDepartment,
                        stringResource(R.string.usage_streak),
                        overview?.let { stringResource(R.string.usage_streak_days, it.currentStreakDays) },
                    ),
                    StatsCard(
                        Icons.Rounded.EmojiEvents,
                        stringResource(R.string.usage_longest_streak),
                        overview?.let { stringResource(R.string.usage_streak_days, it.longestStreakDays) },
                    ),
                ),
            )

            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.usage_tokens),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                PillTabSwitcher(
                    tabs = listOf(
                        stringResource(R.string.usage_period_week),
                        stringResource(R.string.usage_period_month),
                        stringResource(R.string.usage_period_quarter),
                        stringResource(R.string.usage_all),
                    ),
                    selectedIndex = period.ordinal,
                    onSelect = { period = UsagePeriod.entries[it] },
                )
                Spacer(Modifier.height(12.dp))
                StatsCardGrid(
                    title = null,
                    stale = stale,
                    cards = listOf(
                        StatsCard(
                            Icons.Rounded.Upload,
                            stringResource(R.string.usage_input),
                            summary?.let { count(it.inputTokens) },
                        ),
                        StatsCard(
                            Icons.Rounded.Download,
                            stringResource(R.string.usage_output),
                            summary?.let { count(it.outputTokens) },
                        ),
                        StatsCard(
                            Icons.Rounded.Cached,
                            stringResource(R.string.usage_cached),
                            summary?.let { count(it.cachedInputTokens) },
                        ),
                        StatsCard(
                            Icons.Rounded.Percent,
                            stringResource(R.string.usage_cache_hit),
                            summary?.let { s -> s.cacheHitRate?.let(::percent) ?: "—" },
                        ),
                    ),
                )
            }

            SettingsGroup(
                title = stringResource(R.string.usage_chart_daily),
                items = listOf({
                    Column(Modifier.fillMaxWidth().padding(16.dp).alpha(staleAlpha)) {
                        // A period change redraws every bar and moves the scroll range, so the old
                        // chart fades out while the new one fades in instead of snapping.
                        Crossfade(
                            targetState = period,
                            animationSpec = if (motion.allowSpatialTransitions) {
                                tween(CHART_PERIOD_FADE_MS)
                            } else {
                                snap()
                            },
                            label = "usageChartPeriod",
                        ) { shown ->
                            val shownSummary = if (shown == period) {
                                summary
                            } else {
                                current?.let { UsageStats.period(it, shown, zone, today) }
                            }
                            // Crossfade expects a single child; the chart emits the plot and its
                            // caption, and a bare slot stacked the caption onto the value axis.
                            Column(Modifier.fillMaxWidth()) {
                                UsageBarChart(
                                    bars = shownSummary?.bars.orEmpty(),
                                    today = today,
                                    locale = locale,
                                    format = ::count,
                                )
                            }
                        }
                    }
                }),
            )

            val modelLabel: (String) -> String = { modelAliasDisplayName(it, aliases, customProviders) }
            ShareGroup(
                title = stringResource(R.string.usage_by_model),
                shares = summary?.byModel,
                label = modelLabel,
                format = ::count,
                percent = ::percent,
                alpha = staleAlpha,
            )
        }
    }
}

/** Segmented bar plus legend of one breakdown. Shares beyond [MAX_SHARES] fold into "Other". */
@Composable
private fun ShareGroup(
    title: String,
    shares: List<UsageShare>?,
    label: (String) -> String,
    format: (Long) -> String,
    percent: (Double) -> String,
    alpha: Float,
) {
    val colors = statsSegmentColors()
    val otherColor = MaterialTheme.colorScheme.outlineVariant
    val otherLabel = stringResource(R.string.usage_other)
    SettingsGroup(
        title = title,
        items = listOf({
            Column(
                Modifier.fillMaxWidth().padding(16.dp).alpha(alpha),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val all = shares.orEmpty()
                val totalTokens = all.sumOf { it.tokens }
                // Without any reported tokens the split falls back to request counts.
                val weightOf: (UsageShare) -> Long = if (totalTokens > 0) {
                    { it.tokens }
                } else {
                    { it.requests }
                }
                val total = all.sumOf(weightOf).coerceAtLeast(1)
                // The bar's total, so the split reads against a figure before its parts. The figure
                // is the card's headline, so it is set larger than the legend values.
                Column {
                    Text(
                        stringResource(R.string.usage_total_tokens),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    if (shares == null) {
                        StatsPlaceholder(112.dp, 30.dp, Modifier.padding(vertical = 2.dp))
                    } else {
                        Text(
                            format(totalTokens),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                val shown = if (all.size > MAX_SHARES) all.take(MAX_SHARES - 1) else all
                val rest = all.drop(shown.size)
                val entries = shown.mapIndexed { index, share ->
                    Triple(label(share.key), weightOf(share), colors[index % colors.size])
                } + if (rest.isEmpty()) {
                    emptyList()
                } else {
                    listOf(Triple(otherLabel, rest.sumOf(weightOf), otherColor))
                }
                if (entries.isEmpty()) {
                    // Nothing to split yet: a shimmering capsule says "still counting" instead of a
                    // bare track that looks like a finished, empty bar.
                    Box(
                        Modifier.fillMaxWidth().height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(statsShimmerBrush()),
                    )
                } else {
                    SegmentedBar(
                        fractions = entries.map { it.second.toFloat() / total },
                        colors = entries.map { it.third },
                    )
                }
                if (shares == null) {
                    StatsPlaceholder(160.dp, 14.dp)
                } else if (entries.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        entries.forEach { (name, weight, color) ->
                            LegendRow(color, name, "${format(weight)} · ${percent(weight.toDouble() / total)}")
                        }
                    }
                }
            }
        }),
    )
}

@Composable
private fun UsageBarChart(
    bars: List<UsageBar>,
    today: LocalDate,
    locale: Locale,
    format: (Long) -> String,
) {
    var picked by remember(bars.firstOrNull()?.start, bars.size) { mutableStateOf<Int?>(null) }
    val barColor = MaterialTheme.colorScheme.primary
    val pickedColor = MaterialTheme.colorScheme.tertiary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisLineColor = MaterialTheme.colorScheme.outlineVariant
    val max = bars.maxOfOrNull { it.tokens }?.coerceAtLeast(1) ?: 1
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = axisColor)
    val tickFormat = remember(locale) { DateTimeFormatter.ofPattern("MMM d", locale) }
    val density = LocalDensity.current
    val gapPx = with(density) { BAR_CHART_BAR_GAP.toPx() }
    val labelBand = with(density) { BAR_CHART_LABEL_BAND.toPx() }
    val topInset = with(density) { BAR_CHART_TOP_INSET.toPx() }
    val scroll = rememberScrollState(initial = Int.MAX_VALUE)
    // The newest day is what people look for, so a scrollable chart opens at its right end and
    // returns there when the period or the data changes.
    LaunchedEffect(bars.firstOrNull()?.start, bars.size) {
        if (bars.size <= BAR_CHART_VISIBLE_DAYS) return@LaunchedEffect
        snapshotFlow { scroll.maxValue }.first { it > 0 }
        withFrameNanos { }
        scroll.scrollTo(scroll.maxValue)
    }
    val motion = LocalAgoraMotionPolicy.current
    val fadeSpec: AnimationSpec<Float> =
        if (motion.allowSpatialTransitions) tween(200) else snap()
    val leftFade by animateFloatAsState(
        targetValue = if (scroll.canScrollBackward) 1f else 0f,
        animationSpec = fadeSpec,
        label = "usageChartLeftFade",
    )
    val rightFade by animateFloatAsState(
        targetValue = if (scroll.canScrollForward) 1f else 0f,
        animationSpec = fadeSpec,
        label = "usageChartRightFade",
    )
    Row(Modifier.fillMaxWidth().height(BAR_CHART_PLOT_HEIGHT + BAR_CHART_LABEL_BAND)) {
        // Value axis: max, half and zero. It stays put while the bars scroll under it.
        Canvas(Modifier.width(BAR_CHART_AXIS_WIDTH).fillMaxHeight()) {
            if (bars.isEmpty()) return@Canvas
            val plotBottom = size.height - labelBand
            val plotHeight = plotBottom - topInset
            // Zero rides the baseline and the maximum sits at the top, matching the bars.
            listOf(0L, max / 2, max).forEachIndexed { tick, value ->
                val y = plotBottom - plotHeight * tick / 2f
                val label = measurer.measure(format(value), style = labelStyle, maxLines = 1, softWrap = false)
                drawText(
                    label,
                    topLeft = Offset(
                        size.width - 6.dp.toPx() - label.size.width,
                        (y - label.size.height / 2f)
                            .coerceIn(0f, (plotBottom - label.size.height).coerceAtLeast(0f)),
                    ),
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            // At most 30 days fit the viewport; longer periods keep one bar per day and scroll.
            val slots = bars.size.coerceIn(1, BAR_CHART_VISIBLE_DAYS)
            val slot = maxWidth / slots
            val contentWidth = slot * bars.size.toFloat()
            val contentWidthPx = with(density) { contentWidth.toPx() }
            val stepPx = contentWidthPx / bars.size.coerceAtLeast(1)
            val labelGap = with(density) { 8.dp.toPx() }
            // Every date is measured once per data change, not per frame, so scrolling stays cheap.
            val dateLabels = remember(bars, tickFormat, labelStyle) {
                bars.map { bar ->
                    measurer.measure(
                        bar.start.format(tickFormat),
                        style = labelStyle,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            val labelStep = remember(dateLabels, stepPx, labelGap) {
                if (dateLabels.isEmpty()) 1 else {
                    val widest = dateLabels.maxOf { it.size.width }
                    ceil((widest + labelGap) / stepPx).toInt().coerceAtLeast(1)
                }
            }
            // A label sits in its own slot and is skipped when it cannot fit there, so nothing is
            // clamped into its neighbour. The newest day is always marked by dropping the label it
            // would touch.
            val datePlacements = remember(dateLabels, labelStep, stepPx, contentWidthPx, labelGap) {
                val placed = ArrayList<Pair<Int, Float>>(dateLabels.size / labelStep + 1)
                dateLabels.forEachIndexed { index, label ->
                    if (index % labelStep != 0) return@forEachIndexed
                    val x = index * stepPx + (stepPx - label.size.width) / 2f
                    if (x < 0f || x + label.size.width > contentWidthPx) return@forEachIndexed
                    placed += index to x
                }
                if (dateLabels.isNotEmpty()) {
                    if (placed.lastOrNull()?.first == dateLabels.lastIndex) {
                        placed.removeAt(placed.lastIndex)
                    }
                    val lastX = contentWidthPx - dateLabels.last().size.width
                    while (placed.isNotEmpty()) {
                        val (index, x) = placed.last()
                        if (x + dateLabels[index].size.width + labelGap > lastX) {
                            placed.removeAt(placed.lastIndex)
                        } else {
                            break
                        }
                    }
                    placed += dateLabels.lastIndex to lastX
                }
                placed
            }
            // Grid lines belong to the viewport, not to the scrolling content.
            Canvas(Modifier.fillMaxSize()) {
                val plotBottom = size.height - labelBand
                val plotHeight = plotBottom - topInset
                listOf(2, 1, 0).forEach { tick ->
                    val y = plotBottom - plotHeight * tick / 2f
                    drawLine(
                        if (tick == 0) axisLineColor else gridColor,
                        Offset(0f, y),
                        Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            Box(
                Modifier.fillMaxSize()
                    .horizontalEdgeFade(edgeFadeDp = 24f, leftWeight = leftFade, rightWeight = rightFade)
                    .horizontalScroll(scroll),
            ) {
                Canvas(
                    Modifier
                        .width(contentWidth)
                        .fillMaxHeight()
                        .pointerInput(bars) {
                            detectTapGestures { offset ->
                                if (bars.isEmpty()) return@detectTapGestures
                                val step = size.width.toFloat() / bars.size
                                val index = (offset.x / step).toInt().coerceIn(0, bars.lastIndex)
                                picked = if (picked == index) null else index
                            }
                        },
                ) {
                    if (bars.isEmpty()) return@Canvas
                    val plotBottom = size.height - labelBand
                    val plotHeight = plotBottom - topInset
                    val step = size.width / bars.size
                    val barWidth = (step - gapPx).coerceAtLeast(1f)
                    val stub = 2.dp.toPx()
                    val radius = CornerRadius(
                        (barWidth / 2).coerceAtMost(BAR_CHART_CORNER_RADIUS.toPx()),
                    )
                    bars.forEachIndexed { index, bar ->
                        val left = index * step + (step - barWidth) / 2f
                        val height = if (bar.tokens > 0) {
                            (plotHeight * bar.tokens / max).coerceAtLeast(stub)
                        } else {
                            stub
                        }
                        val color = when {
                            bar.tokens <= 0 -> emptyColor
                            picked == index -> pickedColor
                            else -> barColor
                        }
                        drawRoundRect(color, Offset(left, plotBottom - height), Size(barWidth, height), radius)
                    }

                    // Date axis: placements were computed against the content width, so drawing is
                    // just text at fixed offsets.
                    datePlacements.forEach { (index, x) ->
                        drawText(dateLabels[index], topLeft = Offset(x, plotBottom + 4.dp.toPx()))
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    val shown = picked?.let(bars::getOrNull) ?: bars.lastOrNull()
    val shortDate = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    Text(
        stringResource(
            R.string.usage_day_tokens,
            (shown?.start ?: today).format(shortDate),
            format(shown?.tokens ?: 0),
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun UsageHeatmap(
    days: Map<LocalDate, UsageDay>,
    today: LocalDate,
    metric: UsageMetric,
    locale: Locale,
    format: (Long) -> String,
    refreshing: Boolean,
) {
    val firstDay: DayOfWeek = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val endWeekStart = today.with(TemporalAdjusters.previousOrSame(firstDay))
    val start = endWeekStart.minusWeeks((HEATMAP_WEEKS - 1).toLong())
    fun valueOf(day: UsageDay?, metric: UsageMetric): Long = when (metric) {
        UsageMetric.TOKENS -> day?.tokens ?: 0L
        UsageMetric.REQUESTS -> day?.requests ?: 0L
    }
    // Quartile thresholds over non-zero days keep one heavy day from washing out the rest.
    fun thresholdsOf(metric: UsageMetric): List<Long> {
        val values = days.values.map { valueOf(it, metric) }.filter { it > 0 }.sorted()
        return if (values.isEmpty()) {
            emptyList()
        } else {
            listOf(0.25, 0.5, 0.75).map { values[((values.size - 1) * it).toInt()] }
        }
    }
    fun level(value: Long, thresholds: List<Long>): Int =
        if (value <= 0) 0 else 1 + thresholds.count { value > it }
    val thresholds = remember(days, metric) { thresholdsOf(metric) }
    // A metric switch re-levels every dot at once, so the grid keeps the colours that are on screen
    // and blends them to the new ramp once the new figures have settled.
    var lastMetric by remember { mutableStateOf(metric) }
    val previousMetric = remember(metric) { lastMetric }
    SideEffect { lastMetric = metric }
    val previousThresholds = remember(previousMetric, days) {
        if (previousMetric == null) emptyList() else thresholdsOf(previousMetric)
    }
    // A fresh animatable per metric starts on the outgoing ramp, so the first frame of a switch
    // cannot paint the target colours before the blend begins.
    val blend = remember(metric) { Animatable(0f) }

    var picked by remember { mutableStateOf<LocalDate?>(null) }
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val full = MaterialTheme.colorScheme.primary
    val todayOutline = MaterialTheme.colorScheme.outline
    // Collar of a picked dot: it has to contrast with every level, including the brightest one.
    val pickedCollar = MaterialTheme.colorScheme.onSurface
    fun shade(level: Int): Color = if (level == 0) empty else lerp(empty, full, 0.25f + 0.75f * level / 4f)
    // A dot keeps the previous metric's colour and fades to the new one, so a tab switch reads as a
    // wash over the calendar rather than a hard repaint.
    fun dotColor(day: UsageDay?): Color {
        val target = shade(level(valueOf(day, metric), thresholds))
        val from = previousMetric
        if (from == null || from == metric) return target
        return lerp(shade(level(valueOf(day, from), previousThresholds)), target, blend.value)
    }
    val cell: Dp = 12.dp
    val gap: Dp = 3.dp
    val step = cell + gap
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val motion = LocalAgoraMotionPolicy.current
    // Waiting for the count to finish keeps the fade off a grid that still shows the previous
    // figures: the dots take their new colours only once the new numbers are in, then blend over.
    LaunchedEffect(metric, refreshing) {
        val switched = previousMetric != null && previousMetric != metric
        if (!switched || refreshing) return@LaunchedEffect
        if (motion.allowSpatialTransitions) {
            blend.animateTo(1f, tween(HEATMAP_METRIC_FADE_MS))
        } else {
            blend.snapTo(1f)
        }
    }
    val fadeSpec: AnimationSpec<Float> =
        if (motion.allowSpatialTransitions) tween(200) else snap()
    val leftFade by animateFloatAsState(
        targetValue = if (scroll.canScrollBackward) 1f else 0f,
        animationSpec = fadeSpec,
        label = "heatmapLeftFade",
    )
    val rightFade by animateFloatAsState(
        targetValue = if (scroll.canScrollForward) 1f else 0f,
        animationSpec = fadeSpec,
        label = "heatmapRightFade",
    )
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row {
        // Weekday labels on alternate rows, fixed while the weeks scroll.
        Column(Modifier.padding(top = MONTH_LABEL_HEIGHT, end = 6.dp)) {
            for (row in 0 until 7) {
                Box(Modifier.height(step), contentAlignment = Alignment.CenterStart) {
                    if (row % 2 == 1) {
                        Text(
                            firstDay.plus(row.toLong()).getDisplayName(TextStyle.SHORT, locale),
                            style = labelStyle,
                            color = labelColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Box(
            Modifier.weight(1f)
                // Same edge mask as the attachment row and LaTeX block: the weeks scroll, so both
                // ends fade instead of slicing a column of dots.
                .horizontalEdgeFade(edgeFadeDp = 24f, leftWeight = leftFade, rightWeight = rightFade)
                .horizontalScroll(scroll),
        ) {
            // Trailing margin keeps the last column's today ring inside the viewport once scrolled
            // to the end, instead of being cut by the scroll bounds.
            val width = step * HEATMAP_WEEKS - gap + HEATMAP_EDGE_MARGIN
            val height = step * 7 - gap + HEATMAP_EDGE_MARGIN
            Column {
                Box(Modifier.width(width).height(MONTH_LABEL_HEIGHT)) {
                    // A month label sits over the first week that contains that month's first day.
                    for (column in 0 until HEATMAP_WEEKS) {
                        val weekStart = start.plusWeeks(column.toLong())
                        val monthStart = (0L..6L).map(weekStart::plusDays).firstOrNull { it.dayOfMonth == 1 }
                        if (monthStart == null || monthStart.isAfter(today) || column > HEATMAP_WEEKS - 2) continue
                        Text(
                            monthStart.month.getDisplayName(TextStyle.SHORT, locale),
                            style = labelStyle,
                            color = labelColor,
                            maxLines = 1,
                            modifier = Modifier.offset(x = step * column),
                        )
                    }
                }
                Canvas(
                    Modifier.size(width, height).pointerInput(start) {
                        detectTapGestures { offset ->
                            val stepPx = with(density) { step.toPx() }
                            val column = (offset.x / stepPx).toInt()
                            val row = (offset.y / stepPx).toInt()
                            val date = start.plusWeeks(column.toLong()).plusDays(row.toLong())
                            picked = if (row in 0..6 && !date.isAfter(today) && date != picked) date else null
                        }
                    },
                ) {
                    val radius = cell.toPx() / 2
                    val stepPx = step.toPx()
                    for (column in 0 until HEATMAP_WEEKS) {
                        for (row in 0 until 7) {
                            val date = start.plusWeeks(column.toLong()).plusDays(row.toLong())
                            if (date.isAfter(today)) continue
                            val center = Offset(column * stepPx + radius, row * stepPx + radius)
                            if (date == picked) {
                                // Contrasting collar plus a shrunken dot: a plain stroke in the dot's
                                // own colour was invisible, especially on the brightest levels.
                                drawCircle(pickedCollar, radius, center)
                                drawCircle(dotColor(days[date]), radius - 2.dp.toPx(), center)
                            } else {
                                drawCircle(dotColor(days[date]), radius, center)
                            }
                            if (date == today && date != picked) {
                                drawCircle(todayOutline, radius, center, style = Stroke(1.5.dp.toPx()))
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val shown = picked ?: today
        val dateText = remember(shown, locale) {
            shown.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }
        Text(
            stringResource(
                if (metric == UsageMetric.TOKENS) R.string.usage_day_tokens else R.string.usage_day_requests,
                dateText,
                format(valueOf(days[shown], metric)),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        // The indicator trails the caption, so fading it in never shifts the date sideways.
        StatsRefreshStatus(visible = refreshing)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.usage_less), style = labelStyle, color = labelColor)
        Spacer(Modifier.width(4.dp))
        for (lvl in 0..4) {
            Canvas(Modifier.padding(horizontal = 1.5.dp).size(10.dp)) { drawCircle(shade(lvl)) }
        }
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.usage_more), style = labelStyle, color = labelColor)
    }
}

private const val HEATMAP_WEEKS = 53
/** Fade between the token and the request ramps of the calendar. */
private const val HEATMAP_METRIC_FADE_MS = 240
private val BAR_CHART_PLOT_HEIGHT = 120.dp
private val BAR_CHART_LABEL_BAND = 18.dp
private val BAR_CHART_TOP_INSET = 10.dp
private val BAR_CHART_AXIS_WIDTH = 40.dp
/** Days that fit the plot before the chart starts scrolling; also the slowest zoom-out. */
private const val BAR_CHART_VISIBLE_DAYS = 30
/** Fade between periods; the bars, the date axis and the scroll range all change at once. */
private const val CHART_PERIOD_FADE_MS = 200
private val BAR_CHART_BAR_GAP = 3.dp
private val BAR_CHART_CORNER_RADIUS = 6.dp
private const val COUNTING_SHOW_DELAY_MS = 200L
private const val COUNTING_MIN_VISIBLE_MS = 400L
private val HEATMAP_EDGE_MARGIN = 8.dp
private const val MAX_SHARES = 6
private val MONTH_LABEL_HEIGHT = 18.dp
