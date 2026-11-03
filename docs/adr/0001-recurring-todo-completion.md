# ADR 0001: 重复待办（VTODO）完成语义

- 状态：已采纳
- 日期：2026-11-03
- 关联：F1.3 重复待办完成语义（`docs/TODO`）

## 背景

`Todo` 自 F1.2 起可以带 `RRULE` / `RDATE` / `EXDATE`，但完成只有一个动作：把记录本身置为
`STATUS:COMPLETED`。对重复待办来说，这会让整条系列从活动列表消失，也无法再投影下一次；导出的
`VTODO` 也与 RFC 5545 的重复语义不一致（没有实例级的 `RECURRENCE-ID`）。

问题不在于「怎么勾选」，而在于「勾选代表哪个对象」：一次完成针对的是**某一次出现**，而重复规则属于
**整个系列**。

## 决策（推荐方案：RFC 对齐）

采用 RFC 5545 的「主记录 + 实例」模型：

1. **主记录**保留 `RRULE` / `RDATE` / `EXDATE`，永不因完成单次出现而改变，继续投影下一次。
2. **完成一次**生成（或更新）一条**实例记录**：
   - 与主记录共享 `UID`；
   - 带 `RECURRENCE-ID`，值为主记录 `DTSTART`（无 `DTSTART` 时为 `DUE`）在同一槽位上的值，
     值类型（`DATE` / floating / `TZID` / UTC）与主记录一致；
   - 写 `STATUS:COMPLETED`、`PERCENT-COMPLETE:100`、`COMPLETED:<UTC>`，`SEQUENCE` 递增；
   - 自身不带 `RRULE`。
3. **默认行为：完成本次**。勾选卡片（含勾选圆圈与整行点击）只完成当前展示的那一次出现。
4. **系列级动作**放在卡片的「更多」菜单里：
   - **完成整个系列**：主记录置为完成，不再投影任何出现；
   - **停止重复**：清空主记录的 `RRULE` / `RDATE` / `EXDATE`，保留记录本身与已存实例（历史），
     之后按一次性待办处理。

`EXDATE` 的语义保持不变：它仍然是主记录上「这段规则里没有这一次」的声明；完成实例则是「这一次已经
完成」的独立记录。二者互不替代：状态层查询下一次出现时同时跳过 `EXDATE` 与已有实例的槽位。

### 备选方案（未采纳）

直接把主记录的 `DUE` / `DTSTART` 推进到下一次并重置状态：

- 优点：实现快，不需要新字段。
- 缺点：丢失完成历史；导出既不能表达「第 N 次已完成」，也会让其它客户端把整个系列当成移动过的
  一次性待办；与 RFC 5545 的 `RECURRENCE-ID` 语义冲突。**不推荐**。

## 影响

- `domain`：`Todo.recurrenceId: IcsTimeValue? = null`（additive，旧 payload 解码为 `null`）；
  `TodoRecurrence` 的 `occursOn` / `nextOccurrenceDate` 接受同一系列的实例列表并跳过其槽位；
  `recurrenceSlotOn(date)` 生成与主记录对齐的 `RECURRENCE-ID`；`todoForest` 在 UID 冲突时优先
  主记录，避免完成实例抢走子任务的父节点。
- `ICS`：读写 `VTODO` 的 `RECURRENCE-ID`；实例与主记录共享 `UID`；`RECURRENCE-ID` 只在与主记录
  同文件导出时写出（孤立的实例按普通待办写出），导入时按主记录的值类型对齐槽位；合并键为
  `UID + RECURRENCE-ID`。
- `state`：`TodoService.completeOccurrence` / `completeSeries` / `stopRecurrence`，全部经
  `StateAccess.persist`；活动列表显示主记录的下一次，完成实例进入已完成列表；统计按实例的
  `COMPLETED` 日期计数。
- `ui`：勾选默认完成本次；重复待办卡片新增「更多」菜单。

## 测试要点

- 每天 / 每周系列各完成一次后，下一次落在正确日期，主记录字段与 `SEQUENCE` 不变。
- `COUNT` 用尽、`UNTIL` 过期后不再产生新的出现。
- `EXDATE` 槽位不可完成，下一次跳过它。
- 带 `TZID` 的系列，实例的 `RECURRENCE-ID` / `DUE` 保留同一时区与钟点。
- 主记录 + 实例的 ICS 往返：共享 `UID`、`RECURRENCE-ID`、`COMPLETED`、`PERCENT-COMPLETE` 不丢。
- 完成整个系列、停止重复各自的状态迁移。
- 旧 payload（无 `recurrenceId`）直接解码；含实例的 payload 往返一致。
- 与 F1.4 解耦：完成语义只依赖已存记录，不依赖撤销栈；删除一条实例后该槽位重新可用。

## 回滚

- 字段为 additive 且有默认值，回滚代码后旧版本仍可读取新 payload（忽略 `recurrenceId`）。
- 行为回滚：删除 `completeOccurrence` / `completeSeries` / `stopRecurrence` 的 UI 入口即可恢复
  F1.2 的一次性完成；已生成的实例是普通 `VTODO`，可手工删除，不会影响主记录。
- ICS 读写是增量能力：不写 `RECURRENCE-ID` 的客户端仍能把实例当独立待办处理。
