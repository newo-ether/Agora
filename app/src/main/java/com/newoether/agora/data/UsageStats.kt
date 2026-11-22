package com.newoether.agora.data

import com.newoether.agora.data.local.UsageLedgerRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class UsageDay(
    val tokens: Long,
    val requests: Long,
)

/** All-time figures for the heatmap and the overview cards. */
data class UsageOverview(
    /** Only days with at least one request. */
    val days: Map<LocalDate, UsageDay>,
    val totalTokens: Long,
    val totalRequests: Long,
    val currentStreakDays: Int,
    val longestStreakDays: Int,
)

enum class UsagePeriod(val days: Long?) {
    WEEK(7),
    MONTH(30),
    QUARTER(90),
    ALL(null),
}

/** One bar of the period chart: the tokens of the days starting at [start]. */
data class UsageBar(
    val start: LocalDate,
    val tokens: Long,
)

/** Tokens attributed to one model within a period. */
data class UsageShare(
    val key: String,
    val tokens: Long,
    val requests: Long,
)

data class UsagePeriodSummary(
    val totalTokens: Long,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val outputTokens: Long,
    /** Cached share of input among requests that reported a cache split, or null without any. */
    val cacheHitRate: Double?,
    val bars: List<UsageBar>,
    /** Largest first. */
    val byModel: List<UsageShare>,
)

/** Aggregates ledger rows for the Usage page in the device time zone. */
object UsageStats {
    /** Above this many days a period chart groups days into weeks so bars stay readable. */
    private const val MAX_DAILY_BARS = 90

    private fun UsageLedgerRow.day(zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(bucket * UsageRecorder.BUCKET_MILLIS).atZone(zone).toLocalDate()

    fun overview(rows: List<UsageLedgerRow>, zone: ZoneId, today: LocalDate): UsageOverview {
        val tokens = HashMap<LocalDate, Long>()
        val requests = HashMap<LocalDate, Long>()
        var totalTokens = 0L
        var totalRequests = 0L
        for (row in rows) {
            val day = row.day(zone)
            tokens.merge(day, row.totalTokens, Long::plus)
            requests.merge(day, row.requests, Long::plus)
            totalTokens += row.totalTokens
            totalRequests += row.requests
        }
        val days = requests.filterValues { it > 0 }
            .mapValues { (day, count) -> UsageDay(tokens[day] ?: 0L, count) }
        val (current, longest) = streaks(days.keys, today)
        return UsageOverview(days, totalTokens, totalRequests, current, longest)
    }

    fun period(
        rows: List<UsageLedgerRow>,
        period: UsagePeriod,
        zone: ZoneId,
        today: LocalDate,
    ): UsagePeriodSummary {
        val periodDays = period.days
        val inPeriod = rows.map { it to it.day(zone) }.filter { (_, day) ->
            !day.isAfter(today) && (periodDays == null || !day.isBefore(today.minusDays(periodDays - 1)))
        }
        val first = when {
            periodDays != null -> today.minusDays(periodDays - 1)
            else -> inPeriod.minOfOrNull { it.second } ?: today
        }

        var input = 0L
        var cached = 0L
        var output = 0L
        var total = 0L
        var cacheReportedInput = 0L
        val tokensByDay = HashMap<LocalDate, Long>()
        val models = HashMap<String, LongArray>()
        for ((row, day) in inPeriod) {
            input += row.inputTokens
            cached += row.cachedInputTokens
            output += row.outputTokens
            total += row.totalTokens
            cacheReportedInput += row.cacheReportedInputTokens
            tokensByDay.merge(day, row.totalTokens, Long::plus)
            models.getOrPut(row.model) { LongArray(2) }.add(row)
        }

        val span = ChronoUnit.DAYS.between(first, today) + 1
        val binDays = if (span > MAX_DAILY_BARS) 7L else 1L
        val bars = generateSequence(first) { it.plusDays(binDays) }
            .takeWhile { !it.isAfter(today) }
            .map { start ->
                val tokens = (0 until binDays).sumOf { offset -> tokensByDay[start.plusDays(offset)] ?: 0L }
                UsageBar(start, tokens)
            }
            .toList()

        return UsagePeriodSummary(
            totalTokens = total,
            inputTokens = input,
            cachedInputTokens = cached,
            outputTokens = output,
            cacheHitRate = if (cacheReportedInput > 0) {
                (cached.toDouble() / cacheReportedInput).coerceIn(0.0, 1.0)
            } else {
                null
            },
            bars = bars,
            byModel = models.toShares(),
        )
    }

    private fun LongArray.add(row: UsageLedgerRow) {
        this[0] += row.totalTokens
        this[1] += row.requests
    }

    private fun Map<String, LongArray>.toShares(): List<UsageShare> =
        map { (key, totals) -> UsageShare(key, totals[0], totals[1]) }
            .sortedWith(
                compareByDescending<UsageShare> { it.tokens }
                    .thenByDescending { it.requests }
                    .thenBy { it.key },
            )

    /**
     * The current streak ends today, or yesterday when today has no use yet, so an unfinished
     * day does not reset it to zero.
     */
    internal fun streaks(active: Set<LocalDate>, today: LocalDate): Pair<Int, Int> {
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (day in active.sorted()) {
            run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
            if (run > longest) longest = run
            previous = day
        }
        var cursor = if (today in active) today else today.minusDays(1)
        var current = 0
        while (cursor in active) {
            current++
            cursor = cursor.minusDays(1)
        }
        return current to longest
    }
}
