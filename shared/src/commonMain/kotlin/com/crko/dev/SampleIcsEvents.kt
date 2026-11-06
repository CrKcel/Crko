package com.crko.dev

import com.crko.domain.plusDays
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number

/**
 * Time-bound sample records: one-off and repeating events with alarms, participants, locations,
 * attachments, all-day spans, overlapping blocks and the different time value types (floating,
 * `TZID`, UTC, `DATE`, zero-length and `DURATION`).
 */
internal fun sampleEvents(
    today: LocalDate,
    zone: TimeZone,
    stamp: String,
    nowMillis: Long,
): List<String> = buildList {
    val yesterday = today.plusDays(-1)
    val tomorrow = today.plusDays(1)
    val recentWednesday = recentWeekday(today, 2)
    val monthStart = LocalDate(today.year, today.month.number, 1)
    val zonedParam = ";TZID=\"${zone.id}\""

    // ----- today: boundaries, an overlapping pair, a long block, zero-length and DURATION -----
    add(
        icsEvent(
            uid = "sample-event-midnight@crko",
            summary = "午夜电台",
            stamp = stamp,
            start = icsLocal(today, 0, 0),
            end = icsLocal(today, 0, 30),
            extra = listOf(
                "CATEGORIES:生活",
                "DESCRIPTION:零点档节目，适合睡前收听",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-run@crko",
            summary = "晨跑",
            stamp = stamp,
            start = icsLocal(today, 7, 0),
            end = icsLocal(today, 7, 45),
            extra = listOf(
                "LOCATION:滨江公园",
                "CATEGORIES:运动,健康",
                "URL:https://example.com/routes/riverside",
                "DESCRIPTION:5 公里轻松跑\\n记录配速与心率",
                "BEGIN:VALARM",
                "ACTION:DISPLAY",
                "TRIGGER:-PT15M",
                "DESCRIPTION:该出门跑步了",
                "END:VALARM",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-team-sync@crko",
            summary = "团队周会",
            stamp = stamp,
            start = icsLocal(today, 10, 0),
            end = icsLocal(today, 11, 0),
            extra = listOf(
                "LOCATION:线上会议",
                "CATEGORIES:工作",
                "DESCRIPTION:同步本周进展与风险\\n会议纪要会发到群里",
                "URL:https://meet.example.com/team-weekly",
                "GEO:31.230416;121.473701",
                "ORGANIZER;CN=王敏:mailto:wang.min@example.com",
                "ATTENDEE;CN=李雷;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:lilei@example.com",
                "ATTENDEE;CN=韩梅梅;ROLE=OPT-PARTICIPANT;PARTSTAT=TENTATIVE:mailto:han.meimei@example.com",
                "ATTENDEE;CN=Alex;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:alex@example.com",
                "PRIORITY:2",
                "CREATED:${hoursAgo(nowMillis, 72)}",
                "LAST-MODIFIED:${hoursAgo(nowMillis, 1)}",
                "SEQUENCE:1",
                "BEGIN:VALARM",
                "ACTION:DISPLAY",
                "TRIGGER:-PT5M",
                "DESCRIPTION:周会 5 分钟后开始",
                "END:VALARM",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-design-review@crko",
            summary = "设计评审",
            stamp = stamp,
            start = icsLocal(today, 10, 30),
            end = icsLocal(today, 11, 30),
            extra = listOf(
                "LOCATION:3 楼会议室",
                "CATEGORIES:工作,设计",
                "DESCRIPTION:与周会重叠，检查并列布局\\n评审新版日程页的交互",
                "ORGANIZER;CN=陈可:mailto:chen.ke@example.com",
                "ATTENDEE;CN=赵一;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED:mailto:zhao.yi@example.com",
                "PRIORITY:2",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-lunch@crko",
            summary = "午餐",
            stamp = stamp,
            start = icsLocal(today, 12, 0),
            end = icsLocal(today, 13, 0),
            extra = listOf(
                "LOCATION:公司食堂",
                "CATEGORIES:生活",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-okr@crko",
            summary = "季度 OKR 复盘与下季度规划会议",
            stamp = stamp,
            start = icsLocal(today, 15, 0),
            end = icsLocal(today, 16, 30),
            extra = listOf(
                "LOCATION:大会议室",
                "CATEGORIES:工作,规划",
                "DESCRIPTION:上半场复盘本季度目标完成情况\\n下半场对齐下季度重点与资源",
                "CLASS:CONFIDENTIAL",
                "STATUS:CONFIRMED",
                "PRIORITY:1",
                "ATTENDEE;CN=林小满;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:lin.xiaoman@example.com",
                "ATTACH;FMTTYPE=application/pdf;FILENAME=okr-review.pdf:https://example.com/docs/okr-review.pdf",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-groceries@crko",
            summary = "买菜",
            stamp = stamp,
            start = icsLocal(today, 18, 30),
            end = icsLocal(today, 19, 15),
            extra = listOf(
                "LOCATION:社区超市",
                "CATEGORIES:生活,家务",
                "DESCRIPTION:牛奶\\n鸡蛋\\n青菜\\n猫粮",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-read@crko",
            summary = "阅读",
            stamp = stamp,
            start = icsLocal(today, 21, 0),
            end = icsLocal(today, 22, 0),
            extra = listOf(
                "LOCATION:书房",
                "CATEGORIES:阅读",
                "DESCRIPTION:继续读《深度工作》第三章",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-wind-down@crko",
            summary = "睡前放松",
            stamp = stamp,
            start = icsLocal(today, 23, 0),
            end = icsLocal(today, 23, 59),
            extra = listOf("CATEGORIES:生活,健康"),
        ),
    )

    // ----- all-day, multi-day, DURATION and zero-length records -----
    add(
        icsEvent(
            uid = "sample-event-no-phone@crko",
            summary = "无手机日",
            stamp = stamp,
            start = icsDate(today),
            startParam = ";VALUE=DATE",
            end = icsDate(tomorrow),
            endParam = ";VALUE=DATE",
            extra = listOf(
                "CATEGORIES:生活,自我管理",
                "TRANSP:TRANSPARENT",
                "DESCRIPTION:除了接打电话，尽量不碰手机",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-design-week@crko",
            summary = "设计周",
            stamp = stamp,
            start = icsDate(today.plusDays(2)),
            startParam = ";VALUE=DATE",
            end = icsDate(today.plusDays(5)),
            endParam = ";VALUE=DATE",
            extra = listOf(
                "LOCATION:城市展览中心",
                "CATEGORIES:工作,设计",
                "URL:https://example.com/design-week",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-meditate@crko",
            summary = "冥想",
            stamp = stamp,
            start = icsLocal(today, 6, 30),
            extra = listOf(
                "DURATION:PT20M",
                "CATEGORIES:健康",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-check-in@crko",
            summary = "打卡提醒",
            stamp = stamp,
            start = icsLocal(today, 9, 0),
            extra = listOf("CATEGORIES:工作"),
        ),
    )

    // ----- neighboring days and later in the month -----
    add(
        icsEvent(
            uid = "sample-event-gym-series@crko",
            summary = "健身训练",
            stamp = stamp,
            start = icsLocal(yesterday, 19, 0),
            end = icsLocal(yesterday, 20, 0),
            extra = listOf(
                "LOCATION:社区健身房",
                "CATEGORIES:运动",
                "DESCRIPTION:力量训练 + 20 分钟有氧",
                "RRULE:FREQ=WEEKLY;COUNT=10",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-dentist@crko",
            summary = "牙医预约",
            stamp = stamp,
            start = icsLocal(tomorrow, 9, 30),
            end = icsLocal(tomorrow, 10, 15),
            extra = listOf(
                "LOCATION:口腔诊所",
                "CATEGORIES:健康",
                "PRIORITY:2",
                "BEGIN:VALARM",
                "ACTION:DISPLAY",
                "TRIGGER:-PT1H",
                "DESCRIPTION:一小时后去看牙医",
                "END:VALARM",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-demo@crko",
            summary = "项目演示",
            stamp = stamp,
            start = icsLocal(tomorrow, 14, 0),
            end = icsLocal(tomorrow, 15, 30),
            extra = listOf(
                "LOCATION:线上会议室",
                "CATEGORIES:工作",
                "DESCRIPTION:带好备用转接头",
                "URL:https://meet.example.com/demo",
                "ATTENDEE;CN=周舟;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED:mailto:zhou.zhou@example.com",
                "BEGIN:VALARM",
                "ACTION:DISPLAY",
                "TRIGGER:-PT30M",
                "DESCRIPTION:项目演示 30 分钟后开始",
                "END:VALARM",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-movie@crko",
            summary = "电影",
            stamp = stamp,
            start = icsLocal(tomorrow, 20, 0),
            end = icsLocal(tomorrow, 21, 30),
            extra = listOf(
                "LOCATION:万象影城",
                "CATEGORIES:娱乐",
                "URL:https://example.com/cinema/seat",
                "CLASS:PRIVATE",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-train@crko",
            summary = "高铁出发",
            stamp = stamp,
            start = icsLocal(today.plusDays(2), 8, 0),
            end = icsLocal(today.plusDays(2), 9, 30),
            extra = listOf(
                "LOCATION:上海虹桥站",
                "CATEGORIES:出行",
                "DESCRIPTION:G1234 次 8:35 开\\n取票后直接去 12 号检票口",
                "PRIORITY:1",
                "BEGIN:VALARM",
                "ACTION:DISPLAY",
                "TRIGGER:-PT2H",
                "DESCRIPTION:该出发去车站了",
                "END:VALARM",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-dinner@crko",
            summary = "朋友聚餐",
            stamp = stamp,
            start = icsLocal(today.plusDays(3), 19, 0),
            end = icsLocal(today.plusDays(3), 22, 0),
            extra = listOf(
                "LOCATION:老街火锅",
                "CATEGORIES:社交",
                "ATTENDEE;CN=小林;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED:mailto:lin@example.com",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-parents-meeting@crko",
            summary = "家长会",
            stamp = stamp,
            start = icsLocal(today.plusDays(5), 14, 0),
            end = icsLocal(today.plusDays(5), 16, 0),
            extra = listOf(
                "LOCATION:学校礼堂",
                "CATEGORIES:家庭",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-checkup@crko",
            summary = "体检",
            stamp = stamp,
            start = icsLocal(today.plusDays(8), 10, 0),
            end = icsLocal(today.plusDays(8), 12, 0),
            extra = listOf(
                "LOCATION:市第一医院",
                "CATEGORIES:健康",
                "DESCRIPTION:空腹\\n带上身份证和体检单",
                "BEGIN:VALARM",
                "ACTION:DISPLAY",
                "TRIGGER:-PT12H",
                "DESCRIPTION:明早体检，记得空腹",
                "END:VALARM",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-moving@crko",
            summary = "搬家整理",
            stamp = stamp,
            start = icsLocal(today.plusDays(12), 9, 0),
            end = icsLocal(today.plusDays(12), 18, 0),
            extra = listOf(
                "LOCATION:新家",
                "CATEGORIES:生活",
                "DESCRIPTION:打包书籍和厨房用品",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-concert@crko",
            summary = "音乐会",
            stamp = stamp,
            start = icsLocal(today.plusDays(18), 18, 0),
            end = icsLocal(today.plusDays(18), 20, 0),
            extra = listOf(
                "LOCATION:交响乐团音乐厅",
                "CATEGORIES:娱乐,音乐",
                "URL:https://example.com/concert/tickets",
                "ATTACH;FMTTYPE=application/pdf;FILENAME=concert-ticket.pdf:https://example.com/tickets/concert.pdf",
                "ATTENDEE;CN=阿青;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED:mailto:qing@example.com",
                "STATUS:TENTATIVE",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-team-building@crko",
            summary = "团建",
            stamp = stamp,
            start = icsLocal(today.plusDays(-5), 15, 0),
            end = icsLocal(today.plusDays(-5), 18, 0),
            extra = listOf(
                "LOCATION:郊外营地",
                "CATEGORIES:工作,社交",
                "ATTENDEE;CN=全体同事;ROLE=OPT-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:team@example.com",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-business-trip@crko",
            summary = "出差",
            stamp = stamp,
            start = icsLocal(today.plusDays(35), 9, 0),
            end = icsLocal(today.plusDays(35), 18, 0),
            extra = listOf(
                "LOCATION:杭州分公司",
                "CATEGORIES:工作",
                "DESCRIPTION:跨月，检查月份切换",
            ),
        ),
    )

    // ----- recurring records: daily, weekly with an exclusion, monthly, and an exception -----
    add(
        icsEvent(
            uid = "sample-event-standup@crko",
            summary = "每日站会",
            stamp = stamp,
            start = icsLocal(today.plusDays(-2), 9, 30),
            end = icsLocal(today.plusDays(-2), 9, 45),
            extra = listOf(
                "LOCATION:线上会议",
                "CATEGORIES:工作",
                "URL:https://meet.example.com/standup",
                "RRULE:FREQ=DAILY;COUNT=20",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-reading-club@crko",
            summary = "周三读书会",
            stamp = stamp,
            start = icsLocal(recentWednesday, 20, 0),
            end = icsLocal(recentWednesday, 21, 30),
            extra = listOf(
                "LOCATION:社区图书馆",
                "CATEGORIES:阅读,社交",
                "DESCRIPTION:本期读《置身事内》",
                "RRULE:FREQ=WEEKLY;BYDAY=WE;COUNT=8",
                "EXDATE:${icsLocal(recentWednesday.plusDays(14), 20, 0)}",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-bills@crko",
            summary = "每月账单整理",
            stamp = stamp,
            start = icsLocal(monthStart, 20, 0),
            end = icsLocal(monthStart, 20, 30),
            extra = listOf(
                "CATEGORIES:生活,财务",
                "DESCRIPTION:核对信用卡与订阅支出",
                "RRULE:FREQ=MONTHLY;BYMONTHDAY=1;COUNT=6",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-review-series@crko",
            summary = "每日复盘",
            stamp = stamp,
            start = icsLocal(today.plusDays(-2), 20, 0),
            end = icsLocal(today.plusDays(-2), 20, 20),
            extra = listOf(
                "CATEGORIES:复盘",
                "RRULE:FREQ=DAILY;COUNT=6",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-review-series@crko",
            summary = "每日复盘（延后）",
            stamp = stamp,
            start = icsLocal(today, 21, 30),
            end = icsLocal(today, 21, 50),
            extra = listOf(
                "CATEGORIES:复盘",
                "RECURRENCE-ID:${icsLocal(today, 20, 0)}",
                "DESCRIPTION:今天加班，复盘挪到睡前",
                "SEQUENCE:2",
            ),
        ),
    )

    // ----- other time value types: Zoned, UTC, tentative and transparent -----
    add(
        icsEvent(
            uid = "sample-event-client-call@crko",
            summary = "跨时区客户电话",
            stamp = stamp,
            start = icsLocal(today, 17, 0),
            startParam = zonedParam,
            end = icsLocal(today, 17, 45),
            endParam = zonedParam,
            extra = listOf(
                "CATEGORIES:工作",
                "LOCATION:线上会议",
                "ORGANIZER;CN=客户成功团队:mailto:cs@example.com",
                "ATTENDEE;CN=Marco;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED:mailto:marco@example.com",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-global-talk@crko",
            summary = "国际线上分享",
            stamp = stamp,
            start = utcAt(today, 14, 0, zone),
            end = utcAt(today, 15, 0, zone),
            extra = listOf(
                "CATEGORIES:学习,工作",
                "URL:https://example.com/webinar",
                "CLASS:PUBLIC",
            ),
        ),
    )
    add(
        icsEvent(
            uid = "sample-event-optional-salon@crko",
            summary = "可选参加：行业沙龙",
            stamp = stamp,
            start = icsLocal(today.plusDays(4), 19, 0),
            end = icsLocal(today.plusDays(4), 21, 0),
            extra = listOf(
                "LOCATION:创意园区",
                "CATEGORIES:学习,社交",
                "STATUS:TENTATIVE",
                "TRANSP:TRANSPARENT",
                "URL:https://example.com/salon",
            ),
        ),
    )
}
