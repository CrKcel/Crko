# Todo

## 小

- [ ] 重新导入按 `SEQUENCE` / `DTSTAMP` 更新已有记录（当前只按 UID 跳过）。
- [ ] 同步文件内删除或 `CANCELLED` 的日程，从本地移除对应记录（需先定义导入范围）。
- [ ] `X-WR-TIMEZONE` 被忽略，浮动时间按设备时区解释。
- [ ] 自定义 `VTIMEZONE` 只解析 `RRULE` 型转换：`RDATE` 型转换、`TZOFFSETTO` 的秒部分、观测规则的 `UNTIL/COUNT` 均未处理。
- [ ] `RECURRENCE-ID;RANGE=THISANDPRIOR` 未特殊处理，会当作普通单次例外。
- [ ] `FREQ=HOURLY/MINUTELY/SECONDLY` 不支持，按单次日程导入并告警。
- [ ] 修改提醒时，所有导入的 `VALARM` 会被压缩成一条简单相对提醒。
- [ ] 缺少 `DTEND` / `DURATION` 的定时日程默认按 60 分钟处理，而 RFC 5545 语义为零时长；需同时调整模型校验与时间轴表达。
- [ ] 仅接受 UTF-8：GBK / Windows-1252 导出的 ICS 会直接导入失败；vCalendar 1.0 的 `ENCODING=QUOTED-PRINTABLE` 不解码。
- [ ] 导入上限 5 MB（Android/iOS），超大文件已提示缩小导出范围，但仍无分块或流式读取。
- [ ] `ATTACH` 仅原样保留，界面不展示也无法下载。
- [ ] 警告只有汇总文案，没有逐条记录/行号。
- [ ] `VJOURNAL` 的解析与导入导出往返。
- [ ] VTODO 未建模 `DTSTART`、`LOCATION`、`CATEGORIES`、`URL`、`RELATED-TO`（不含子任务层级）。
- [ ] 秒级时间与大跨度系列的性能优化（极端历史系列仍会线性扫描）。

## 大

- [ ] `METHOD` / iTIP 邀请流程（`REQUEST` / `REPLY` / `CANCEL`）。
- [ ] `ORGANIZER` / `ATTENDEE` 的邀请、回复、`METHOD` 与 `SEQUENCE` 冲突解决。
- [ ] 多日历 / 导入 `X-WR-CALNAME` / `REFRESH-INTERVAL`（当前没有日历容器模型）。
- [ ] 日历订阅 / 同步（webcal、CalDAV、增量同步）。
- [ ] `VFREEBUSY` 与忙闲查询。
- [ ] VTODO 的 `RRULE` / `RDATE` / `EXDATE` 展开（重复待办目前只保留原始行，显示为一条）。
- [ ] VTODO 的 `RELATED-TO` 子任务层级与界面。
- [ ] `VALARM` 的系统通知与权限、重复提醒（当前只存储，不调度）。
- [ ] `BYHOUR` / `BYMINUTE` 展开为同一天多个开始时间（当前模型只有单个开始时间）。
- [ ] 逐条导入预览与选择（与“警告带行号”配套）。
- [ ] `VJOURNAL` 的浏览 / 编辑界面。
