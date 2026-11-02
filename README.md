# Crko

日程与待办应用。Android 与 iOS 共用 Kotlin Multiplatform + Compose Multiplatform 界面。

## 功能

- 底部三栏：待办（左）、日程（中）、更多（右）。启动进入日程页。
- 日程：上方为当周日期条，下方为所选日期（默认今天）的日程；时间轴为 00:00–24:00、每小时一格，点击空白时段新建日程。时间轴竖向可无限上下滚动，跨过 00:00/24:00 即无缝衔接到上一/下一天，每天以日期标题分块，日期标题滚动时吸顶、跨天时由下一天的标题接替，横向滑动切换到列表视图；列表视图一次展示一天，左右滑动切换相邻日期，页内竖向滚动，在顶部继续下拉、在底部继续上拉（或内容不足一屏时直接竖滑）可切回时间轴。星期栏固定在顶部，下拉日期区时整月网格以本周为锚点上下展开（上滑收起，也可点击箭头切换），左右滑动切换月份。通过视图菜单或下方区域双指捏合进入整周总览、双指张开退回日视图，总览同样支持时间轴（七列，一页一周，页内先竖向滚动，滚到顶部/底部后继续滑动翻到上/下一周，星期日期条固定在顶部跟随）与列表（一次一周，左右滑动切换相邻周，页内竖向滚动）两种形式，两者之间同样可横向/竖向滑动切换，上方周历不受影响；时间轴滚动时上方周历会实时跟随当前所在的天/周（换周时保持所选星期）；列表视图左右滑动跨周时上方周历同步滑动，停靠后再更新所选日期。新建或编辑日程时可用周日期条点选本周任意一天，也可打开月历自选其他日期；可设置全天、跨天/多天、截止到午夜，以及地点、分类、状态、优先级、提醒等属性。重复支持每天、每周、每月、每年与自定义星期，并可设置间隔、次数或结束日期；编辑重复日程时可选择“仅此次 / 此次及以后 / 整个系列”。
- 待办：优先级、截止日期、完成状态与进度；已完成项折叠展示。
- 更多：统计（今日完成、进行中、未来三天日程、近七天完成量、按优先级分布）与设置；支持 ICS 导入导出，日程与待办会一起导出为 .ics 文件，也可从其他日历应用导入。全天/多日事件、时区（TZID/VTIMEZONE）、重复规则与例外日期、提醒（VALARM）及常见 VEVENT 属性会尽量保留。
- 设置：语言随系统或手动选择中文/英文（其他语言回退英文）；外观随系统或浅色/深色；配色为莫奈（Android 使用壁纸取色，其他平台回退自选种子色）、无配色、自选色盘。
- 数据保存在本机：Android 使用 SharedPreferences，iOS 使用 UserDefaults。

## 构建

```bash
# Android
./gradlew :androidApp:assembleDebug

# 单元测试（JVM host）
./gradlew :shared:testAndroidHostTest

# iOS（需要 Xcode）
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 18 Pro' build
```

## 结构

```
shared/src/commonMain/kotlin/com/crko/
  domain/     数据模型与日期计算（纯 Kotlin，可在 commonTest 中测试）
  storage/    持久化接口与平台声明（expect）
  state/      AppStateHolder：日程、待办、设置的状态与写入校验
  i18n/       中英文文案
  ui/         主题、组件、日程/待办/更多三个页面
```

## 数据模型

`domain` 以 RFC 5545 的值为唯一事实来源，导入/导出接近 1:1：

- `DTSTART` / `DTEND` / `DUE` / `COMPLETED` / `RECURRENCE-ID` 使用 `IcsTimeValue`（`Date`、`Floating`、`Zoned`、`Utc`）。结束时间排他；没有结束表示零时长定时事件；全天由 `Date` 推导，不再单独存储。
- 重复集合是 `Recurrence`（`RRULE` + `RDATE` + `EXDATE`）。主事件与例外共享同一个 `UID`，通过 `RECURRENCE-ID` 区分；`RDATE;VALUE=PERIOD` 保留每次实例长度。
- `AppState.calendars` 是持久化主结构：每个 `Calendar` 持有自己的事件、待办、日志、日历级属性与未知 `VTIMEZONE` 定义；`AppState.events` / `todos` / `journals` 只是跨日历的只读投影。相同的 `UID` 可以同时存在于不同日历而互不覆盖。
- `VTIMEZONE` 属于 `Calendar`，事件只保存 `TZID`；未解析的未知时区由所属日历的定义兜底。
- 设备时区只通过 `day` / `endDay` / `localStart` / `localEnd` / `occursOn` / `timeRangeOn` 等展示投影参与界面，不写回持久化模型。
- `importSource` / `sourceLine` 等本地归属放在 `LocalRecordMeta`，不与 RFC 属性混在一起；订阅记录以所属 `Calendar` 为作用域，不再用 `importSource` 前缀充当日历身份。
- 旧版持久化 JSON 由 `LegacyModels` 与 `Platform.decodeStateWithReport` 迁移；新版本只写当前结构（`schemaVersion = 6`）。

防回归：

```bash
./gradlew :shared:verifyDomainModel
```

检查 `commonMain` 中是否重新出现旧 `Event` 字段、合成 UID（`#override-` / `#future-` / `#prior-`）或 ICS 导入导出路径上的设备时区。

约定：

- 莫奈与自选色盘由 `ThemeController` 以 `MonetSystem` 模式驱动，无配色使用 `ColorSchemeMode.System`（即 miuix 自带的默认色板）；所有 miuix 组件与 Material `TimePicker` 共享同一套颜色。
- 整页使用 `safeDrawingPadding`，因为 iOS 上 `WindowInsets.statusBars` 为空。
- 对话框使用 `WindowDialog`（独立窗口），不依赖 Scaffold 的 `MiuixPopupHost`。

## 许可证

- **GPL-3.0-or-later**
- **App Store** 分发及任何**闭源分发**的构建，不适用 GPLv3，请获取商业许可。