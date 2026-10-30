# Crko

日程与待办应用。Android 与 iOS 共用 Kotlin Multiplatform + Compose Multiplatform 界面。

## 功能

- 底部三栏：待办（左）、日程（中）、更多（右）。启动进入日程页。
- 日程：上方为当周日期条，下方为所选日期（默认今天）的日程；时间轴为 00:00–24:00、每小时一格，点击空白时段新建日程。时间轴竖向可无限上下滚动，跨过 00:00/24:00 即无缝衔接到上一/下一天，每天以日期标题分块，日期标题滚动时吸顶、跨天时由下一天的标题接替，横向滑动切换到列表视图；列表视图一次展示一天，左右滑动切换相邻日期，页内竖向滚动，在顶部继续下拉、在底部继续上拉（或内容不足一屏时直接竖滑）可切回时间轴。星期栏固定在顶部，下拉日期区时整月网格以本周为锚点上下展开（上滑收起，也可点击箭头切换），左右滑动切换月份。通过视图菜单或下方区域双指捏合进入整周总览、双指张开退回日视图，总览同样支持时间轴（七列，竖向无限滚动衔接上/下一周，星期日期条固定在顶部跟随）与列表（一次一周，左右滑动切换相邻周，页内竖向滚动）两种形式，两者之间同样可横向/竖向滑动切换，上方周历不受影响；滚动停靠后，上方周历与列表视图的日期摘要会跟随当前所在的天/周，换周时保持所选星期。新建或编辑日程时可设置重复：每天、每周或自定义星期，重复日程会出现在之后所有匹配的日期。
- 待办：优先级、截止日期、完成状态；已完成项折叠展示。
- 更多：统计（今日完成、进行中、未来三天日程、近七天完成量、按优先级分布）与设置。
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

约定：

- 莫奈与自选色盘由 `ThemeController` 以 `MonetSystem` 模式驱动，无配色使用 `ColorSchemeMode.System`（即 miuix 自带的默认色板）；所有 miuix 组件与 Material `TimePicker` 共享同一套颜色。
- 整页使用 `safeDrawingPadding`，因为 iOS 上 `WindowInsets.statusBars` 为空。
- 对话框使用 `WindowDialog`（独立窗口），不依赖 Scaffold 的 `MiuixPopupHost`。
