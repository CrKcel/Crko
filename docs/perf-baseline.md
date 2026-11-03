# F2.1 持久化性能基线

本文件是 F2.1 的产出：在替换数据层（F2.2 / F2.3）之前，先固定一份可复现的测量口径和一组数字，后续只比较同一命令、同一数据集、同一指标的中位数。

## 1. 复现方式

前置条件：JDK 21、Android SDK（`testAndroidHostTest` 是 JVM host 测试，不需要模拟器或真机）。

```bash
./gradlew :shared:testAndroidHostTest -PperfBaseline
```

- 不带 `-PperfBaseline` 时，`PerfBaselineTest` 直接返回，普通测试不受影响。
- 带该属性时，Gradle 只跑 `com.crko.perf.PerfBaselineTest`，强制 test JVM 时区为 UTC，并把测试 stdout 打出来。
- 输出是一张 Markdown 表；测试每次都会重跑（`outputs.upToDateWhen { false }`），避免拿到旧的 up-to-date 结果。

实现位置：`shared/src/androidHostTest/kotlin/com/crko/perf/PerfBaselineTest.kt`。

## 2. 数据集

三个规模都使用同一套生成规则，只用索引运算，没有随机源、没有 wall clock、没有设备时区：

| 项目 | 规则 |
|---|---|
| 事件 | 1k / 5k / 20k 三种总量；均匀分到 10 个订阅日历 |
| 待办 | 每个规模都固定 1k，全部在本地日历；1/4 已完成 |
| 订阅日历 | 10 个，每个带 `etag`、`sync-token`、`refreshMinutes`、1 个未知 `TZID` 的 `VTIMEZONE` |
| 事件类型 | 约 1/9 全天；1/7 每周重复（`RRULE:FREQ=WEEKLY;COUNT=52`） |
| 事件时间 | 以 2026-11-03 为中心，向前后各铺约 250 天；时刻 08:00–17:45 |
| 提醒 | 1/4 的事件带 2 个 `VALARM`（提前 15 分钟、提前 1 天） |
| 文本 | 每个事件带 note、分类，部分带地点；每个待办带 note、分类，部分带地点与 `RELATED-TO` |
| 固定时钟 | `now = 2026-11-03T09:00:00Z`；查询日 `2026-11-03`；JVM 默认时区强制 UTC |

固定数据集后，`payload` 大小也固定：1k 事件 + 1k 待办约 1.5 MiB，5k 约 5.0 MiB，20k 约 18.1 MiB（UTF-8 JSON）。

## 3. 测量口径

| 指标 | 调用路径 | 是否含磁盘 |
|---|---|---|
| `FileStateStorage.saveState` | `AppJson.encodeToString(AppState)` + `KeyValueStore.putString` | 是（临时文件） |
| `FileStateStorage.loadState` | 读回字符串 + schema 探测 + 完整解码 | 是（临时文件） |
| `StateRepository.persist (1 edit + awaitIdle)` | 同步更新内存 + `replaceIcsTimeZones` + IO 协程写盘 | 是 |
| `StateRepository.persist burst (20 edits + awaitIdle)` | 连续 20 次 `persist` 后 `awaitIdle`，统计实际 `saveState` 次数 | 是 |
| `replaceIcsTimeZones` | 按 11 个日历重建时区作用域缓存 | 否 |
| `planReminders` | `now` 固定，`zone=UTC`，默认 60 天窗口与 64 条上限 | 否 |
| `eventsOn(day)` | `AppStateHolder.eventsOn(2026-11-03)`（内部 `visibleEvents.occurringOn`） | 否 |
| `activeTodos` | `AppStateHolder.activeTodos()`（内部 `TodoService`） | 否 |

`FileStateStorage` 通过一个按 key 写临时文件的 `KeyValueStore` 驱动，测的是真实序列化 + 文件读写；它不等同于 Android `SharedPreferences` 或 iOS `NSUserDefaults` 的具体实现，换平台后绝对值会变化，但比较趋势和 F2.3 前后差异仍然有效。

每个指标先做 3 次预热，再取 9 个样本的中位数；表里同时给出最小/最大值。事件数量从小到大的顺序固定，因此第一组（1k）仍包含一部分 JIT 预热成本；同一命令下三组可比。

## 4. 环境

| 项目 | 值 |
|---|---|
| 设备 | MacBook Air（`Mac17,3`），Apple M5，16 GiB |
| 系统 | macOS 27.2（26B123） |
| JVM | OpenJDK 21.0.13（Homebrew，64-Bit Server VM，mixed mode） |
| Gradle | 9.8.2 |
| Kotlin | 2.4.20（Kotlin/JVM host test） |
| 运行形态 | Gradle `testAndroidHostTest` test worker；debug 级 JVM bytecode（无 R8/ProGuard），JVM 默认 JIT |
| 写盘调度器 | `Dispatchers.Default`（`StateRepository` 的默认 IO dispatcher） |
| 样本 / 预热 | 9 / 3，取中位数 |
| 时区 | 强制 UTC |
| 存储介质 | 临时目录（APFS） |

## 5. 基线结果

以下数字来自 `./gradlew :shared:testAndroidHostTest -PperfBaseline` 的一次运行，保留 3 位小数。

| events | operation | median ms | min ms | max ms | n | notes |
|---|---|---|---|---|---|---|
| 1000 | FileStateStorage.saveState | 4.727 | 3.739 | 6.759 | 9 | payload=1.5 MiB |
| 1000 | FileStateStorage.loadState | 18.441 | 15.430 | 21.947 | 9 | payload=1.5 MiB |
| 1000 | StateRepository.persist (1 edit + awaitIdle) | 4.446 | 4.248 | 6.298 | 9 |  |
| 1000 | StateRepository.persist burst (20 edits + awaitIdle) | 10.806 | 10.219 | 16.020 | 9 | coalesced writes=3 |
| 1000 | replaceIcsTimeZones | 0.034 | 0.033 | 0.034 | 9 | calendars=11 |
| 1000 | planReminders | 4.519 | 4.467 | 4.766 | 9 | events=1000, reminders=64 |
| 1000 | eventsOn(day) | 0.419 | 0.408 | 0.473 | 9 | date=2026-11-03, events=13 |
| 1000 | activeTodos | 0.688 | 0.671 | 0.713 | 9 | todos=1000, active=750 |
| 5000 | FileStateStorage.saveState | 11.159 | 10.585 | 13.582 | 9 | payload=5.0 MiB |
| 5000 | FileStateStorage.loadState | 44.629 | 43.666 | 47.833 | 9 | payload=5.0 MiB |
| 5000 | StateRepository.persist (1 edit + awaitIdle) | 12.254 | 12.038 | 12.594 | 9 |  |
| 5000 | StateRepository.persist burst (20 edits + awaitIdle) | 28.726 | 28.483 | 29.333 | 9 | coalesced writes=3 |
| 5000 | replaceIcsTimeZones | 0.029 | 0.028 | 0.031 | 9 | calendars=11 |
| 5000 | planReminders | 6.706 | 5.426 | 7.358 | 9 | events=5000, reminders=64 |
| 5000 | eventsOn(day) | 1.050 | 0.925 | 1.082 | 9 | date=2026-11-03, events=61 |
| 5000 | activeTodos | 0.384 | 0.368 | 0.392 | 9 | todos=1000, active=750 |
| 20000 | FileStateStorage.saveState | 35.608 | 35.318 | 36.608 | 9 | payload=18.1 MiB |
| 20000 | FileStateStorage.loadState | 213.097 | 191.153 | 260.628 | 9 | payload=18.1 MiB |
| 20000 | StateRepository.persist (1 edit + awaitIdle) | 34.533 | 33.849 | 38.694 | 9 |  |
| 20000 | StateRepository.persist burst (20 edits + awaitIdle) | 104.693 | 103.093 | 115.428 | 9 | coalesced writes=3 |
| 20000 | replaceIcsTimeZones | 0.024 | 0.024 | 0.027 | 9 | calendars=11 |
| 20000 | planReminders | 15.551 | 15.267 | 17.032 | 9 | events=20000, reminders=64 |
| 20000 | eventsOn(day) | 1.946 | 1.499 | 2.555 | 9 | date=2026-11-03, events=239 |
| 20000 | activeTodos | 0.361 | 0.277 | 0.394 | 9 | todos=1000, active=750 |

## 6. 结论与后续用法

- **保存与加载都随整份 JSON 线性增长。** 20k 事件时保存约 36 ms，加载约 213 ms；加载比保存慢约 6 倍，解码是主要成本。这是 F2.3 最直接的前后对比指标。
- **单次编辑的写盘成本已经等于一次完整保存。** `StateRepository.persist` 的中位数与 `saveState` 基本重合，说明当前的“增量”只体现在异步和合并，不体现在写入量。
- **突发合并不是固定一次。** 20 次连续 `persist` 在真实 `Dispatchers.Default` 下被 3 个重叠的 drain 批次吸收，最终写盘 3 次（每次都是当时的“最新快照”）。F2.3 应保留“每个 drain 批次只写最新状态”的语义，同时把单次写入量降下来。
- **查询没有成为瓶颈。** 20k 事件下 `eventsOn(day)` 约 2 ms、`planReminders` 约 16 ms、`activeTodos` 约 0.4 ms；`replaceIcsTimeZones` 约 0.03 ms。F2.3 的收益应主要落在保存/启动/后台访问，不应以牺牲这些查询为代价。
- **F2.3 对比时保持同一口径。** 先跑本命令记录新的中位数，再与上表逐项比较；至少关注 20k 的 `saveState`、`loadState`、单次 `persist` 与突发 `persist`。
- **数字会波动。** 不同机器、不同 JVM、是否处于低电量模式、临时目录所在磁盘都会影响绝对值；比较时请在同一台机器上成对运行，并看中位数而不是单次 min/max。
</content>
