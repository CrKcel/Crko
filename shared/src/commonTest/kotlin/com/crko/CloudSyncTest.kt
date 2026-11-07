package com.crko

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.PersistedSettings
import com.crko.state.AppMessage
import com.crko.state.ChangeOrigin
import com.crko.state.StateAccess
import com.crko.state.sync.CloudCalendarConfig
import com.crko.state.sync.CloudPushService
import com.crko.state.sync.CloudSyncService
import com.crko.storage.CloudAccountConfig
import com.crko.storage.MemoryCloudAccountStore
import com.crko.storage.MemoryOutboxStore
import com.crko.storage.OutboxBackend
import com.crko.storage.OutboxEntry
import com.crko.storage.PureCloudCrypto
import com.crko.storage.SecretStore
import com.crko.storage.HttpRequest
import com.crko.storage.HttpResponse
import com.crko.storage.SubscriptionHttp
import com.crko.storage.openText
import com.crko.storage.sealText
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cloud sync engines against a scripted HTTP fake and the memory stores. */
class CloudSyncTest {

    private val encKey = ByteArray(32) { (it + 1).toByte() }
    private val crypto = PureCloudCrypto { count -> ByteArray(count) { (it * 11 + 1).toByte() } }

    private class FakeHttp : SubscriptionHttp {
        val requests = mutableListOf<HttpRequest>()
        private val responders = ArrayDeque<HttpResponse>()

        /** Runs while a request is on the wire, to simulate a local edit during the push. */
        var onSend: (() -> Unit)? = null

        fun enqueue(status: Int, body: String) {
            responders += HttpResponse(status, emptyMap(), body.encodeToByteArray())
        }

        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            onSend?.invoke()
            return responders.removeFirstOrNull() ?: HttpResponse(500, emptyMap(), ByteArray(0))
        }
    }

    private class FakeSecrets : SecretStore {
        private val values = mutableMapOf<String, String>()

        override suspend fun save(key: String, value: String) {
            values[key] = value
        }

        override suspend fun load(key: String): String? = values[key]

        override suspend fun delete(key: String) {
            values.remove(key)
        }
    }

    private class FakeAccess(initial: AppState) : StateAccess {
        private var current = initial
        val persists = mutableListOf<ChangeOrigin>()
        val messages = mutableListOf<AppMessage>()

        override val state: AppState get() = current

        override fun persist(next: AppState, origin: ChangeOrigin) {
            persists += origin
            current = next
        }

        override fun notify(message: AppMessage) {
            messages += message
        }

        override val defaultCalendarId: String? get() = null

        override fun setDefaultCalendarId(id: String?) = Unit
    }

    private class FakeClock(private var ms: Long = 1_000_000) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(ms)

        fun advance(by: Long) {
            ms += by
        }
    }

    private fun event(id: String, title: String, uid: String? = null, sequence: Int = 0) = Event(
        id = id,
        title = title,
        start = IcsTimeValue.Utc(Instant.parse("2026-03-01T10:00:00Z")),
        end = IcsTimeValue.Utc(Instant.parse("2026-03-01T11:00:00Z")),
        icsUid = uid,
        sequence = sequence,
    )

    private fun pushService(
        access: FakeAccess,
        outbox: MemoryOutboxStore,
        accounts: MemoryCloudAccountStore,
        secrets: FakeSecrets,
        http: FakeHttp,
        clock: FakeClock,
    ) = CloudPushService(
        access = access,
        outbox = outbox,
        http = http,
        crypto = crypto,
        accountStore = accounts,
        secretStore = secrets,
        clock = clock,
        settingsJson = { "{}" },
    )

    private fun syncService(
        access: FakeAccess,
        outbox: MemoryOutboxStore,
        accounts: MemoryCloudAccountStore,
        secrets: FakeSecrets,
        http: FakeHttp,
        clock: FakeClock,
        push: CloudPushService,
        appliedSettings: MutableList<PersistedSettings>,
    ) = CloudSyncService(
        access = access,
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher()),
        outbox = outbox,
        http = http,
        crypto = crypto,
        accountStore = accounts,
        secretStore = secrets,
        clock = clock,
        idProvider = { "id-new" },
        settingsJson = { "{}" },
        applySettings = { appliedSettings += it },
        push = push,
        workDispatcher = null,
    )

    private suspend fun signedIn(accounts: MemoryCloudAccountStore, secrets: FakeSecrets) {
        accounts.save(CloudAccountConfig(server = "https://crko.test", name = "alice", enabled = true))
        // The "{}" fingerprint matches the services' settingsJson(), so pull-focused tests are not
        // polluted by an automatic settings push.
        accounts.savePushedSettings("{}")
        secrets.save("cloud-token", "token-1")
        secrets.save("cloud-enc-key", kotlin.io.encoding.Base64.encode(encKey))
    }

    private fun cloudRow(
        calendarId: String,
        entity: String,
        uid: String,
        operation: String,
        sequence: Long = 0,
    ) = OutboxEntry(
        calendarId = calendarId,
        entity = entity,
        uid = uid,
        recurrenceKey = "",
        operation = operation,
        sequence = sequence,
        backend = OutboxBackend.Cloud,
        createdMs = 1,
        updatedMs = 1,
    )

    @Test
    fun pushSealsPendingRecordsAndClearsTheRows() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        outbox.upsert(cloudRow("local", "event", "e1@crko", OutboxEntry.Operation.Create, sequence = 3))
        val access = FakeAccess(AppState(calendars = listOf(Calendar.local().copy(events = listOf(event("e1", "One", uid = "e1@crko", sequence = 3))))))
        http.enqueue(200, """{"applied":1,"stale":0,"cursor":1}""")

        val pushed = pushService(access, outbox, accounts, secrets, http, clock).drain()

        assertTrue(pushed)
        assertEquals(1, http.requests.size)
        assertEquals("https://crko.test/v1/sync", http.requests.single().url)
        assertTrue(outbox.entries().isEmpty())
        val body = http.requests.single().body.decodeToString()
        assertTrue(body.contains(""""kind":"event""""))
        assertTrue(body.contains(""""key":"local/e1@crko/""""))
        assertTrue(body.contains(""""seq":3"""))
        // The payload is sealed text that opens back to the record's JSON with the same key.
        val payload = Regex(""""payload":"(v1\.[^"]+)"""").find(body)!!.groupValues[1]
        val opened = crypto.openText(encKey, payload, "crko/v1|event|local/e1@crko/")
        assertTrue(opened!!.contains("\"title\":\"One\""))
    }

    @Test
    fun pushTombstonesOutrankTheDeletedRevision() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        outbox.upsert(cloudRow("local", "event", "e1@crko", OutboxEntry.Operation.Delete, sequence = 5))
        val access = FakeAccess(AppState())
        http.enqueue(200, """{"applied":1,"stale":0,"cursor":2}""")

        pushService(access, outbox, accounts, secrets, http, clock).drain()

        val body = http.requests.single().body.decodeToString()
        assertTrue(body.contains("\"seq\":6"))
        assertTrue(body.contains("\"deleted\":true"))
        assertTrue(!body.contains("\"payload\":\""))
    }

    @Test
    fun aStalePushStillDropsTheRowAndPullReconciles() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        outbox.upsert(cloudRow("local", "event", "e1@crko", OutboxEntry.Operation.Update, sequence = 1))
        val access = FakeAccess(AppState(calendars = listOf(Calendar.local())))
        http.enqueue(200, """{"applied":0,"stale":1,"cursor":9}""")

        pushService(access, outbox, accounts, secrets, http, clock).drain()

        assertTrue(outbox.entries().isEmpty())
    }

    @Test
    fun pushFailureBacksOffAndAnExpiredTokenNotifies() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        // A deletion needs no local record, so the row survives to carry the failure.
        outbox.upsert(cloudRow("local", "event", "e1@crko", OutboxEntry.Operation.Delete, sequence = 1))
        val access = FakeAccess(AppState(calendars = listOf(Calendar.local())))
        http.enqueue(401, """{"error":"unauthorized"}""")

        val service = pushService(access, outbox, accounts, secrets, http, clock)
        service.drain()

        val row = outbox.entries().single()
        assertEquals(1, row.attempts)
        assertEquals(1, access.messages.size)
        assertTrue(access.messages.single() is AppMessage.CloudAuthExpired)

        // Backoff: a fresh drain before the retry time does not touch the network.
        http.enqueue(200, """{"applied":1,"stale":0,"cursor":1}""")
        service.drain()
        assertEquals(1, http.requests.size)
    }

    @Test
    fun pullAppliesANewerRemoteRecord() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        accounts.saveBootstrapped(true)
        val remote = event("remote-1", "From phone", uid = "e9@crko", sequence = 4)
        val payload = crypto.sealText(
            encKey,
            AppJson.encodeToString(Event.serializer(), remote),
            "crko/v1|event|local/e9@crko/",
        )
        http.enqueue(
            200,
            """{"cursor":7,"changes":[{"kind":"event","key":"local/e9@crko/","seq":4,"ts":100,"deleted":false,"payload":"$payload"}]}""",
        )
        val access = FakeAccess(AppState(calendars = listOf(Calendar.local())))
        val service = syncService(access, outbox, accounts, secrets, http, clock, pushService(access, outbox, accounts, secrets, http, clock), mutableListOf())

        service.syncNow()

        val stored = access.state.calendars.first().events.single()
        assertEquals("From phone", stored.title)
        assertEquals(4, stored.sequence)
        assertEquals(ChangeOrigin.Sync, access.persists.single())
        assertEquals(7, accounts.loadCursor())
    }

    @Test
    fun pullKeepsTheLocalRecordWhenTheRemoteIsNotNewer() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        accounts.saveBootstrapped(true)
        val local = event("e1", "Local edit", uid = "e1@crko", sequence = 5)
        val remote = event("other", "Stale phone", uid = "e1@crko", sequence = 4)
        val payload = crypto.sealText(
            encKey,
            AppJson.encodeToString(Event.serializer(), remote),
            "crko/v1|event|local/e1@crko/",
        )
        http.enqueue(
            200,
            """{"cursor":7,"changes":[{"kind":"event","key":"local/e1@crko/","seq":4,"ts":100,"deleted":false,"payload":"$payload"}]}""",
        )
        val access = FakeAccess(AppState(calendars = listOf(Calendar.local().copy(events = listOf(local)))))

        syncService(access, outbox, accounts, secrets, http, clock, pushService(access, outbox, accounts, secrets, http, clock), mutableListOf()).syncNow()

        assertEquals("Local edit", access.state.calendars.first().events.single().title)
        assertTrue(access.persists.isEmpty())
    }

    @Test
    fun pullTombstonesRemoveOlderLocalsOnly() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        accounts.saveBootstrapped(true)
        val older = event("e1", "Doomed", uid = "e1@crko", sequence = 2)
        val newer = event("e2", "Survivor", uid = "e2@crko", sequence = 9)
        http.enqueue(
            200,
            """{"cursor":3,"changes":[
                {"kind":"event","key":"local/e1@crko/","seq":3,"ts":100,"deleted":true},
                {"kind":"event","key":"local/e2@crko/","seq":2,"ts":100,"deleted":true}
            ]}""",
        )
        val access = FakeAccess(AppState(calendars = listOf(Calendar.local().copy(events = listOf(older, newer)))))

        syncService(access, outbox, accounts, secrets, http, clock, pushService(access, outbox, accounts, secrets, http, clock), mutableListOf()).syncNow()

        val remaining = access.state.calendars.first().events
        assertEquals(1, remaining.size)
        assertEquals("Survivor", remaining.single().title)
    }

    @Test
    fun pullFollowsTheCursorAcrossPagesUntilAShortOne() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        accounts.saveBootstrapped(true)
        // A full page (CloudPullPage == 200) must keep the loop going; tombstones need no sealed
        // payload, so the page can be filler.
        val fullPage = (0 until 200).joinToString(",") { i ->
            """{"kind":"event","key":"local/t$i@crko/","seq":1,"ts":100,"deleted":true}"""
        }
        val remote = event("remote-paged", "From page two", uid = "paged@crko", sequence = 2)
        val payload = crypto.sealText(
            encKey,
            AppJson.encodeToString(Event.serializer(), remote),
            "crko/v1|event|local/paged@crko/",
        )
        http.enqueue(200, """{"cursor":1,"changes":[$fullPage]}""")
        http.enqueue(
            200,
            """{"cursor":2,"changes":[{"kind":"event","key":"local/paged@crko/","seq":2,"ts":100,"deleted":false,"payload":"$payload"}]}""",
        )
        val access = FakeAccess(AppState(calendars = listOf(Calendar.local())))
        val service = syncService(access, outbox, accounts, secrets, http, clock, pushService(access, outbox, accounts, secrets, http, clock), mutableListOf())

        service.syncNow()

        // The content of the short page is applied and its cursor is the one that persists.
        assertEquals("From page two", access.state.calendars.first().events.single().title)
        assertEquals(2, accounts.loadCursor())
        val pulls = http.requests.filter { it.method == "GET" }
        assertEquals(2, pulls.size)
        assertTrue(pulls[0].url.contains("cursor=0&"), pulls[0].url)
        assertTrue(pulls[1].url.contains("cursor=1&"), pulls[1].url)
    }

    @Test
    fun pullSkipsRecordsWithPendingCloudRows() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        accounts.saveBootstrapped(true)
        outbox.upsert(cloudRow("local", "event", "e1@crko", OutboxEntry.Operation.Update, sequence = 5))
        val local = event("e1", "Pending edit", uid = "e1@crko", sequence = 5)
        val remote = event("other", "Remote version", uid = "e1@crko", sequence = 6)
        val payload = crypto.sealText(
            encKey,
            AppJson.encodeToString(Event.serializer(), remote),
            "crko/v1|event|local/e1@crko/",
        )
        http.enqueue(
            200,
            """{"cursor":7,"changes":[{"kind":"event","key":"local/e1@crko/","seq":6,"ts":100,"deleted":false,"payload":"$payload"}]}""",
        )
        val access = FakeAccess(AppState(calendars = listOf(Calendar.local().copy(events = listOf(local)))))

        syncService(access, outbox, accounts, secrets, http, clock, pushService(access, outbox, accounts, secrets, http, clock), mutableListOf()).syncNow()

        assertEquals("Pending edit", access.state.calendars.first().events.single().title)
    }

    @Test
    fun pullCreatesAndDeletesCalendarsFromConfigs() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        accounts.saveBootstrapped(true)
        val config = com.crko.state.sync.CloudCalendarConfig(id = "cal-x", name = "Work", colorArgb = 0xFF112233)
        val payload = crypto.sealText(
            encKey,
            AppJson.encodeToString(com.crko.state.sync.CloudCalendarConfig.serializer(), config),
            "crko/v1|calendar|cal-x",
        )
        http.enqueue(
            200,
            """{"cursor":2,"changes":[
                {"kind":"calendar","key":"cal-x","seq":0,"ts":100,"deleted":false,"payload":"$payload"},
                {"kind":"calendar","key":"cal-gone","seq":0,"ts":100,"deleted":true}
            ]}""",
        )
        val access = FakeAccess(AppState(calendars = listOf(Calendar.local())))
        val appliedSettings = mutableListOf<PersistedSettings>()

        syncService(access, outbox, accounts, secrets, http, clock, pushService(access, outbox, accounts, secrets, http, clock), appliedSettings).syncNow()

        val created = assertNotNull(access.state.calendars.firstOrNull { it.id == "cal-x" })
        assertEquals("Work", created.name)
        assertNull(access.state.calendars.firstOrNull { it.id == "cal-gone" })
        assertEquals("local", access.state.calendars.first().id)
    }

    @Test
    fun pullAppliesRemoteSettingsOnce() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        accounts.saveBootstrapped(true)
        val settings = PersistedSettings(darkMode = "Dark", language = "zh")
        val json = AppJson.encodeToString(PersistedSettings.serializer(), settings)
        val payload = crypto.sealText(encKey, json, "crko/v1|settings|settings")
        http.enqueue(
            200,
            """{"cursor":1,"changes":[{"kind":"settings","key":"settings","seq":0,"ts":100,"deleted":false,"payload":"$payload"}]}""",
        )
        val access = FakeAccess(AppState())
        val appliedSettings = mutableListOf<PersistedSettings>()

        syncService(access, outbox, accounts, secrets, http, clock, pushService(access, outbox, accounts, secrets, http, clock), appliedSettings).syncNow()

        assertEquals(listOf(settings), appliedSettings)
        assertEquals(json, accounts.loadAppliedSettings())
        // The same blob again is a no-op.
        http.enqueue(
            200,
            """{"cursor":1,"changes":[{"kind":"settings","key":"settings","seq":0,"ts":100,"deleted":false,"payload":"$payload"}]}""",
        )
        accounts.saveCursor(0)
        syncService(access, outbox, accounts, secrets, http, clock, pushService(access, outbox, accounts, secrets, http, clock), appliedSettings).syncNow()
        assertEquals(1, appliedSettings.size)
    }

    @Test
    fun aFreshAccountBootstrapsEverythingLocal() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        // The bootstrap pass must include the settings blob, so clear the fingerprint.
        accounts.savePushedSettings(null)
        val access = FakeAccess(
            AppState(
                calendars = listOf(
                    Calendar.local(name = "Personal").copy(events = listOf(event("e1", "One", sequence = 2))),
                    Calendar(id = "cal-2", name = "Second").copy(todos = listOf(com.crko.domain.Todo(id = "t1", title = "Buy"))),
                ),
            ),
        )
        http.enqueue(200, """{"applied":5,"stale":0,"cursor":4}""")
        http.enqueue(200, """{"cursor":0,"changes":[]}""")

        syncService(access, outbox, accounts, secrets, http, clock, pushService(access, outbox, accounts, secrets, http, clock), mutableListOf()).syncNow()

        assertTrue(accounts.loadBootstrapped())
        // The bootstrap pushed 2 calendar configs + 2 records + the settings blob, and the drain
        // consumed the rows again (both responses answered).
        assertTrue(outbox.entriesForBackend(OutboxBackend.Cloud).isEmpty())
        val pushBody = http.requests.first { it.method == "POST" }.body.decodeToString()
        assertTrue(pushBody.contains("\"kind\":\"calendar\""))
        assertTrue(pushBody.contains("\"kind\":\"event\""))
        assertTrue(pushBody.contains("\"kind\":\"todo\""))
        assertTrue(pushBody.contains("\"kind\":\"settings\""))
    }

    @Test
    fun bootstrapKeepsAPendingCalendarRowInsteadOfDuplicatingIt() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        // A rename queued before the first pass is the newest local intent and must survive.
        outbox.upsert(cloudRow("local", "calendar", "local", OutboxEntry.Operation.Update, sequence = 0))
        val access = FakeAccess(AppState(calendars = listOf(Calendar.local(name = "Renamed"))))
        http.enqueue(500, """{"error":"boom"}""")
        http.enqueue(200, """{"cursor":0,"changes":[]}""")

        syncService(access, outbox, accounts, secrets, http, clock, pushService(access, outbox, accounts, secrets, http, clock), mutableListOf()).syncNow()

        // A second insert would also violate the outbox's unique (backend, calendar, entity, uid) key.
        assertEquals(1, outbox.entries().count { it.entity == "calendar" })
    }

    @Test
    fun pullIgnoresStaleSettingsButAppliesTheNewerOne() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        accounts.saveBootstrapped(true)
        accounts.saveAppliedVersions(mapOf("settings" to "0:500"))
        val stale = PersistedSettings(darkMode = "Dark")
        val fresh = PersistedSettings(darkMode = "Light")
        val stalePayload = crypto.sealText(
            encKey,
            AppJson.encodeToString(PersistedSettings.serializer(), stale),
            "crko/v1|settings|settings",
        )
        val freshPayload = crypto.sealText(
            encKey,
            AppJson.encodeToString(PersistedSettings.serializer(), fresh),
            "crko/v1|settings|settings",
        )
        http.enqueue(
            200,
            """{"cursor":2,"changes":[
                {"kind":"settings","key":"settings","seq":0,"ts":100,"deleted":false,"payload":"$stalePayload"},
                {"kind":"settings","key":"settings","seq":0,"ts":600,"deleted":false,"payload":"$freshPayload"}
            ]}""",
        )
        val access = FakeAccess(AppState())
        val appliedSettings = mutableListOf<PersistedSettings>()

        syncService(access, outbox, accounts, secrets, http, clock, pushService(access, outbox, accounts, secrets, http, clock), appliedSettings).syncNow()

        // The stale log entry the server appended for an earlier rejected push is skipped.
        assertEquals(listOf(fresh), appliedSettings)
    }

    @Test
    fun pullIgnoresAnOlderCalendarConfig() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        accounts.saveBootstrapped(true)
        accounts.saveAppliedVersions(mapOf("calendar:local" to "0:500"))
        val config = CloudCalendarConfig(id = "local", name = "Stale name")
        val payload = crypto.sealText(
            encKey,
            AppJson.encodeToString(CloudCalendarConfig.serializer(), config),
            "crko/v1|calendar|local",
        )
        http.enqueue(
            200,
            """{"cursor":1,"changes":[{"kind":"calendar","key":"local","seq":0,"ts":100,"deleted":false,"payload":"$payload"}]}""",
        )
        val access = FakeAccess(AppState(calendars = listOf(Calendar.local(name = "Local name"))))

        syncService(access, outbox, accounts, secrets, http, clock, pushService(access, outbox, accounts, secrets, http, clock), mutableListOf()).syncNow()

        assertEquals("Local name", access.state.calendars.first().name)
        assertTrue(access.persists.isEmpty())
    }

    @Test
    fun aPushInFlightKeepsANewerEditQueued() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        accounts.saveBootstrapped(true)
        val stored = event("e1", "First draft", uid = "e1@crko", sequence = 1)
        val access = FakeAccess(AppState(calendars = listOf(Calendar.local().copy(events = listOf(stored)))))
        outbox.upsert(cloudRow("local", "event", "e1@crko", OutboxEntry.Operation.Update, sequence = 1))
        http.onSend = {
            // The user edits while the batch is on the wire; the recorder re-queues the same row.
            val pending = outbox.entries().single()
            outbox.upsert(pending.copy(sequence = 2, updatedMs = 2))
        }
        http.enqueue(200, """{"applied":1,"stale":0,"cursor":1}""")

        pushService(access, outbox, accounts, secrets, http, clock).drain()

        // Dropping the drained snapshot would silently lose the edit until the next local persist.
        assertEquals(2, outbox.entries().single().sequence)
    }

    @Test
    fun aManualPassReportsConnectionFailure() = runTest {
        val outbox = MemoryOutboxStore()
        val accounts = MemoryCloudAccountStore()
        val secrets = FakeSecrets()
        val http = FakeHttp()
        val clock = FakeClock()
        signedIn(accounts, secrets)
        accounts.saveBootstrapped(true)
        val access = FakeAccess(AppState(calendars = listOf(Calendar.local())))

        syncService(access, outbox, accounts, secrets, http, clock, pushService(access, outbox, accounts, secrets, http, clock), mutableListOf())
            .syncNow(notify = true)

        assertEquals(listOf<AppMessage>(AppMessage.CloudFailed), access.messages)
    }
}
