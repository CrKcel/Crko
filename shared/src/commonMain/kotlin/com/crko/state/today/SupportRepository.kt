package com.crko.state.today

import com.crko.storage.SupportRecord
import com.crko.storage.SupportStore
import kotlin.time.Clock

internal const val DailyPlanKind = "daily_plan"
internal const val FocusCurrentKind = "focus_current"
internal const val FocusCurrentId = "focus_current"

internal fun dailyPlanId(day: String): String = "$DailyPlanKind:$day"

/**
 * Thin typed access to [SupportStore]. The rows are tiny and only change on a user action or a
 * focus transition, so the writes go straight through instead of queueing behind the calendar's
 * background writer: a focus session that survives an immediate process kill is worth more than
 * the sub-millisecond write.
 */
internal class SupportRepository(
    private val store: SupportStore,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun load(kind: String): List<SupportRecord> = runCatching { store.recordsOfKind(kind) }.getOrDefault(emptyList())

    fun loadOne(recordId: String): SupportRecord? =
        runCatching { store.records().firstOrNull { it.recordId == recordId } }.getOrNull()

    fun save(recordId: String, kind: String, day: String?, payload: String) {
        runCatching {
            store.upsert(
                SupportRecord(
                    recordId = recordId,
                    kind = kind,
                    day = day,
                    payload = payload,
                    updatedMs = nowMillis(),
                ),
            )
        }
    }

    fun delete(recordId: String) {
        runCatching { store.delete(recordId) }
    }

    /** Keeps the support table small; the today plan only matters for the current day. */
    fun pruneDailyPlans(beforeDay: String) {
        runCatching { store.deleteBefore(DailyPlanKind, beforeDay) }
    }
}
