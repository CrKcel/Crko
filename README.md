# Crko

<p align="center">
  <img src="docs/icons/preview/crko-icon-192.png" width="96" alt="Crko 图标">
</p>

<p align="center">日程与待办应用，Android 与 iOS 共用 Kotlin Multiplatform + Compose Multiplatform 界面。</p>

<p align="center">
  <a href="#功能">功能</a> ·
  <a href="#构建">构建</a> ·
  <a href="#项目结构">项目结构</a> ·
  <a href="#数据模型">数据模型</a> ·
  <a href="#校验与防回归">校验</a> ·
  <a href="#开发约定">约定</a> ·
  <a href="#许可证">许可证</a>
</p>

## 功能

底部三栏：**待办**（左）、**日程**（中）、**更多**（右）。启动后进入日程页。

| 页面 | 内容 |
| --- | --- |
| 日程 | 周日期条、日/周时间轴、日/周列表、月历、日程编辑与重复规则 |
| 待办 | 优先级、开始/截止日期、完成状态与进度、备注、地点、分类；新建与编辑共用编辑器；已完成项折叠展示 |
| 更多 | 统计、多日历管理、设置、ICS 导入导出 |

### 日程

上方为当周日期条，下方展示所选日期（默认今天）的日程。

**时间轴**

- 00:00–24:00，每小时一格；点击空白时段新建日程。
- 竖向可无限上下滚动，跨过 00:00/24:00 即无缝衔接到上一天/下一天。
- 每天以日期标题分块；标题滚动时吸顶，跨天时由下一天的标题接替。
- 横向滑动切换到列表视图。

**列表**

- 一次展示一天，左右滑动切换相邻日期，页内竖向滚动。
- 在顶部继续下拉、在底部继续上拉（内容不足一屏时直接竖滑）可切回时间轴。

**周历与月历**

- 星期栏固定在顶部。
- 下拉日期区时，整月网格以本周为锚点上下展开；上滑收起，也可点击箭头切换。
- 左右滑动切换月份。

**整周总览**

- 通过视图菜单或下方区域双指捏合进入，双指张开退回日视图。
- 同样支持两种形式：
  - **时间轴**：七列，一页一周；页内先竖向滚动，滚到顶部/底部后继续滑动翻到上/下一周；星期日期条固定在顶部跟随。
  - **列表**：一次一周，左右滑动切换相邻周，页内竖向滚动。
- 两种形式之间可横向/竖向滑动切换，上方周历不受影响。

**周历联动**

- 时间轴滚动时，上方周历实时跟随当前所在的天/周；换周时保持所选星期。
- 列表视图左右滑动跨周时，上方周历同步滑动，停靠后再更新所选日期。

**新建与编辑**

- 可用周日期条点选本周任意一天，也可打开月历自选其他日期。
- 可设置全天、跨天/多天、截止到午夜，以及地点、分类、状态、优先级、提醒等属性。
- 重复支持每天、每周、每月、每年与自定义星期，并可设置间隔、次数或结束日期。
- 编辑重复日程时，可选择“仅此次 / 此次及以后 / 整个系列”。

### 更多

- **日历**：新建、重命名、配色、隐藏/显示、设为默认；删除前确认并显示记录数量，最后一个本地日历不可删除；某个日历可单独导出。
- **统计**：今日完成、进行中、未来三天日程、近七天完成量、按优先级分布。
- **设置**：见下方[设置](#设置)一节。
- **ICS 导入导出**：日程与待办一起导出为 `.ics` 文件，也可从其他日历应用导入。全天/多日事件、时区（TZID/VTIMEZONE）、重复规则与例外日期、提醒（VALARM）及常见 VEVENT 属性会尽量保留。

### 设置

- **语言**：随系统，或手动选择中文/英文（其他语言回退英文）。
- **外观**：随系统，或浅色/深色。
- **配色**：莫奈（Android 使用壁纸取色，其他平台回退自选种子色）、无配色、自选色盘。

### 数据存储

数据保存在本机：Android 使用 SharedPreferences，iOS 使用 UserDefaults。

## 构建

环境：JDK 21、Android SDK；iOS 构建需 Xcode。

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
│   ├── migration/  旧版持久化 DTO 与迁移转换（只被 storage 与迁移测试依赖）
│   ├── storage/    持久化接口、平台声明（expect）与文本解码（CalendarTextReader）
│   ├── sync/       订阅抓取与 CalDAV 协议实现
│   ├── state/      AppStateHolder 门面 + event/todo/journal/import/settings/persistence 服务
│   ├── i18n/       中英文文案
│   └── ui/         主题、组件、日程/待办/更多三个页面；ui/transfer 只编排导入导出
└── commonTest/kotlin/com/crko/
    ├── Ics*Test.kt 读写、时区、附件、往返与重复实例按场景拆分
    └── StateHolder 相关测试按服务拆分
```

依赖方向按层单向：

- `domain`：不依赖任何其他包。
- `migration` → `domain`
- `storage` → `domain`、`migration`
- `sync` → `domain`、`storage`
- `state` → `domain`、`migration`、`storage`、`sync`、`i18n`
- `ui` → `domain`、`i18n`、`state`（不直接依赖 `storage`、`sync`）
- `com.crko.dev` 只允许组合根 `App.kt` 与测试引用。
- `state` 对 `i18n` 只用于用户可见文案。

## 数据模型

`domain` 以 RFC 5545 的值为唯一事实来源，导入/导出接近 1:1。

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
- **迁移**：旧版持久化 JSON 由 `migration` 包的 `LegacyModels` / `LegacyEventCompat` 与 `storage.StateCodec` 迁移；`domain` 不包含迁移 DTO，新版本只写当前结构（`schemaVersion = 6`）。

## 校验与防回归

三个守卫任务都已挂到 `:shared:check`，CI 在单元测试前显式执行：

```bash
./gradlew :shared:verifyDomainModel :shared:verifyArchitecture :shared:verifyFileSizes
```

- `verifyDomainModel`：检查旧 `Event` / `Todo` 字段名是否以属性声明形式重新出现在 `domain` 或 `state` 的任意非迁移文件中（不只 `Models.kt`，也含 `Event.date` 与 `RepeatRule.weekdays` / `rDates`）、合成 UID 标记（`#override-` / `#future-` / `#prior-`）或 `sub:` 身份前缀是否回到 `migration` 包之外、ICS 与 iTIP 导入导出路径（`Ics*.kt` / `Itip.kt`）是否重新读取设备时区。
- `verifyArchitecture`：按包路径检查跨层 import（`domain` 不得依赖 `storage/sync/state/ui/i18n/dev/migration`，`storage` 不得依赖 `sync/state/ui/i18n/dev`，`ui` 不得依赖 `storage/sync/dev` 等）。
- `verifyFileSizes`：`commonMain` 单个 Kotlin 文件不超过 800 行。

两个架构/尺寸守卫的 allowlist 均已清空，新增违规只能改代码、不能加豁免。

提交前另需本地通过（iOS 编译尚未加入 CI）：

```bash
./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug
./gradlew :shared:compileKotlinIosSimulatorArm64
```

## 开发约定

- 莫奈与自选色盘由 `ThemeController` 以 `MonetSystem` 模式驱动，无配色使用 `ColorSchemeMode.System`（即 miuix 自带的默认色板）；所有 miuix 组件与 Material `TimePicker` 共享同一套颜色。
- 整页使用 `safeDrawingPadding`，因为 iOS 上 `WindowInsets.statusBars` 为空。
- 对话框使用 `WindowDialog`（独立窗口），不依赖 Scaffold 的 `MiuixPopupHost`。

## 许可证

- **GPL-3.0-or-later**
- **App Store** 分发及任何**闭源分发**的构建，不适用 GPLv3，请获取商业许可。
