package com.newoether.agora.ui.settings

import android.icu.text.CompactDecimalFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.UsageDay
import com.newoether.agora.data.UsagePeriod
import com.newoether.agora.data.UsageRecorder
import com.newoether.agora.data.UsageShare
import com.newoether.agora.data.UsageStats
import com.newoether.agora.data.modelAliasDisplayName
import com.newoether.agora.ui.chat.bottombar.LegendRow
import com.newoether.agora.ui.chat.bottombar.SegmentedBar
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
    var period by rememberSaveable { mutableStateOf(UsagePeriod.MONTH) }

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
    val stale = refreshing && current != null
    val staleAlpha = statsStaleAlpha(stale)

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
                                refreshing = refreshing,
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
                        Icons.Default.DataUsage,
                        stringResource(R.string.usage_total_tokens),
                        overview?.let { count(it.totalTokens) },
                    ),
                    StatsCard(
                        Icons.Default.Bolt,
                        stringResource(R.string.usage_total_requests),
                        overview?.let { count(it.totalRequests) },
                    ),
                    StatsCard(
                        Icons.Default.LocalFireDepartment,
                        stringResource(R.string.usage_streak),
                        overview?.let { stringResource(R.string.usage_streak_days, it.currentStreakDays) },
                    ),
                    StatsCard(
                        Icons.Default.EmojiEvents,
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
                            Icons.Default.Upload,
                            stringResource(R.string.usage_input),
                            summary?.let { count(it.inputTokens) },
                        ),
                        StatsCard(
                            Icons.Default.Download,
                            stringResource(R.string.usage_output),
                            summary?.let { count(it.outputTokens) },
                        ),
                        StatsCard(
                            Icons.Default.Cached,
                            stringResource(R.string.usage_cached),
                            summary?.let { count(it.cachedInputTokens) },
                        ),
                        StatsCard(
                            Icons.Default.Percent,
                            stringResource(R.string.usage_cache_hit),
                            summary?.let { s -> s.cacheHitRate?.let(::percent) ?: "—" },
                        ),
                    ),
                )
            }

            val modelLabel: (String) -> String = { modelAliasDisplayName(it, aliases, customProviders) }
            ShareGroup(
                title = stringResource(R.string.usage_by_model),
                shares = summary?.byModel,
                label = modelLabel,
                format = ::count,
                percent = ::percent,
                alpha = staleAlpha,
            )

            Text(
                stringResource(R.string.usage_missing_tokens_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
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
                // The bar's total, so the split reads against a figure before its parts.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.usage_total_tokens),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (shares == null) {
                        StatsPlaceholder(72.dp, 16.dp)
                    } else {
                        Text(
                            format(totalTokens),
                            style = MaterialTheme.typography.labelLarge,
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
    fun valueOf(day: UsageDay?): Long = when (metric) {
        UsageMetric.TOKENS -> day?.tokens ?: 0L
        UsageMetric.REQUESTS -> day?.requests ?: 0L
    }
    // Quartile thresholds over non-zero days keep one heavy day from washing out the rest.
    val thresholds = remember(days, metric) {
        val values = days.values.map(::valueOf).filter { it > 0 }.sorted()
        if (values.isEmpty()) emptyList() else listOf(0.25, 0.5, 0.75).map { values[((values.size - 1) * it).toInt()] }
    }
    fun level(value: Long): Int = if (value <= 0) 0 else 1 + thresholds.count { value > it }

    var picked by remember { mutableStateOf<LocalDate?>(null) }
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val full = MaterialTheme.colorScheme.primary
    val todayOutline = MaterialTheme.colorScheme.outline
    // Collar of a picked dot: it has to contrast with every level, including the brightest one.
    val pickedCollar = MaterialTheme.colorScheme.onSurface
    fun shade(level: Int): Color = if (level == 0) empty else lerp(empty, full, 0.25f + 0.75f * level / 4f)
    val cell: Dp = 12.dp
    val gap: Dp = 3.dp
    val step = cell + gap
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
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
        Box(Modifier.weight(1f).horizontalScroll(scroll)) {
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
                                drawCircle(shade(level(valueOf(days[date]))), radius - 2.dp.toPx(), center)
                            } else {
                                drawCircle(shade(level(valueOf(days[date]))), radius, center)
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
        // Counting sits on the footer line so the pills above keep a stable width.
        if (refreshing) {
            StatsRefreshStatus(visible = true)
            Spacer(Modifier.width(8.dp))
        }
        val shown = picked ?: today
        val dateText = remember(shown, locale) {
            shown.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }
        Text(
            stringResource(
                if (metric == UsageMetric.TOKENS) R.string.usage_day_tokens else R.string.usage_day_requests,
                dateText,
                format(valueOf(days[shown])),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
private val HEATMAP_EDGE_MARGIN = 8.dp
private const val MAX_SHARES = 6
private val MONTH_LABEL_HEIGHT = 18.dp
