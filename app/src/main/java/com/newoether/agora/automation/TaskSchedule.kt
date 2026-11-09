package com.newoether.agora.automation

import java.util.Calendar

/**
 * The user-facing shape of a task's schedule, and its lossless mapping to the storage the
 * scheduler actually runs on.
 *
 * Storage is a 5-field cron for every recurring type. ONCE cannot be a cron — a 5-field
 * expression has no year, so "March 3rd at 09:00" would silently mean *every* March 3rd — so a
 * one-shot carries an absolute epoch in [TaskEntity.runAt] and leaves cronExpr blank.
 *
 * A cron that predates this model (or was hand-written) may not map to any type; [parse] returns
 * null for those, and the editor keeps the raw expression untouched until the user picks a type.
 */
enum class ScheduleType { ONCE, DAILY, WEEKLY, MONTHLY, YEARLY }

/** True when the task carries a schedule at all — a recurring cron OR a one-shot instant.
 *  Everything that used to test `cronExpr.isNotBlank()` must go through this, or one-shots
 *  read as unscheduled and are silently skipped. */
fun com.newoether.agora.data.local.TaskEntity.hasSchedule(): Boolean =
    cronExpr.isNotBlank() || (runAt?.let { it > 0L } == true)

/**
 * @param daysOfWeek WEEKLY only. 0 = Sunday … 6 = Saturday, matching cron's day-of-week field.
 * @param dayOfMonth MONTHLY / YEARLY: 1..31.
 * @param month YEARLY only: 1..12.
 * @param onceAtMillis ONCE only: the absolute instant to fire.
 */
data class TaskSchedule(
    val type: ScheduleType,
    val hour: Int,
    val minute: Int,
    val daysOfWeek: Set<Int> = emptySet(),
    val dayOfMonth: Int = 1,
    val month: Int = 1,
    val onceAtMillis: Long = 0L,
) {
    /** The cron this schedule runs on, or "" for a one-shot (which uses [runAt] instead). */
    fun toCron(): String = when (type) {
        ScheduleType.ONCE -> ""
        ScheduleType.DAILY -> "$minute $hour * * *"
        // An empty selection would produce a malformed field; treat it as "every day", which is
        // what a weekly schedule with nothing ticked can only sensibly mean.
        ScheduleType.WEEKLY ->
            if (daysOfWeek.isEmpty()) "$minute $hour * * *"
            else "$minute $hour * * ${daysOfWeek.sorted().joinToString(",")}"
        ScheduleType.MONTHLY -> "$minute $hour $dayOfMonth * *"
        ScheduleType.YEARLY -> "$minute $hour $dayOfMonth $month *"
    }

    /** The one-shot epoch, or null for a recurring schedule. */
    fun toRunAt(): Long? = onceAtMillis.takeIf { type == ScheduleType.ONCE && it > 0L }

    /**
     * Switches recurrence while keeping everything the new type can still use. Fields the old
     * type did not have are seeded from today rather than left at a default the user never chose
     * (switching Daily → Monthly on the 23rd should propose the 23rd, not the 1st).
     */
    fun switchedTo(newType: ScheduleType): TaskSchedule {
        if (newType == type) return this
        val today = Calendar.getInstance()
        val seeded = copy(
            type = newType,
            daysOfWeek = daysOfWeek.ifEmpty { setOf(today.get(Calendar.DAY_OF_WEEK) - 1) },
            dayOfMonth = dayOfMonth.takeIf { parseHadDate } ?: today.get(Calendar.DAY_OF_MONTH),
            month = month.takeIf { parseHadDate } ?: (today.get(Calendar.MONTH) + 1),
        )
        // ONCE needs a concrete future instant. A date-less type defaults to the next day when
        // today's time has passed; a type that already carried month/day advances one year.
        return if (newType == ScheduleType.ONCE) {
            val exact = seeded.withOnceAt(
                today.get(Calendar.YEAR),
                seeded.month,
                seeded.dayOfMonth,
            )
            if (exact.onceAtMillis > System.currentTimeMillis()) {
                exact
            } else {
                val next = Calendar.getInstance().apply {
                    timeInMillis = exact.onceAtMillis
                    add(if (parseHadDate) Calendar.YEAR else Calendar.DAY_OF_MONTH, 1)
                }
                exact.withOnceAt(
                    next.get(Calendar.YEAR),
                    next.get(Calendar.MONTH) + 1,
                    next.get(Calendar.DAY_OF_MONTH),
                )
            }
        } else {
            seeded.copy(onceAtMillis = 0L)
        }
    }

    /** True when this instance already carries a real calendar date (monthly/yearly/once). */
    private val parseHadDate: Boolean
        get() = type == ScheduleType.MONTHLY || type == ScheduleType.YEARLY || type == ScheduleType.ONCE

    /** Rebuilds the one-shot instant from the exact calendar date plus current time-of-day.
     * Past dates stay past so validation can reject them instead of silently changing the year. */
    fun withOnceAt(year: Int, month: Int, day: Int): TaskSchedule {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return copy(dayOfMonth = day, month = month, onceAtMillis = cal.timeInMillis)
    }

    /** Applies a new time-of-day, re-deriving the one-shot instant when this is a ONCE schedule. */
    fun withTime(newHour: Int, newMinute: Int): TaskSchedule {
        val next = copy(hour = newHour, minute = newMinute)
        if (type != ScheduleType.ONCE) return next
        val base = Calendar.getInstance().apply {
            if (onceAtMillis > 0L) timeInMillis = onceAtMillis
        }
        return next.withOnceAt(
            base.get(Calendar.YEAR),
            base.get(Calendar.MONTH) + 1,
            base.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** "On" row value for the date-bearing types. ONCE shows the full date; YEARLY omits the
     *  year, which cron cannot store anyway. */
    fun formatOnDate(): String {
        val cal = Calendar.getInstance()
        if (type == ScheduleType.ONCE && onceAtMillis > 0L) {
            cal.timeInMillis = onceAtMillis
        } else {
            // Use a fixed leap year for a yearless display. Formatting February 29 against the
            // current year would normalize it to March 1 whenever the current year is not leap.
            cal.apply {
                clear()
                set(Calendar.YEAR, 2000)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, dayOfMonth)
            }
        }
        val pattern = if (type == ScheduleType.ONCE) "yyyy-MM-dd" else "MMM d"
        return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(cal.time)
    }

    companion object {
        /** Default for a brand-new task: every day at 09:00. */
        fun default(): TaskSchedule = TaskSchedule(ScheduleType.DAILY, hour = 9, minute = 0)

        /**
         * Reads a stored schedule back into the editor model, or null when [cronExpr] is a valid
         * cron that this model cannot express (e.g. the legacy "0 * * * *" hourly preset, or a
         * hand-written step expression). Callers must leave such expressions alone rather than
         * rewriting them into an approximation.
         */
        fun parse(cronExpr: String, runAt: Long?): TaskSchedule? {
            if (runAt != null && runAt > 0L) {
                val cal = Calendar.getInstance().apply { timeInMillis = runAt }
                return TaskSchedule(
                    type = ScheduleType.ONCE,
                    hour = cal.get(Calendar.HOUR_OF_DAY),
                    minute = cal.get(Calendar.MINUTE),
                    dayOfMonth = cal.get(Calendar.DAY_OF_MONTH),
                    month = cal.get(Calendar.MONTH) + 1,
                    onceAtMillis = runAt,
                )
            }
            val parts = cronExpr.trim().split(Regex("\\s+"))
            if (parts.size != 5) return null
            val (minField, hourField, domField, monthField, dowField) = parts
            // Only a single literal minute/hour maps to an "at HH:MM" row.
            val minute = minField.singleCronValue(0, 59) ?: return null
            val hour = hourField.singleCronValue(0, 23) ?: return null

            val domAny = domField == "*"
            val monthAny = monthField == "*"
            val dowAny = dowField == "*"

            return when {
                domAny && monthAny && dowAny ->
                    TaskSchedule(ScheduleType.DAILY, hour, minute)

                domAny && monthAny -> {
                    val days = dowField.cronValueList(0, 7)?.map { if (it == 7) 0 else it }?.toSet()
                        ?: return null
                    TaskSchedule(ScheduleType.WEEKLY, hour, minute, daysOfWeek = days)
                }

                dowAny && monthAny -> {
                    val dom = domField.singleCronValue(1, 31) ?: return null
                    TaskSchedule(ScheduleType.MONTHLY, hour, minute, dayOfMonth = dom)
                }

                dowAny -> {
                    val dom = domField.singleCronValue(1, 31) ?: return null
                    val month = monthField.singleCronValue(1, 12) ?: return null
                    TaskSchedule(ScheduleType.YEARLY, hour, minute, dayOfMonth = dom, month = month)
                }

                // Both day-of-month and day-of-week restricted: cron ORs them, which no single
                // editor row can express.
                else -> null
            }
        }

        private fun String.singleCronValue(min: Int, max: Int): Int? =
            toIntOrNull()?.takeIf { it in min..max }

        private fun String.cronValueList(min: Int, max: Int): List<Int>? {
            if (isBlank()) return null
            val values = split(",").map { it.trim().toIntOrNull() ?: return null }
            if (values.isEmpty() || values.any { it !in min..max }) return null
            return values
        }
    }
}

/** Destructuring helper so [TaskSchedule.parse] can name the five cron fields. */
private operator fun <T> List<T>.component4(): T = this[3]
private operator fun <T> List<T>.component5(): T = this[4]
