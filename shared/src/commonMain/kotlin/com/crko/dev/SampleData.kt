package com.crko.dev

import com.crko.domain.AppState
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.MaxEndMinutes
import com.crko.domain.Todo
import com.crko.domain.TodoPriority
import com.crko.domain.TodoStatus
import com.crko.domain.plusDays
import com.crko.domain.toIcsPriority
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/**
 * Seed content for debug builds that start without saved data, so every screen can be checked on
 * a real device without manual entry. Dates are relative to [today] so the data always lands in
 * the current week and month.
 */
fun sampleState(
    today: LocalDate,
    nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
): AppState {
    val yesterday = today.plusDays(-1)
    var eventId = 0
    fun event(title: String, day: LocalDate, startMinutes: Int, endMinutes: Int, note: String = "") =
        Event("sample-event-${eventId++}", title, day.toString(), startMinutes, endMinutes, note = note)

    var todoId = 0
    fun todo(
        title: String,
        priority: TodoPriority = TodoPriority.Normal,
        due: LocalDate? = null,
        done: Boolean = false,
        createdHoursAgo: Long = 0,
    ) = Todo(
        id = "sample-todo-${todoId++}",
        title = title,
        status = if (done) TodoStatus.Completed else TodoStatus.NeedsAction,
        completed = if (done) {
            kotlin.time.Instant.fromEpochMilliseconds(nowMillis - createdHoursAgo * 3_600_000)
        } else {
            null
        },
        priority = priority.toIcsPriority(),
        createdAt = nowMillis - createdHoursAgo * 3_600_000,
        due = due?.let(IcsTimeValue::Date),
    )

    fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

    val events = listOf(
        // Today: day-boundary times, an overlapping pair, and a long block for timeline layout.
        event("午夜电台", today, at(0), at(0, 30)),
        event("晨跑", today, at(7), at(7, 45), "滨江公园 5 公里"),
        event("团队周会", today, at(10), at(11), "线上会议"),
        event("设计评审", today, at(10, 30), at(11, 30), "与周会重叠，检查并列布局"),
        event("午餐", today, at(12), at(13)),
        event("季度 OKR 复盘与下季度规划会议", today, at(15), at(16, 30)),
        event("买菜", today, at(18, 30), at(19, 15)),
        event("阅读", today, at(21), at(22)),
        event("睡前放松", today, at(23), MaxEndMinutes),
        // Neighboring days for the week strip and list view.
        event("健身房", yesterday, at(19), at(20)),
        event("牙医预约", today.plusDays(1), at(9, 30), at(10, 15)),
        event("项目演示", today.plusDays(1), at(14), at(15, 30), "带好备用转接头"),
        event("电影", today.plusDays(1), at(20), at(21, 30)),
        event("高铁出发", today.plusDays(2), at(8), at(9, 30), "G1234 次 8:35 开"),
        event("朋友聚餐", today.plusDays(3), at(19), at(22)),
        event("家长会", today.plusDays(5), at(14), at(16)),
        event("体检", today.plusDays(8), at(10), at(12), "空腹"),
        event("搬家整理", today.plusDays(12), at(9), at(18)),
        event("音乐会", today.plusDays(18), at(18), at(20)),
        event("团建", today.plusDays(-5), at(15), at(18)),
        event("出差", today.plusDays(35), at(9), at(18), "跨月，检查月份切换"),
    )

    val todos = listOf(
        todo("提交报销单", TodoPriority.High, today),
        todo("回复设计稿意见", TodoPriority.High, yesterday),
        todo("准备周四演示", TodoPriority.High),
        todo("预约体检", TodoPriority.Normal, today),
        todo("买猫粮", TodoPriority.Normal, today.plusDays(1)),
        todo("给绿萝浇水", TodoPriority.Normal),
        todo("整理相册", TodoPriority.Low),
        todo("归还图书馆的书", TodoPriority.Low, today.plusDays(3)),
        todo("完成周报", TodoPriority.High, today, done = true, createdHoursAgo = 1),
        todo("清理收件箱", TodoPriority.Normal, today, done = true, createdHoursAgo = 2),
        todo("缴水电费", TodoPriority.Normal, yesterday, done = true, createdHoursAgo = 26),
        todo("清理下载文件夹", TodoPriority.Low, today.plusDays(-3), done = true, createdHoursAgo = 74),
        todo("预约牙医", TodoPriority.High, today.plusDays(-5), done = true, createdHoursAgo = 122),
        todo("更新简历", TodoPriority.Normal, today.plusDays(-6), done = true, createdHoursAgo = 146),
    )

    return AppState(events = events, todos = todos)
}
