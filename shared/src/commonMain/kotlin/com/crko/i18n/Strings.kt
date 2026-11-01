package com.crko.i18n

import com.crko.domain.IcsImportFailure
import com.crko.domain.IcsWarning
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
    val switchView: String
    val viewTimeline: String
    val viewList: String
    val dayView: String
    val weekOverview: String
    val repeat: String
    val repeatNone: String
    val repeatDaily: String
    val repeatWeekly: String
    val repeatMonthly: String
    val repeatYearly: String
    val repeatCustom: String
    val repeatWeekdaysRequired: String
    val repeatInterval: String
    val repeatEnd: String
    val repeatNeverEnds: String
    val repeatAfterTimes: String
    val repeatUntilDate: String

    val allDay: String
    val endDate: String
    val location: String
    val locationField: String
    val categories: String
    val categoriesField: String
    val urlField: String
    val moreOptions: String
    val eventStatus: String
    val statusConfirmed: String
    val statusTentative: String
    val transparency: String
    val busy: String
    val free: String
    val priorityNone: String
    val reminder: String
    val reminderNone: String
    val reminderAtTime: String
    fun reminderBefore(minutes: Int): String
    val editScope: String
    val scopeThisEvent: String
    val scopeThisAndFuture: String
    val scopeAllEvents: String

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
    val selectDate: String
    val incomplete: String
    val completed: String
    val todoStatusInProgress: String
    val todoStatusCancelled: String
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

    val calendarData: String
    val importIcs: String
    val importIcsHint: String
    val exportIcs: String
    val exportIcsHint: String
    val nothingToImport: String
    val importFailed: String
    val exportFailed: String
    val exported: String
    fun importedItems(events: Int, todos: Int): String
    fun importFailure(failure: IcsImportFailure): String
    fun importWarning(warning: IcsWarning): String
    fun storageRecovered(droppedRecords: Int): String
    val untitledEvent: String

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
    override val switchView = "切换视图"
    override val viewTimeline = "时间轴"
    override val viewList = "列表"
    override val dayView = "日视图"
    override val weekOverview = "周总览"
    override val repeat = "重复"
    override val repeatNone = "不重复"
    override val repeatDaily = "每天"
    override val repeatWeekly = "每周"
    override val repeatMonthly = "每月"
    override val repeatYearly = "每年"
    override val repeatCustom = "自定义"
    override val repeatWeekdaysRequired = "请至少选择一天"
    override val repeatInterval = "间隔"
    override val repeatEnd = "结束重复"
    override val repeatNeverEnds = "永不结束"
    override val repeatAfterTimes = "次数"
    override val repeatUntilDate = "日期"

    override val allDay = "全天"
    override val endDate = "结束日期"
    override val location = "地点"
    override val locationField = "地点"
    override val categories = "分类"
    override val categoriesField = "分类（用逗号分隔）"
    override val urlField = "网址"
    override val moreOptions = "更多选项"
    override val eventStatus = "状态"
    override val statusConfirmed = "确认"
    override val statusTentative = "待定"
    override val transparency = "空闲状态"
    override val busy = "忙碌"
    override val free = "空闲"
    override val priorityNone = "无"
    override val reminder = "提醒"
    override val reminderNone = "不提醒"
    override val reminderAtTime = "准时"
    override fun reminderBefore(minutes: Int) = "提前 $minutes 分钟"
    override val editScope = "修改范围"
    override val scopeThisEvent = "仅此次"
    override val scopeThisAndFuture = "此次及以后"
    override val scopeAllEvents = "整个系列"

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
    override val selectDate = "选择日期"
    override val incomplete = "未完成"
    override val completed = "已完成"
    override val todoStatusInProgress = "进行中"
    override val todoStatusCancelled = "已取消"
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
    override val calendarData = "日程数据"
    override val importIcs = "导入 ICS 文件"
    override val importIcsHint = "从其他日历应用导入日程"
    override val exportIcs = "导出 ICS 文件"
    override val exportIcsHint = "将全部日程保存为 .ics 文件"
    override val nothingToImport = "没有可导入的日程"
    override val importFailed = "导入失败，请检查文件格式"
    override val exportFailed = "导出失败"
    override val exported = "已导出"
    override fun importedItems(events: Int, todos: Int): String = when {
        events > 0 && todos > 0 -> "已导入 $events 项日程、$todos 项待办"
        todos > 0 -> "已导入 $todos 项待办"
        else -> "已导入 $events 项日程"
    }
    override fun importFailure(failure: IcsImportFailure): String = when (failure) {
        IcsImportFailure.TooLarge -> "文件超过 5 MB，无法导入；请先在原日历中缩小导出范围后重试"
        IcsImportFailure.InvalidEncoding -> "文件不是 UTF-8 编码，无法导入；请重新导出为 UTF-8 文件"
        IcsImportFailure.Unreadable -> "无法读取所选文件"
        IcsImportFailure.InvalidFormat -> importFailed
    }
    override fun importWarning(warning: IcsWarning): String = when (warning) {
        IcsWarning.UnresolvedTimeZone -> "部分日程的时区无法识别，已按本地时间导入"
        IcsWarning.InvalidValue -> "部分日期或时间格式无效，已跳过"
        IcsWarning.UnsupportedRecurrence -> "部分重复规则暂不支持，已简化这些日程"
        IcsWarning.MultipleRrules -> "单个日程包含多条重复规则，只使用了第一条"
        IcsWarning.MissingStart -> "部分日程没有可用的开始时间，已跳过"
        IcsWarning.AmbiguousRecurrenceId -> "同一天有多次重复改动无法区分，已按独立日程保留"
        IcsWarning.ExruleIgnored -> "部分日程包含已废弃的 EXRULE 排除规则，已忽略"
        IcsWarning.UnknownSubcomponent -> "部分日程包含不支持的子组件，已忽略"
        IcsWarning.ExdateMismatch -> "部分排除日期的时刻与日程开始时间不一致，已忽略"
    }
    override fun storageRecovered(droppedRecords: Int): String =
        if (droppedRecords > 0) {
            "本地日程数据损坏，已尽量恢复，$droppedRecords 条记录无法读取（原始文件已备份）"
        } else {
            "本地日程数据无法读取，已备份原始文件以便恢复"
        }
    override val untitledEvent = "无标题日程"
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
    override val switchView = "Switch view"
    override val viewTimeline = "Timeline"
    override val viewList = "List"
    override val dayView = "Day view"
    override val weekOverview = "Week overview"
    override val repeat = "Repeat"
    override val repeatNone = "Never"
    override val repeatDaily = "Daily"
    override val repeatWeekly = "Weekly"
    override val repeatMonthly = "Monthly"
    override val repeatYearly = "Yearly"
    override val repeatCustom = "Custom"
    override val repeatWeekdaysRequired = "Pick at least one day"
    override val repeatInterval = "Every"
    override val repeatEnd = "Ends"
    override val repeatNeverEnds = "Never"
    override val repeatAfterTimes = "After"
    override val repeatUntilDate = "On date"

    override val allDay = "All day"
    override val endDate = "End date"
    override val location = "Location"
    override val locationField = "Location"
    override val categories = "Categories"
    override val categoriesField = "Categories (comma separated)"
    override val urlField = "URL"
    override val moreOptions = "More options"
    override val eventStatus = "Status"
    override val statusConfirmed = "Confirmed"
    override val statusTentative = "Tentative"
    override val transparency = "Availability"
    override val busy = "Busy"
    override val free = "Free"
    override val priorityNone = "None"
    override val reminder = "Reminder"
    override val reminderNone = "None"
    override val reminderAtTime = "At start"
    override fun reminderBefore(minutes: Int) = "$minutes min before"
    override val editScope = "Apply to"
    override val scopeThisEvent = "This event"
    override val scopeThisAndFuture = "This and later"
    override val scopeAllEvents = "All events"

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
    override val selectDate = "Select date"
    override val incomplete = "To do"
    override val completed = "Completed"
    override val todoStatusInProgress = "In progress"
    override val todoStatusCancelled = "Cancelled"
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
    override val calendarData = "Calendar data"
    override val importIcs = "Import ICS file"
    override val importIcsHint = "Bring events in from another calendar app"
    override val exportIcs = "Export ICS file"
    override val exportIcsHint = "Save every event as an .ics file"
    override val nothingToImport = "Nothing to import"
    override val importFailed = "Import failed. Check the file format."
    override val exportFailed = "Export failed"
    override val exported = "Exported"
    override fun importedItems(events: Int, todos: Int): String = when {
        events > 0 && todos > 0 -> "$events events and $todos todos imported"
        todos > 0 -> if (todos == 1) "1 todo imported" else "$todos todos imported"
        else -> if (events == 1) "1 event imported" else "$events events imported"
    }
    override fun importFailure(failure: IcsImportFailure): String = when (failure) {
        IcsImportFailure.TooLarge -> "The file is larger than 5 MB. Export a shorter date range and try again."
        IcsImportFailure.InvalidEncoding -> "The file is not UTF-8 encoded. Export it again as UTF-8."
        IcsImportFailure.Unreadable -> "The selected file could not be read"
        IcsImportFailure.InvalidFormat -> importFailed
    }
    override fun importWarning(warning: IcsWarning): String = when (warning) {
        IcsWarning.UnresolvedTimeZone -> "Some time zones were unknown; those events use local time"
        IcsWarning.InvalidValue -> "Some dates or times were invalid and were skipped"
        IcsWarning.UnsupportedRecurrence -> "Some recurrence rules are unsupported; those events were simplified"
        IcsWarning.MultipleRrules -> "An event had several recurrence rules; only the first was used"
        IcsWarning.MissingStart -> "Some events had no usable start date and were skipped"
        IcsWarning.AmbiguousRecurrenceId -> "Several changes shared one occurrence date; they are kept as separate events"
        IcsWarning.ExruleIgnored -> "Some events contain deprecated EXRULE exclusions, which were ignored"
        IcsWarning.UnknownSubcomponent -> "Some events contain unsupported subcomponents, which were ignored"
        IcsWarning.ExdateMismatch -> "Some excluded dates did not match the event start time and were ignored"
    }
    override fun storageRecovered(droppedRecords: Int): String =
        if (droppedRecords > 0) {
            "Stored calendar data was damaged; $droppedRecords records could not be read. The original file was kept as a backup."
        } else {
            "Stored calendar data could not be read. The original file was kept as a backup."
        }
    override val untitledEvent = "Untitled event"
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

