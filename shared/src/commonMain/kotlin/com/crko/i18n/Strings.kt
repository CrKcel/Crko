package com.crko.i18n

import com.crko.domain.TodoPriority
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.number

enum class AppLanguage { System, Chinese, English }

/** Locales other than Chinese fall back to English. */
fun systemLanguage(tag: String): AppLanguage =
    if (tag.lowercase().startsWith("zh")) AppLanguage.Chinese else AppLanguage.English

fun resolveLanguage(setting: AppLanguage, systemTag: String): AppLanguage =
    if (setting == AppLanguage.System) systemLanguage(systemTag) else setting

fun languageTag(language: AppLanguage): String = when (language) {
    AppLanguage.Chinese -> "zh"
    AppLanguage.English -> "en"
    AppLanguage.System -> ""
}

fun languageFromTag(tag: String): AppLanguage = when (tag) {
    "zh" -> AppLanguage.Chinese
    "en" -> AppLanguage.English
    else -> AppLanguage.System
}

interface Strings {
    val appName: String
    val tabSchedule: String
    val tabTodo: String
    val tabMore: String

    val today: String
    val tomorrow: String
    val yesterday: String
    val weekdaysFull: List<String>
    val weekdaysShort: List<String>

    fun monthTitle(year: Int, month: Month): String
    fun dayMonth(date: LocalDate): String
    fun monthName(month: Month): String
    fun time(time: LocalTime): String
    fun timeRange(start: LocalTime, end: LocalTime): String
    fun relativeDay(date: LocalDate, delta: Int): String

    val eventNew: String
    val eventEdit: String
    val eventTitleField: String
    val noteField: String
    val save: String
    val cancel: String
    val done: String
    val saved: String
    val deleted: String
    val todoAdded: String
    val delete: String
    val startTime: String
    val endTime: String
    val invalidTimeRange: String
    val emptyTitle: String
    val noEventsToday: String
    val noEventsSelected: String
    val expandCalendar: String
    val collapseCalendar: String
    val scheduleHint: String
    fun eventsCount(count: Int): String
    val viewTimeline: String
    val viewList: String

    val todos: String
    val todoNew: String
    val todoTitleField: String
    val priority: String
    val priorityHigh: String
    val priorityNormal: String
    val priorityLow: String
    val dueDate: String
    val clear: String
    val customDate: String
    val incomplete: String
    val completed: String
    val emptyTodos: String
    fun items(count: Int): String

    val statistics: String
    val settings: String
    val completedToday: String
    val activeTodos: String
    val upcomingEvents: String
    val last7Days: String
    val byPriority: String
    val thisWeek: String
    val todayEvents: String

    val language: String
    val languageSystem: String
    val languageChinese: String
    val languageEnglish: String
    val appearance: String
    val modeSystem: String
    val modeLight: String
    val modeDark: String
    val colorPalette: String
    val paletteMonet: String
    val paletteMonochrome: String
    val paletteCustom: String
    val defaultHourNotSet: String
    val timelineStart: String
    val startHour: String

    val about: String
    val version: String
    val storageNote: String
}

object ZhStrings : Strings {
    override val appName = "日程"
    override val tabSchedule = "日程"
    override val tabTodo = "待办"
    override val tabMore = "更多"

    override val today = "今天"
    override val tomorrow = "明天"
    override val yesterday = "昨天"
    override val weekdaysFull = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    override val weekdaysShort = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    override fun monthTitle(year: Int, month: Month) = "${year}年${month.number}月"
    override fun dayMonth(date: LocalDate) = "${date.month.number}月${date.day}日"
    override fun monthName(month: Month) = "${month.number}月"
    override fun time(time: LocalTime) = "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    override fun timeRange(start: LocalTime, end: LocalTime) = "${time(start)} – ${time(end)}"
    override fun relativeDay(date: LocalDate, delta: Int) = when (delta) {
        0 -> today
        1 -> tomorrow
        -1 -> yesterday
        else -> weekdaysFull[date.dayOfWeek.ordinal]
    }

    override val eventNew = "新建日程"
    override val eventEdit = "编辑日程"
    override val eventTitleField = "标题"
    override val noteField = "备注"
    override val save = "保存"
    override val cancel = "取消"
    override val done = "完成"
    override val saved = "已保存"
    override val deleted = "已删除"
    override val todoAdded = "已添加待办"
    override val delete = "删除"
    override val startTime = "开始"
    override val endTime = "结束"
    override val invalidTimeRange = "结束时间需晚于开始时间"
    override val emptyTitle = "请输入标题"
    override val noEventsToday = "今天暂无日程"
    override val noEventsSelected = "这天暂无日程"
    override val expandCalendar = "展开月视图"
    override val collapseCalendar = "收起月视图"
    override val scheduleHint = "点击空白时段快速新建"
    override fun eventsCount(count: Int) = if (count == 0) "" else "$count 项"
    override val viewTimeline = "时间轴"
    override val viewList = "列表"

    override val todos = "待办"
    override val todoNew = "新建待办"
    override val todoTitleField = "要做的事"
    override val priority = "优先级"
    override val priorityHigh = "高"
    override val priorityNormal = "中"
    override val priorityLow = "低"
    override val dueDate = "截止日期"
    override val clear = "清除"
    override val customDate = "自定义"
    override val incomplete = "未完成"
    override val completed = "已完成"
    override val emptyTodos = "暂无待办"
    override fun items(count: Int) = "$count 项"
    override val statistics = "统计"
    override val settings = "设置"
    override val completedToday = "今日完成"
    override val activeTodos = "进行中"
    override val upcomingEvents = "即将开始"
    override val last7Days = "近 7 天完成"
    override val byPriority = "按优先级"
    override val thisWeek = "本周日程"
    override val todayEvents = "今日日程"
    override val language = "语言"
    override val languageSystem = "跟随系统"
    override val languageChinese = "中文"
    override val languageEnglish = "English"
    override val appearance = "外观"
    override val modeSystem = "跟随系统"
    override val modeLight = "浅色"
    override val modeDark = "深色"
    override val colorPalette = "配色"
    override val paletteMonet = "莫奈"
    override val paletteMonochrome = "无配色"
    override val paletteCustom = "自选"
    override val defaultHourNotSet = "未设置"
    override val timelineStart = "默认起始时间"
    override val startHour = "起始小时"
    override val about = "关于"
    override val version = "版本"
    override val storageNote = "数据仅保存在本机"
}

object EnStrings : Strings {
    override val appName = "Schedule"
    override val tabSchedule = "Schedule"
    override val tabTodo = "Todo"
    override val tabMore = "More"

    override val today = "Today"
    override val tomorrow = "Tomorrow"
    override val yesterday = "Yesterday"
    override val weekdaysFull = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    override val weekdaysShort = listOf("M", "T", "W", "T", "F", "S", "S")

    private val monthFull = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    // Short form keeps the header on one line next to the week and toolbar actions.
    override fun monthTitle(year: Int, month: Month) = "${monthFull[month.ordinal].take(3)} $year"
    override fun dayMonth(date: LocalDate) = "${monthFull[date.month.ordinal].take(3)} ${date.day}"
    override fun monthName(month: Month) = monthFull[month.ordinal].take(3)
    override fun time(time: LocalTime) = "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    override fun timeRange(start: LocalTime, end: LocalTime) = "${time(start)} – ${time(end)}"
    override fun relativeDay(date: LocalDate, delta: Int) = when (delta) {
        0 -> today
        1 -> tomorrow
        -1 -> yesterday
        else -> weekdaysFull[date.dayOfWeek.ordinal]
    }

    override val eventNew = "New event"
    override val eventEdit = "Edit event"
    override val eventTitleField = "Title"
    override val noteField = "Notes"
    override val save = "Save"
    override val cancel = "Cancel"
    override val done = "Done"
    override val saved = "Saved"
    override val deleted = "Deleted"
    override val todoAdded = "Todo added"
    override val delete = "Delete"
    override val startTime = "Start"
    override val endTime = "End"
    override val invalidTimeRange = "End time must be after start time"
    override val emptyTitle = "Title is required"
    override val noEventsToday = "Nothing scheduled today"
    override val noEventsSelected = "Nothing scheduled"
    override val expandCalendar = "Show month"
    override val collapseCalendar = "Hide month"
    override val scheduleHint = "Tap an empty slot to add"
    override fun eventsCount(count: Int) = if (count == 0) "" else if (count == 1) "1 event" else "$count events"
    override val viewTimeline = "Timeline"
    override val viewList = "List"

    override val todos = "Todo"
    override val todoNew = "New todo"
    override val todoTitleField = "What needs doing"
    override val priority = "Priority"
    override val priorityHigh = "High"
    override val priorityNormal = "Medium"
    override val priorityLow = "Low"
    override val dueDate = "Due date"
    override val clear = "Clear"
    override val customDate = "Custom"
    override val incomplete = "To do"
    override val completed = "Completed"
    override val emptyTodos = "No todos"
    override fun items(count: Int) = if (count == 1) "1 item" else "$count items"
    override val statistics = "Statistics"
    override val settings = "Settings"
    override val completedToday = "Done today"
    override val activeTodos = "Active"
    override val upcomingEvents = "Upcoming"
    override val last7Days = "Last 7 days"
    override val byPriority = "By priority"
    override val thisWeek = "This week"
    override val todayEvents = "Today"
    override val language = "Language"
    override val languageSystem = "System"
    override val languageChinese = "中文"
    override val languageEnglish = "English"
    override val appearance = "Appearance"
    override val modeSystem = "System"
    override val modeLight = "Light"
    override val modeDark = "Dark"
    override val colorPalette = "Palette"
    override val paletteMonet = "Monet"
    override val paletteMonochrome = "Monochrome"
    override val paletteCustom = "Custom"
    override val defaultHourNotSet = "Not set"
    override val timelineStart = "Default start time"
    override val startHour = "Start hour"
    override val about = "About"
    override val version = "Version"
    override val storageNote = "Data is stored on this device"
}

fun todoPriorityLabel(strings: Strings, priority: TodoPriority): String = when (priority) {
    TodoPriority.High -> strings.priorityHigh
    TodoPriority.Normal -> strings.priorityNormal
    TodoPriority.Low -> strings.priorityLow
}

fun stringsFor(language: AppLanguage): Strings = when (language) {
    AppLanguage.Chinese -> ZhStrings
    else -> EnStrings
}

