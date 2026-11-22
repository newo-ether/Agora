package com.newoether.agora.data

import com.newoether.agora.data.local.UsageLedgerRow
import com.newoether.agora.data.local.UsageLedgerSource
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageStatsTest {
    private fun row(
        epochMillis: Long,
        model: String = "OpenAI:gpt-x",
        source: String = UsageLedgerSource.CHAT,
        requests: Long = 1,
        tokens: Long = 100,
        input: Long = 80,
        cached: Long = 40,
        output: Long = 20,
        cacheReported: Long = input,
    ) = UsageLedgerRow(
        bucket = epochMillis / UsageRecorder.BUCKET_MILLIS,
        provider = model.substringBefore(':'),
        model = model,
        source = source,
        requests = requests,
        totalTokens = tokens,
        inputTokens = input,
        cachedInputTokens = cached,
        outputTokens = output,
        cacheReportedInputTokens = cacheReported,
    )

    private fun millis(date: String, hour: Int, zone: ZoneId) =
        LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.parse("2026-11-02")

    @Test
    fun `overview assigns buckets to the local day`() {
        val rows = listOf(
            // 23:00 local on the 1st is 15:00 UTC; it must stay on the 1st, not move a day.
            row(millis("2026-11-01", 23, shanghai)),
            row(millis("2026-11-02", 9, shanghai), requests = 2, tokens = 500),
            // A row without requests does not mark a day active.
            row(millis("2026-10-20", 9, shanghai), requests = 0, tokens = 0),
        )
        val overview = UsageStats.overview(rows, shanghai, today)
        assertEquals(setOf(LocalDate.parse("2026-11-01"), today), overview.days.keys)
        assertEquals(UsageDay(500, 2), overview.days[today])
        assertEquals(600, overview.totalTokens)
        assertEquals(3, overview.totalRequests)
        assertEquals(2, overview.currentStreakDays)
        assertEquals(2, overview.longestStreakDays)
    }

    @Test
    fun `period keeps only its days and ranks models`() {
        val rows = listOf(
            row(millis("2026-10-27", 12, shanghai), tokens = 9_999), // the 7-day window starts on the 27th
            row(millis("2026-10-26", 12, shanghai), tokens = 1_000_000), // outside it
            row(millis("2026-11-02", 9, shanghai), model = "DeepSeek:flash", source = UsageLedgerSource.TASK, tokens = 500, input = 400, cached = 0, output = 100),
            row(millis("2026-11-02", 10, shanghai), source = UsageLedgerSource.TITLE, tokens = 1),
        )
        val week = UsageStats.period(rows, UsagePeriod.WEEK, shanghai, today)
        assertEquals(10_500, week.totalTokens)
        assertEquals(560, week.inputTokens)
        assertEquals(80, week.cachedInputTokens)
        assertEquals(80.0 / 560.0, week.cacheHitRate!!, 1e-9)
        assertEquals(7, week.bars.size)
        assertEquals(LocalDate.parse("2026-10-27"), week.bars.first().start)
        assertEquals(501, week.bars.last().tokens)
        assertEquals(listOf("OpenAI:gpt-x", "DeepSeek:flash"), week.byModel.map { it.key })
        assertEquals(listOf(10_000L, 500L), week.byModel.map { it.tokens })
        assertEquals(listOf(2L, 1L), week.byModel.map { it.requests })

        val all = UsageStats.period(rows, UsagePeriod.ALL, shanghai, today)
        assertEquals(1_010_500, all.totalTokens)
        assertEquals(LocalDate.parse("2026-10-26"), all.bars.first().start)
    }

    @Test
    fun `long periods bin bars by week`() {
        val start = LocalDate.parse("2026-01-01")
        val rows = listOf(row(start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), tokens = 7))
        val end = start.plusDays(99)
        val all = UsageStats.period(rows, UsagePeriod.ALL, ZoneOffset.UTC, end)
        assertEquals(15, all.bars.size)
        assertEquals(7, all.bars.first().tokens)
        assertEquals(7, all.bars.sumOf { it.tokens })
        // A 90-day period stays daily.
        assertEquals(90, UsageStats.period(rows, UsagePeriod.QUARTER, ZoneOffset.UTC, end).bars.size)
    }

    @Test
    fun `cache rate is null without reported input`() {
        val rows = listOf(row(0L, cached = 0, cacheReported = 0))
        val summary = UsageStats.period(rows, UsagePeriod.ALL, ZoneOffset.UTC, LocalDate.parse("1970-01-01"))
        assertNull(summary.cacheHitRate)
    }

    @Test
    fun `streak tolerates an empty today and finds the longest run`() {
        val d = LocalDate.parse("2026-11-10")
        val active = setOf(d.minusDays(1), d.minusDays(2), d.minusDays(5), d.minusDays(6), d.minusDays(7), d.minusDays(8))
        assertEquals(2 to 4, UsageStats.streaks(active, d))
        assertEquals(0 to 4, UsageStats.streaks(active, d.plusDays(1)))
    }
}
