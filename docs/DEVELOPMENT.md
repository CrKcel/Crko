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
│   ├── sync/       订阅抓取、CalDAV 协议与 crko.cloud 客户端实现
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

## 云同步（crko.cloud）

与 CalDAV 同步平行的自有同步方案，服务端是独立的 Cloudflare Worker + D1（代码在 `crko.cloud` 仓库）。设计原则：**Worker 只做鉴权、JSON 转发和 D1 读写，不解析业务对象；数据端到端加密；业务逻辑全部在客户端。**

### 覆盖范围与触发

- 只同步**本地日历**（`syncState == null`）及其日程/待办/手帐、日历配置（名称/颜色/可见性/时区定义）和应用设置；CalDAV 与订阅日历继续走各自的服务器。
- 触发：用户编辑即时推拉（`persist(ChangeOrigin.User)` → outbox 落行 → `cloud.syncDue()`）；前台轮询每分钟一次（`App.kt` 的 60s 循环调用 `syncDueCloud()`），拉取间隔至少 5 分钟；设置页可手动「立即同步」。

### 加密与账号

- 密钥派生：`PBKDF2-HMAC-SHA256(密码, 服务器下发 salt, 600k)` → master，再经两个 HMAC 域（`crko/auth` / `crko/enc`）拆成 authKey 与 encKey。authKey 仅用于认证，服务器只存其 SHA-256；encKey 加密所有 payload，保存在平台 keystore/keychain（`SecretStore`），**从不出客户端**。
- payload 格式：`v1.` + base64(nonce ‖ AES-256-GCM 密文 ‖ tag)，AAD 绑定 `kind|key`，防止密文被挪用到别的实体上。
- 密码学原语是 `storage/` 下的**纯 Kotlin 实现**（SHA-256/HMAC/PBKDF2/AES-GCM，NIST/RFC 向量测试锁定），平台 actual 只提供随机数——两端密文逐字节一致，没有 provider 差异。

### 同步协议与合并

- outbox 表带 `backend` 列（`caldav` / `cloud`），同一条记录可同时排在两个后端；`OutboxRecorder` 按「CalDAV 可写 → caldav 行、云同步开启的本地日历 → cloud 行」扇出，日历的配置变化也入队（仅 cloud）。
- 推送：`CloudPushService` 把待发行打包成 `{kind, key, seq, ts, deleted, payload}` 批量 `POST /v1/sync`；删除发送墓碑（`seq = 原 SEQUENCE + 1`、无 payload）。服务器按 `(seq, ts)` 元组条件 upsert，旧版本被拒（stale）后直接丢行，由随后的拉取收敛——没有冲突协商。
- 拉取：`CloudSyncService` 按游标增量拉取 `changes` 日志（新设备 `cursor=0` 全量拉 `entities` 快照），按与 CalDAV 相同的 `isNewer`（SEQUENCE → DTSTAMP → LAST-MODIFIED）规则合并；有待发行（`backend=cloud`）的记录跳过不覆盖，待推送的内容胜出后再被下次拉取回读。
- 账号与设备簿记（游标、bootstrap 标记、设置指纹）在 `CloudAccountStore`（平台偏好，不同步、不入库）；开启同步时先 bootstrap（全量本地内容入队），登录/注册会重置这些簿记。

### 已知边界

- 设置是单块加密 blob 的 LWW：两端离线各改设置时，后推的一方整体胜出，不按字段合并。
- 同步关闭期间的删除不会生成墓碑，重新开启后在其他设备上可能需要再次手动删除。
- 附件不同步；服务端 `changes` 日志无 GC（量大后可在 Worker 加 Cron 清理旧行）。


