package com.crko.i18n

import com.crko.domain.IcsImportFailure
import com.crko.domain.IcsPartStat
import com.crko.domain.IcsWarning
import com.crko.domain.SubscriptionFailure
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
    val eventDetail: String
    val eventTitleField: String
    val noteField: String
    val save: String
    val cancel: String
    val edit: String
    val close: String
    val done: String
    val saved: String
    val deleted: String
    /** Snackbar action that restores the most recent delete. */
    val undo: String
    val deleteUndone: String
    val deleteUndoFailed: String
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
    val attachments: String
    val attachmentSaveFailed: String
    val attachmentBroken: String
    val moreOptions: String
    val eventStatus: String
    val statusConfirmed: String
    val statusTentative: String
    val statusCancelled: String
    val transparency: String
    val busy: String
    val free: String
    val priorityNone: String
    val reminder: String
    val reminderNone: String
    val reminderAtTime: String
    fun reminderBefore(minutes: Int): String
    fun reminderAfterStart(minutes: Int): String
    val reminderAtEnd: String
    fun reminderBeforeEnd(minutes: Int): String
    fun reminderAfterEnd(minutes: Int): String
    val reminderRelativeTo: String
    val reminderRelativeToStart: String
    val reminderRelativeToEnd: String
    val editScope: String
    val scopeThisEvent: String
    val scopeThisAndFuture: String
    val scopeAllEvents: String

    val todos: String
    val todoNew: String
    val todoEdit: String
    val todoDetail: String
    val todoTitleField: String
    val untitledTodo: String
    val startDate: String
    val progress: String
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

    /** Completion scope of a repeating todo. */
    val todoActions: String
    val todoCompleteSeries: String
    val todoStopRecurrence: String

    fun items(count: Int): String

    /** `RELATED-TO` sub-tasks. */
    val subtaskAdd: String
    fun subtaskOf(parent: String): String

    /** `VJOURNAL` browsing and editing. */
    val journals: String
    val journalNew: String
    val journalEdit: String
    val journalEmpty: String
    val journalDate: String

    /** Global search across events, todos and journals. */
    val search: String
    val searchHint: String
    val searchNoResults: String
    val searchFilters: String
    val searchAllCalendars: String
    val searchAllCategories: String
    val searchDateFrom: String
    val searchDateTo: String
    val searchRecurring: String
    val searchLimitReached: String
    val searchTypeEvents: String
    val searchTypeTodos: String
    val searchTypeJournals: String

    val statistics: String
    val settings: String
    val calendarsAndData: String
    val back: String
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
    fun importedItems(events: Int, todos: Int, journals: Int, removed: Int): String
    fun importFailure(failure: IcsImportFailure): String
    fun importWarning(warning: IcsWarning): String
    fun importWarningLocation(line: Int?, record: String?): String
    fun importWarningMore(count: Int): String
    fun storageRecovered(droppedRecords: Int): String
    val untitledEvent: String

    /** Per-record import preview. */
    val importPreviewTitle: String
    val importPreviewEvent: String
    val importPreviewTodo: String
    val importPreviewJournal: String
    val importPreviewRemove: String
    val importPreviewEmpty: String
    val importSelectAll: String
    val importSelectNone: String
    val importConfirm: String

    /** Calendar subscriptions (webcal/ICS feeds and CalDAV collections). */
    val subscriptions: String
    val subscriptionsHint: String
    val subscriptionAdd: String
    val subscriptionAddHint: String
    val subscriptionName: String
    val subscriptionUrl: String
    val subscriptionUrlInvalid: String
    val subscriptionSyncNow: String
    val subscriptionNeverSynced: String
    val subscriptionsEmpty: String
    fun subscriptionLastSynced(at: String): String
    fun subscriptionSynced(name: String, events: Int, todos: Int, removed: Int): String
    fun subscriptionUnchanged(name: String): String
    fun subscriptionFailed(name: String, failure: SubscriptionFailure): String

    /** Local calendar management. */
    val calendars: String
    val calendar: String
    val calendarAdd: String
    val calendarAddHint: String
    val calendarName: String
    val calendarColor: String
    val calendarDefault: String
    val calendarSetDefault: String
    val calendarHidden: String
    val calendarExport: String
    val calendarRename: String
    val calendarDelete: String
    fun calendarDeleteSummary(name: String, records: Int): String

    /** Name of a `PARTSTAT` for invitation feedback. */
    fun partStatName(partStat: IcsPartStat): String
    fun inviteReplyProcessed(attendee: String, partStat: IcsPartStat): String
    fun inviteReplyUnknown(attendee: String): String

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

    val gestures: String
    val gesturePinchOverview: String
    val gestureSwipeSwitch: String

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
    override val eventDetail = "日程详情"
    override val eventTitleField = "标题"
    override val noteField = "备注"
    override val save = "保存"
    override val cancel = "取消"
    override val edit = "编辑"
    override val close = "关闭"
    override val done = "完成"
    override val saved = "已保存"
    override val deleted = "已删除"
    override val undo = "撤销"
    override val deleteUndone = "已撤销删除"
    override val deleteUndoFailed = "无法撤销：记录已存在或所在日历已删除"
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
    override val attachments = "附件"
    override val attachmentSaveFailed = "附件保存失败"
    override val attachmentBroken = "附件数据无法解码"
    override val moreOptions = "更多选项"
    override val eventStatus = "状态"
    override val statusConfirmed = "确认"
    override val statusTentative = "待定"
    override val statusCancelled = "已取消"
    override val transparency = "空闲状态"
    override val busy = "忙碌"
    override val free = "空闲"
    override val priorityNone = "无"
    override val reminder = "提醒"
    override val reminderNone = "不提醒"
    override val reminderAtTime = "准时"
    override fun reminderBefore(minutes: Int) = "提前 $minutes 分钟"
    override fun reminderAfterStart(minutes: Int) = "开始后 $minutes 分钟"
    override val reminderAtEnd = "结束时"
    override fun reminderBeforeEnd(minutes: Int) = "结束前 $minutes 分钟"
    override fun reminderAfterEnd(minutes: Int) = "结束后 $minutes 分钟"
    override val reminderRelativeTo = "基准"
    override val reminderRelativeToStart = "开始时间"
    override val reminderRelativeToEnd = "结束时间"
    override val editScope = "修改范围"
    override val scopeThisEvent = "仅此次"
    override val scopeThisAndFuture = "此次及以后"
    override val scopeAllEvents = "整个系列"

    override val todos = "待办"
    override val todoNew = "新建待办"
    override val todoEdit = "编辑待办"
    override val todoDetail = "待办详情"
    override val todoTitleField = "要做的事"
    override val untitledTodo = "未命名待办"
    override val startDate = "开始日期"
    override val progress = "完成度"
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
    override val todoActions = "待办操作"
    override val todoCompleteSeries = "完成整个系列"
    override val todoStopRecurrence = "停止重复"
    override fun items(count: Int) = "$count 项"
    override val subtaskAdd = "添加子任务"
    override fun subtaskOf(parent: String) = "「$parent」的子任务"
    override val journals = "日记"
    override val journalNew = "新建日记"
    override val journalEdit = "编辑日记"
    override val journalEmpty = "还没有日记"
    override val journalDate = "日期"
    override val search = "搜索"
    override val searchHint = "搜索日程、待办、日记"
    override val searchNoResults = "没有匹配的结果"
    override val searchFilters = "筛选"
    override val searchAllCalendars = "全部日历"
    override val searchAllCategories = "全部分类"
    override val searchDateFrom = "起始日期"
    override val searchDateTo = "结束日期"
    override val searchRecurring = "重复"
    override val searchLimitReached = "仅显示前 100 条结果"
    override val searchTypeEvents = "日程"
    override val searchTypeTodos = "待办"
    override val searchTypeJournals = "日记"
    override val statistics = "统计"
    override val settings = "设置"
    override val calendarsAndData = "日历与数据"
    override val back = "返回"
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
    override fun importedItems(events: Int, todos: Int, journals: Int, removed: Int): String {
        val items = listOf(
            events.takeIf { it > 0 }?.let { "$it 项日程" },
            todos.takeIf { it > 0 }?.let { "$it 项待办" },
            journals.takeIf { it > 0 }?.let { "$it 篇日记" },
        ).filterNotNull()
        val imported = if (items.isEmpty()) "" else "已导入 " + items.joinToString("、")
        val removedText = if (removed > 0) "已移除 $removed 条记录" else ""
        return listOf(imported, removedText).filter { it.isNotEmpty() }.joinToString("，")
    }
    override fun importFailure(failure: IcsImportFailure): String = when (failure) {
        IcsImportFailure.TooLarge -> "文件超过 5 MB，无法导入；请先在原日历中缩小导出范围后重试"
        IcsImportFailure.InvalidEncoding -> "无法识别文件编码，无法导入；请重新导出为 UTF-8 或 GBK 文件"
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
        IcsWarning.UnappliedRecurrenceRange -> "部分例外标记为“此次及以后”但没有自带重复规则，其后的实例仍按原重复进行"
    }
    override fun importWarningLocation(line: Int?, record: String?): String = when {
        record != null && line != null -> "（$record，第 $line 行）"
        record != null -> "（$record）"
        line != null -> "（第 $line 行）"
        else -> ""
    }
    override fun importWarningMore(count: Int): String = "（另有 $count 条）"
    override fun storageRecovered(droppedRecords: Int): String =
        if (droppedRecords > 0) {
            "本地日程数据损坏，已尽量恢复，$droppedRecords 条记录无法读取（原始文件已备份）"
        } else {
            "本地日程数据无法读取，已备份原始文件以便恢复"
        }
    override val untitledEvent = "无标题日程"
    override val importPreviewTitle = "选择要导入的内容"
    override val importPreviewEvent = "日程"
    override val importPreviewTodo = "待办"
    override val importPreviewJournal = "日记"
    override val importPreviewRemove = "移除已取消的日程"
    override val importPreviewEmpty = "文件中没有可选择的记录"
    override val importSelectAll = "全选"
    override val importSelectNone = "全不选"
    override val importConfirm = "导入所选"
    override val subscriptions = "日历订阅"
    override val subscriptionsHint = "同步 webcal 订阅或 CalDAV 日历"
    override val subscriptionAdd = "添加订阅"
    override val subscriptionAddHint = "粘贴日历链接（webcal:// 或 https://）"
    override val subscriptionName = "名称"
    override val subscriptionUrl = "链接"
    override val subscriptionUrlInvalid = "链接无效，需要 http(s) 或 webcal 开头的完整地址"
    override val subscriptionSyncNow = "立即同步"
    override val subscriptionNeverSynced = "尚未同步"
    override val subscriptionsEmpty = "暂无订阅"
    override fun subscriptionLastSynced(at: String) = "上次同步 $at"
    override fun subscriptionSynced(name: String, events: Int, todos: Int, removed: Int): String {
        val items = listOf(
            events.takeIf { it > 0 }?.let { "$it 项日程" },
            todos.takeIf { it > 0 }?.let { "$it 项待办" },
        ).filterNotNull()
        val changed = if (items.isEmpty()) "" else "，更新 " + items.joinToString("、")
        val removedText = if (removed > 0) "，移除 $removed 条" else ""
        return "「$name」已同步$changed$removedText"
    }
    override fun subscriptionUnchanged(name: String) = "「$name」没有更新"
    override fun subscriptionFailed(name: String, failure: SubscriptionFailure): String = when (failure) {
        SubscriptionFailure.Unreachable -> "「$name」同步失败：无法连接服务器"
        SubscriptionFailure.Unauthorized -> "「$name」同步失败：服务器要求登录"
        SubscriptionFailure.HttpError -> "「$name」同步失败：服务器返回错误"
        SubscriptionFailure.NotCalendar -> "「$name」同步失败：地址不是日历"
        SubscriptionFailure.TooLarge -> "「$name」同步失败：日历过大"
    }
    override val calendars = "日历"
    override val calendar = "日历"
    override val calendarAdd = "新建日历"
    override val calendarAddHint = "用日历区分工作、个人与家庭日程"
    override val calendarName = "名称"
    override val calendarColor = "颜色"
    override val calendarDefault = "默认"
    override val calendarSetDefault = "设为默认"
    override val calendarHidden = "已隐藏"
    override val calendarExport = "导出此日历"
    override val calendarRename = "重命名日历"
    override val calendarDelete = "删除日历"
    override fun calendarDeleteSummary(name: String, records: Int) =
        "「$name」中的 $records 条记录将一并删除，且无法恢复。"
    override fun partStatName(partStat: IcsPartStat): String = when (partStat) {
        IcsPartStat.Accepted -> "已接受"
        IcsPartStat.Declined -> "已拒绝"
        IcsPartStat.Tentative -> "暂定参加"
        IcsPartStat.Delegated -> "已委派"
        IcsPartStat.Completed -> "已完成"
        IcsPartStat.InProcess -> "进行中"
        IcsPartStat.NeedsAction -> "待答复"
    }
    override fun inviteReplyProcessed(attendee: String, partStat: IcsPartStat) =
        "$attendee ${partStatName(partStat)}邀请"
    override fun inviteReplyUnknown(attendee: String) = "$attendee 的答复无法匹配本地日程"
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
    override val gestures = "手势"
    override val gesturePinchOverview = "双指缩放切换日视图与周总览"
    override val gestureSwipeSwitch = "滑动切换列表与时间轴"
    override val about = "关于"
    override val version = "版本"
    override val storageNote = "数据仅保存在本机"
}

fun todoPriorityLabel(strings: Strings, priority: TodoPriority): String = when (priority) {
    TodoPriority.High -> strings.priorityHigh
    TodoPriority.Normal -> strings.priorityNormal
    TodoPriority.Low -> strings.priorityLow
}

/**
 * Human label for a relative alarm trigger. `minutes` is positive for "before" (the editor's
 * presets), negative for "after" (only reachable from imported `VALARM`s), and [atEnd] selects the
 * `RELATED=END` wording.
 */
fun Strings.alarmLabel(minutes: Int, atEnd: Boolean): String = when {
    minutes == 0 -> if (atEnd) reminderAtEnd else reminderAtTime
    minutes > 0 -> if (atEnd) reminderBeforeEnd(minutes) else reminderBefore(minutes)
    else -> if (atEnd) reminderAfterEnd(-minutes) else reminderAfterStart(-minutes)
}

fun stringsFor(language: AppLanguage): Strings = when (language) {
    AppLanguage.Chinese -> ZhStrings
    else -> EnStrings
}

