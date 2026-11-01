# Todo

- [ ] `METHOD` / iTIP 邀请流程
- [ ] 多日历 / `X-WR-CALNAME` / `REFRESH-INTERVAL`
- [ ] `VJOURNAL`
- [ ] `VFREEBUSY` 与忙闲查询
- [ ] 日历订阅 / 同步（webcal、CalDAV、增量同步）
- [ ] `VALARM` 的系统通知与权限、重复提醒
- [ ] `ORGANIZER` / `ATTENDEE` 的邀请、回复、`METHOD` 与 `SEQUENCE` 冲突解决

## 其他

- [ ] VTODO 的 `DUE` 仍只保留日期，具体时刻会丢失（`COMPLETED` 已保留 UTC 时刻）。
- [ ] `BYHOUR` / `BYMINUTE` 尚未展开为同一天多个开始时间（当前只保留一个开始时间）。
- [ ] 秒级时间与大跨度系列的性能优化（极端历史系列仍会线性扫描）。
