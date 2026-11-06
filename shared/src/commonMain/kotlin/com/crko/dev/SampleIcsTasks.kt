package com.crko.dev

import com.crko.domain.plusDays
import kotlinx.datetime.LocalDate

/**
 * Sample tasks: priorities, due and start dates, in-process and completed states, cancellations,
 * recurring tasks, nested subtasks through `RELATED-TO`, alarms and attachments.
 */
internal fun sampleTodos(
    today: LocalDate,
    stamp: String,
    nowMillis: Long,
): List<String> = buildList {
    val yesterday = today.plusDays(-1)
    val tomorrow = today.plusDays(1)
    val recentSaturday = recentWeekday(today, 5)

    // An active parent with a nested subtask forest, linked through RELATED-TO.
    add(
        icsTodo(
            uid = "sample-todo-quarter-report@crko",
            summary = "准备季度汇报",
            stamp = stamp,
            start = icsDate(today.plusDays(-2)),
            startParam = ";VALUE=DATE",
            due = icsDate(today),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:1",
                "CATEGORIES:工作",
                "DESCRIPTION:汇总本季度数据，准备 10 页以内的汇报材料",
                "CREATED:${hoursAgo(nowMillis, 96)}",
                "LAST-MODIFIED:${hoursAgo(nowMillis, 2)}",
                "BEGIN:VALARM",
                "ACTION:DISPLAY",
                "TRIGGER:-PT2H",
                "DESCRIPTION:季度汇报今天到期",
                "END:VALARM",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-charts@crko",
            summary = "整理数据图表",
            stamp = stamp,
            start = icsDate(today.plusDays(-1)),
            startParam = ";VALUE=DATE",
            due = icsDate(today),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:1",
                "STATUS:IN-PROCESS",
                "PERCENT-COMPLETE:60",
                "CATEGORIES:工作",
                "RELATED-TO;RELTYPE=PARENT:sample-todo-quarter-report@crko",
                "CREATED:${hoursAgo(nowMillis, 72)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-slides@crko",
            summary = "写讲稿",
            stamp = stamp,
            start = icsDate(today),
            startParam = ";VALUE=DATE",
            due = icsDate(tomorrow),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:5",
                "CATEGORIES:工作",
                "RELATED-TO;RELTYPE=PARENT:sample-todo-quarter-report@crko",
                "DESCRIPTION:每页不超过三个要点",
                "CREATED:${hoursAgo(nowMillis, 48)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-quotes@crko",
            summary = "校对引文",
            stamp = stamp,
            due = icsDate(tomorrow),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:9",
                "CATEGORIES:工作",
                "RELATED-TO;RELTYPE=PARENT:sample-todo-charts@crko",
                "CREATED:${hoursAgo(nowMillis, 30)}",
            ),
        ),
    )

    // Active todos across the priority and date range.
    add(
        icsTodo(
            uid = "sample-todo-reimburse@crko",
            summary = "提交报销单",
            stamp = stamp,
            due = icsDate(today),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:1",
                "CATEGORIES:工作,财务",
                "URL:https://example.com/expense",
                "ATTACH;FMTTYPE=image/jpeg;FILENAME=receipt.jpg:https://example.com/receipts/latest.jpg",
                "CREATED:${hoursAgo(nowMillis, 120)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-design-feedback@crko",
            summary = "回复设计稿意见",
            stamp = stamp,
            due = icsDate(yesterday),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:2",
                "CATEGORIES:工作,设计",
                "CREATED:${hoursAgo(nowMillis, 144)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-demo-prep@crko",
            summary = "准备周四演示",
            stamp = stamp,
            extra = listOf(
                "PRIORITY:1",
                "CATEGORIES:工作",
                "DESCRIPTION:检查演示环境和备用转接头",
                "CREATED:${hoursAgo(nowMillis, 60)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-checkup-booking@crko",
            summary = "预约体检",
            stamp = stamp,
            due = icsDate(today),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:5",
                "CATEGORIES:健康",
                "LOCATION:市第一医院",
                "CREATED:${hoursAgo(nowMillis, 36)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-cat-food@crko",
            summary = "买猫粮",
            stamp = stamp,
            due = icsDate(tomorrow),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:5",
                "CATEGORIES:生活,家务",
                "LOCATION:宠物店",
                "BEGIN:VALARM",
                "ACTION:DISPLAY",
                "TRIGGER:-PT2H",
                "DESCRIPTION:顺路买猫粮",
                "END:VALARM",
                "CREATED:${hoursAgo(nowMillis, 24)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-water-plants@crko",
            summary = "给绿萝浇水",
            stamp = stamp,
            extra = listOf(
                "PRIORITY:5",
                "CATEGORIES:生活,家务",
                "CREATED:${hoursAgo(nowMillis, 12)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-album@crko",
            summary = "整理相册",
            stamp = stamp,
            extra = listOf(
                "PRIORITY:9",
                "CATEGORIES:生活",
                "CREATED:${hoursAgo(nowMillis, 200)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-library@crko",
            summary = "归还图书馆的书",
            stamp = stamp,
            due = icsDate(today.plusDays(3)),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:9",
                "CATEGORIES:生活,阅读",
                "LOCATION:市图书馆",
                "URL:https://example.com/library",
                "CREATED:${hoursAgo(nowMillis, 80)}",
            ),
        ),
    )

    // Completed history for the completed list and the weekly statistics.
    add(
        icsTodo(
            uid = "sample-todo-weekly-report@crko",
            summary = "完成周报",
            stamp = stamp,
            due = icsDate(yesterday),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:1",
                "STATUS:COMPLETED",
                "PERCENT-COMPLETE:100",
                "COMPLETED:${hoursAgo(nowMillis, 3)}",
                "CATEGORIES:工作",
                "CREATED:${hoursAgo(nowMillis, 50)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-inbox@crko",
            summary = "清理收件箱",
            stamp = stamp,
            due = icsDate(yesterday),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:5",
                "STATUS:COMPLETED",
                "PERCENT-COMPLETE:100",
                "COMPLETED:${hoursAgo(nowMillis, 26)}",
                "CATEGORIES:工作",
                "CREATED:${hoursAgo(nowMillis, 28)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-utilities@crko",
            summary = "缴水电费",
            stamp = stamp,
            due = icsDate(today.plusDays(-3)),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:5",
                "STATUS:COMPLETED",
                "PERCENT-COMPLETE:100",
                "COMPLETED:${hoursAgo(nowMillis, 74)}",
                "CATEGORIES:生活,财务",
                "CREATED:${hoursAgo(nowMillis, 100)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-dentist-booking@crko",
            summary = "预约牙医",
            stamp = stamp,
            due = icsDate(today.plusDays(-5)),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:1",
                "STATUS:COMPLETED",
                "PERCENT-COMPLETE:100",
                "COMPLETED:${hoursAgo(nowMillis, 122)}",
                "CATEGORIES:健康",
                "CREATED:${hoursAgo(nowMillis, 150)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-resume@crko",
            summary = "更新简历",
            stamp = stamp,
            due = icsDate(today.plusDays(-6)),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:5",
                "STATUS:COMPLETED",
                "PERCENT-COMPLETE:100",
                "COMPLETED:${hoursAgo(nowMillis, 146)}",
                "CATEGORIES:工作",
                "CREATED:${hoursAgo(nowMillis, 170)}",
            ),
        ),
    )

    // In-process, recurring, cancelled and travelling todos.
    add(
        icsTodo(
            uid = "sample-todo-design-draft@crko",
            summary = "设计稿初稿",
            stamp = stamp,
            due = icsDate(today.plusDays(2)),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:2",
                "STATUS:IN-PROCESS",
                "PERCENT-COMPLETE:40",
                "CATEGORIES:工作,设计",
                "DESCRIPTION:先出两版布局给团队选",
                "CREATED:${hoursAgo(nowMillis, 40)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-plant-series@crko",
            summary = "每周浇花",
            stamp = stamp,
            start = icsDate(recentSaturday),
            startParam = ";VALUE=DATE",
            due = icsDate(recentSaturday),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:9",
                "CATEGORIES:生活,家务",
                "RRULE:FREQ=WEEKLY;COUNT=6",
                "CREATED:${hoursAgo(nowMillis, 210)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-cancelled-pt@crko",
            summary = "取消的私教课",
            stamp = stamp,
            due = icsDate(today.plusDays(-2)),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:5",
                "STATUS:CANCELLED",
                "CATEGORIES:运动",
                "CREATED:${hoursAgo(nowMillis, 90)}",
            ),
        ),
    )
    add(
        icsTodo(
            uid = "sample-todo-trip-checklist@crko",
            summary = "整理旅行清单",
            stamp = stamp,
            due = icsDate(today.plusDays(10)),
            dueParam = ";VALUE=DATE",
            extra = listOf(
                "PRIORITY:5",
                "CATEGORIES:旅行",
                "DESCRIPTION:证件\\n充电器\\n常用药",
                "URL:https://example.com/trip/kyoto",
                "ATTACH;FMTTYPE=application/pdf;FILENAME=kyoto-guide.pdf:https://example.com/trip/kyoto-guide.pdf",
                "CREATED:${hoursAgo(nowMillis, 20)}",
            ),
        ),
    )
}

/**
 * Sample journal entries: dated, undated, recurring, private, with statuses and categories.
 */
internal fun sampleJournals(
    today: LocalDate,
    stamp: String,
): List<String> = buildList {
    add(
        icsJournal(
            uid = "sample-journal-weekly@crko",
            summary = "周记",
            stamp = stamp,
            start = icsDate(today),
            startParam = ";VALUE=DATE",
            extra = listOf(
                "STATUS:FINAL",
                "CATEGORIES:生活,复盘",
                "DESCRIPTION:这周完成了季度汇报的初稿，也把拖了很久的书还了。\\n下周重点是演示和体检。",
            ),
        ),
    )
    add(
        icsJournal(
            uid = "sample-journal-meeting@crko",
            summary = "会议纪要：设计评审",
            stamp = stamp,
            start = icsDate(today),
            startParam = ";VALUE=DATE",
            extra = listOf(
                "STATUS:FINAL",
                "CLASS:PRIVATE",
                "CATEGORIES:工作,会议",
                "DESCRIPTION:结论：日程页保留并列布局。\\n待办：补充空状态截图，整理组件规范。",
                "URL:https://example.com/notes/design-review",
            ),
        ),
    )
    add(
        icsJournal(
            uid = "sample-journal-book-notes@crko",
            summary = "读书笔记：深度工作",
            stamp = stamp,
            start = icsDate(today.plusDays(-3)),
            startParam = ";VALUE=DATE",
            extra = listOf(
                "STATUS:FINAL",
                "CATEGORIES:阅读",
                "DESCRIPTION:把注意力当成稀缺资源来安排。\\n一次只做一件事，比并行更省脑力。",
                "URL:https://example.com/books/deep-work",
            ),
        ),
    )
    add(
        icsJournal(
            uid = "sample-journal-kyoto@crko",
            summary = "京都旅行计划",
            stamp = stamp,
            start = icsDate(today.plusDays(7)),
            startParam = ";VALUE=DATE",
            extra = listOf(
                "STATUS:DRAFT",
                "CATEGORIES:旅行",
                "DESCRIPTION:想去的地点：清水寺、伏见稻荷、岚山。\\n先把交通和住宿定下来。",
            ),
        ),
    )
    add(
        icsJournal(
            uid = "sample-journal-daily-review@crko",
            summary = "每日复盘",
            stamp = stamp,
            start = icsDate(today.plusDays(-2)),
            startParam = ";VALUE=DATE",
            extra = listOf(
                "STATUS:FINAL",
                "CATEGORIES:复盘",
                "DESCRIPTION:今天最重要的一件事是什么？\\n明天怎么做得更好？",
                "RRULE:FREQ=DAILY;COUNT=5",
            ),
        ),
    )
    add(
        icsJournal(
            uid = "sample-journal-ideas@crko",
            summary = "灵感收集",
            stamp = stamp,
            extra = listOf(
                "STATUS:FINAL",
                "CLASS:PRIVATE",
                "CATEGORIES:创作",
                "DESCRIPTION:把零散的想法先记下来，不急着整理。\\n无日期的日志记录。",
            ),
        ),
    )
}
