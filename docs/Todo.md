# Todo

## 导入 / 同步

- [ ] 重新导入只按 UID 去重跳过已有记录：同一文件更新后（`SEQUENCE` 增加、标题/时间变化）不会更新本地记录；文件内删除或 `CANCELLED` 的日程也不会从本地移除。
- [ ] `METHOD` / iTIP 邀请流程（`REQUEST` / `REPLY` / `CANCEL`）。
- [ ] `ORGANIZER` / `ATTENDEE` 的邀请、回复、`METHOD` 与 `SEQUENCE` 冲突解决。
- [ ] 多日历 / `X-WR-CALNAME` / `REFRESH-INTERVAL`（导入忽略日历级属性，导出的文件也不带 `X-WR-CALNAME`）。
- [ ] 日历订阅 / 同步（webcal、CalDAV、增量同步）。

## 组件

- [ ] `VJOURNAL`。
- [ ] `VFREEBUSY` 与忙闲查询。
- [ ] VTODO 的 `RRULE` / `RDATE` / `EXDATE` 只保留原始行、不参与展开，重复待办会显示为一条。
- [ ] VTODO 的 `VALARM` 子组件未解析，导入即丢失。
- [ ] VTODO 未建模 `DTSTART`、`LOCATION`、`CATEGORIES`、`URL`、`RELATED-TO` / 子任务。
- [ ] VTODO 的 `DUE` 仍只保留日期，具体时刻会丢失（`COMPLETED` 已保留 UTC 时刻）。
- [ ] `VALARM` 的系统通知与权限、重复提醒。
- [ ] `ATTACH` 仅原样保留，界面不展示也无法下载。
- [ ] 除 `VALARM` 外的未知子组件会被静默丢弃且不产生警告。

## 重复规则

- [ ] `BYHOUR` / `BYMINUTE` 尚未展开为同一天多个开始时间（当前只保留一个开始时间）。
- [ ] `FREQ=HOURLY/MINUTELY/SECONDLY` 不支持，按单次日程导入并告警。
- [ ] `BYSECOND` 被静默忽略。
- [ ] `WEEKLY` 规则中的序数 `BYDAY`（如 `2TU`）会被丢弃并告警（RFC 5545 不允许，可考虑为兼容旧客户端而展开）。
- [ ] `EXRULE` 不生效，只作为未知属性保留。
- [ ] `RDATE;VALUE=PERIOD` 只取开始时间，周期时长被忽略。
- [ ] `RECURRENCE-ID;RANGE=THISANDPRIOR` 未特殊处理，会当作普通单次例外。
- [ ] `EXDATE` 的时区/时间与系列开始时间不一致时会被静默丢弃。

## 时区

- [ ] Windows 时区别名（如 `China Standard Time`）、带域前缀的 TZID 无法解析，会退化为浮动时间并告警。
- [ ] `X-WR-TIMEZONE` 被忽略，浮动时间按设备时区解释。
- [ ] 自定义 `VTIMEZONE` 只解析 `RRULE` 型转换，`RDATE` 型转换忽略；`TZOFFSETTO` 的秒部分和观测规则的 `UNTIL/COUNT` 未处理。

## 兼容性

- [ ] 仅接受 UTF-8：GBK / Windows-1252 导出的 ICS 会直接导入失败；vCalendar 1.0 的 `ENCODING=QUOTED-PRINTABLE` 不解码。
- [ ] 导入上限 5 MB（Android/iOS），超大文件无分块或提示性降级。

## 编辑 / 体验

- [ ] 修改提醒时，所有导入的 `VALARM` 会被压缩成一条简单相对提醒。
- [ ] 修改重复频率会丢弃原 `BY*` 字段。
- [ ] 缺少 `DTEND` / `DURATION` 的定时日程默认按 60 分钟处理，而 RFC 5545 语义为零时长。
- [ ] 警告只有汇总文案，没有逐条记录/行号；也没有导入预览与选择。

## 性能

- [ ] 秒级时间与大跨度系列的性能优化（极端历史系列仍会线性扫描）。
