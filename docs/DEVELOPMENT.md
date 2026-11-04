# Crko 开发指南

Crko 是日程与待办应用，使用 Kotlin Multiplatform + Compose Multiplatform 界面。

## 开发环境

- JDK 21
- Android SDK
- iOS 构建需 Xcode

## 构建

```bash
# Android debug APK
./gradlew :androidApp:assembleDebug

# 单元测试
./gradlew :shared:testAndroidHostTest

# shared 的 iOS Kotlin 编译
./gradlew :shared:compileKotlinIosSimulatorArm64

# iOS 构建
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 18 Pro' build
```

## 项目结构

```
shared/src/
├── commonMain/kotlin/com/crko/
│   ├── domain/     纯 Kotlin：模型、重复规则（RecurrenceMatching / RecurrenceIndex / TodoRecurrence）、ICS 编解码
│   ├── storage/    持久化门面、SQLDelight 读写、平台声明（expect）与文本解码（CalendarTextReader）
│   ├── sync/       订阅抓取与 CalDAV 协议实现
│   ├── state/      AppStateHolder 门面 + event/todo/journal/import/settings/persistence 服务
│   ├── i18n/       中英文文案
│   └── ui/         主题、组件、日程/待办/更多三个页面；ui/transfer 只编排导入导出
├── commonMain/sqldelight/com/crko/storage/db/  SQLDelight 表结构与查询（生成的 Kotlin 在 build/）
├── androidHostTest/kotlin/com/crko/  SQLite 持久化测试与 F2.1 性能基线
└── commonTest/kotlin/com/crko/
    ├── Ics*Test.kt 读写、时区、附件、往返与重复实例按场景拆分
    └── StateHolder 相关测试按服务拆分
```

## 分层与依赖方向

依赖按层单向：

- `domain`：不依赖其他包。
- `storage` → `domain`
- `sync` → `domain`、`storage`
- `state` → `domain`、`storage`、`sync`、`i18n`
- `ui` → `domain`、`i18n`、`state`（不直接依赖 `storage`、`sync`）
- `com.crko.dev` 只允许组合根 `App.kt` 与测试引用。
- `state` 对 `i18n` 只用于用户可见文案。

## 数据模型与持久化

`domain` 以 RFC 5545 的值为事实来源，导入/导出接近 1:1。

- **时间值**：`DTSTART` / `DTEND` / `DUE` / `COMPLETED` / `RECURRENCE-ID` 使用 `IcsTimeValue`（`Date`、`Floating`、`Zoned`、`Utc`）。结束时间排他；没有结束表示零时长定时事件；全天由 `Date` 推导，不再单独存储。
- **重复集合**：`Recurrence`（`RRULE` + `RDATE` + `EXDATE`）。主事件与例外共享同一个 `UID`，通过 `RECURRENCE-ID` 区分；`RDATE;VALUE=PERIOD` 保留每次实例长度。
- **重复规则实现**：按职责拆在同包。
  - `Recurrence.kt`：`OccurrenceTime`、实例时间与时间片段、集合裁剪/平移。
  - `RecurrenceMatching.kt`：`BY*` 匹配、日/周/月/年候选与 `BYSETPOS`。
  - `RecurrenceIndex.kt`：`COUNT` 索引与序数。
  - `TodoRecurrence.kt`：`Todo` 系列锚点、`occursOn`、`nextOccurrenceDate`。
  - 跨文件符号只放宽到 `internal`。
- **持久化主结构**：`AppState.calendars`。每个 `Calendar` 持有自己的事件、待办、日志、日历级属性与未知 `VTIMEZONE` 定义；`AppState.events` / `todos` / `journals` 只是跨日历的只读投影。相同的 `UID` 可以同时存在于不同日历而互不覆盖。
- **时区**：`VTIMEZONE` 属于 `Calendar`，事件只保存 `TZID`；未解析的未知时区由所属日历的定义兜底。
- **界面投影**：设备时区只通过 `day` / `endDay` / `localStart` / `localEnd` / `occursOn` / `timeRangeOn` 等展示投影参与界面，不写回持久化模型。
- **本地归属**：`importSource` / `sourceLine` 等放在 `LocalRecordMeta`，不与 RFC 属性混在一起；订阅记录以所属 `Calendar` 为作用域，不再用 `importSource` 前缀充当日历身份。
- **持久化**：只写当前结构（`schemaVersion = 6`），存储是 SQLDelight/SQLite（`storage.db.CrkoDatabase`），跨平台表结构在 `shared/src/commonMain/sqldelight/com/crko/storage/db/Crko.sq`。旧版本 JSON 持久化的兼容迁移代码已移除。

### 存储实现

使用 SQLDelight / SQLite：Android 数据库位于应用私有 `databases/crko.db`，iOS 位于 `Application Support/Crko/crko.db`。设置保存在 `SharedPreferences`（Android）/ `NSUserDefaults`（iOS）。

- 每条事件、待办、日志存为一行，RFC JSON payload 是事实来源；`calendar_id` / `ics_uid` / 时间与状态列只用于查询、差异写入和后台读取。
- 首次启动在 meta 表标记 `initialized` 并写入种子数据（debug 构建为示例日程），保证种子只应用一次。
- 数据库打不开或读不出来时，本次进程退到内存存储继续运行，损坏的库文件保留在磁盘上供诊断。

## 校验与防回归

三个守卫任务挂到 `:shared:check`，CI 在单元测试前显式执行：

```bash
./gradlew :shared:verifyDomainModel :shared:verifyArchitecture :shared:verifyFileSizes
```

- `verifyDomainModel`：检查旧 `Event` / `Todo` 字段名是否以属性声明形式重新出现在 `domain` 或 `state` 的任意非迁移文件中（不只 `Models.kt`，也含 `Event.date` 与 `RepeatRule.weekdays` / `rDates`）、合成 UID 标记（`#override-` / `#future-` / `#prior-`）或 `sub:` 身份前缀是否回到数据层、ICS 与 iTIP 导入导出路径（`Ics*.kt` / `Itip.kt`）是否重新读取设备时区。
- `verifyArchitecture`：按包路径检查跨层 import（`domain` 不得依赖 `storage/sync/state/ui/i18n/dev`，`storage` 不得依赖 `sync/state/ui/i18n/dev`，`ui` 不得依赖 `storage/sync/dev` 等）。
- `verifyFileSizes`：`commonMain` 单个 Kotlin 文件不超过 800 行。

两个架构/尺寸守卫的 allowlist 均已清空，新增违规只能改代码、不能加豁免。

## 界面与主题

- 莫奈与自选色盘由 `ThemeController` 以 `MonetSystem` 模式驱动，无配色使用 `ColorSchemeMode.System`（即 miuix 自带的默认色板）；所有 miuix 组件与 Material `TimePicker` 共享同一套颜色。
- 整页使用 `safeDrawingPadding`，因为 iOS 上 `WindowInsets.statusBars` 为空。
- 对话框使用 `WindowDialog`（独立窗口），不依赖 Scaffold 的 `MiuixPopupHost`。

