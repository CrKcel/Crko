import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("CrkoDatabase") {
            packageName.set("com.crko.storage.db")
            // Payload columns stay the source of truth; the schema itself only moves through .sqm
            // files once the first version ships.
            generateAsync.set(false)
        }
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    android {
       namespace = "com.crko.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.sqldelight.android.driver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        named("androidHostTest") {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        commonMain.dependencies {
            implementation(libs.sqldelight.runtime)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.miuix.ui)
            implementation(libs.miuix.squircle)
            implementation(libs.compose.material3)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kyant.backdrop)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

/**
 * Source-level guard for the RFC 5545 domain model. Legacy field
 * names that double as legitimate locals (`endMinutes = when { … }`) are matched in
 * property-declaration form (`val|var name:` , including `val Event.name:` compat getters).
 */
val verifyDomainModel = tasks.register("verifyDomainModel") {
    group = "verification"
    description = "Rejects legacy Event fields, synthetic UIDs and device-zone use in ICS and iTIP I/O."
    val commonMain = layout.projectDirectory.dir("src/commonMain/kotlin/com/crko")
    inputs.dir(commonMain.asFile)

    doLast {
        val root = commonMain.asFile
        val violations = mutableListOf<String>()
        fun File.lines(): List<String> = readText().lines()
        fun report(file: File, line: Int, message: String) {
            violations += "${file.relativeTo(root).path}:${line + 1}: $message"
        }

        val models = root.resolve("domain/Models.kt")
        val modelsText = models.readText()
        val legacyEventFields = listOf(
            "allDay", "startMinutes", "endMinutes", "startSeconds", "endSeconds", "endDate",
            "sourceStart", "sourceEnd", "timeZoneId", "timeZoneDefinition",
        )
        legacyEventFields.forEach { field ->
            if (Regex("""\bval\s+$field\b""").containsMatchIn(modelsText)) {
                violations += "domain/Models.kt: legacy Event field '$field' is back"
            }
        }
        if (Regex("""\bconstructor\s*\(""").containsMatchIn(modelsText)) {
            violations += "domain/Models.kt: a secondary constructor is back; use an RFC time value"
        }
        val repeatRule = modelsText
            .substringAfter("data class RepeatRule(")
            .substringBefore("data class RecurrenceDate(")
        listOf("weekdays", "rDates").forEach { field ->
            if (Regex("""\b$field\b""").containsMatchIn(repeatRule)) {
                violations += "domain/Models.kt: RepeatRule.$field is back; use Recurrence"
            }
        }
        val eventWindow = modelsText
            .substringAfter("data class Event(")
            .substringBefore("data class Todo(")
        if (Regex("""\bval\s+(?:\w+\.)?date\b""").containsMatchIn(eventWindow)) {
            violations += "domain/Models.kt: Event.date is back; derive the day from the IcsTimeValue"
        }

        // The same names must not reappear as stored/derived properties anywhere in the data layer,
        // not just in Models.kt; declaration shape (`val name: Type` / `val Event.name: Type`) keeps
        // identically-named locals out of the way.
        val legacyFieldNames = legacyEventFields + listOf("startTime", "dueTime", "completedTime")
        listOf(root.resolve("domain"), root.resolve("state")).forEach { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.lines().forEachIndexed { index, line ->
                        val code = line.trim()
                        val comment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                        if (comment) return@forEachIndexed
                        legacyFieldNames.forEach { field ->
                            if (Regex("""\b(val|var)\s+(?:[\w.]+\.)?$field\s*:""").containsMatchIn(line)) {
                                report(file, index, "legacy field '$field' is back in the data layer")
                            }
                        }
                    }
                }
        }

        val syntheticMarkers = listOf("#override-", "#future-", "#prior-")
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.lines().forEachIndexed { index, line ->
                    val code = line.trim()
                    val comment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                    if (!comment) {
                        syntheticMarkers.firstOrNull { it in line }?.let { marker ->
                            report(file, index, "synthetic UID marker '$marker' is back in the data layer")
                        }
                        if (line.contains("\"sub:\"") || line.contains("subscriptionSource")) {
                            report(file, index, "importSource must not act as calendar identity in the data layer")
                        }
                    }
                }
            }

        // The ICS reader/writer was split into several `Ics*.kt` files (C1); keep every share of the
        // import/export path under the same rule instead of naming one file.
        root.resolve("domain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && (it.name.startsWith("Ics") || it.name == "Itip.kt") }
            .sortedBy { it.path }
            .forEach { file ->
                file.lines().forEachIndexed { index, line ->
                    if ("TimeZone.currentSystemDefault()" in line) {
                        report(file, index, "device zone must not be used on the import/export path")
                    }
                }
            }

        if (violations.isNotEmpty()) {
            error("Domain model regression guard failed:\n" + violations.joinToString("\n"))
        }
    }
}

/**
 * Package-level dependency guard. Each package may only import the packages listed as allowed in
 * the README "结构" section; the guard scans every Kotlin file under the shared source sets, so
 * platform-specific code cannot slip across the same boundaries. `knownViolations` stays empty:
 * the package split is complete, so new violations must be fixed in code instead of exempted.
 */
val verifyArchitecture = tasks.register("verifyArchitecture") {
    group = "verification"
    description = "Rejects imports that cross the allowed package boundaries."
    val sourceRoots = listOf(
        layout.projectDirectory.dir("src/commonMain/kotlin/com/crko"),
        layout.projectDirectory.dir("src/androidMain/kotlin/com/crko"),
        layout.projectDirectory.dir("src/iosMain/kotlin/com/crko"),
    )
    inputs.files(sourceRoots.map { it.asFile })

    doLast {
        val forbiddenImports = mapOf(
            "domain" to listOf("storage", "sync", "state", "ui", "i18n", "dev"),
            "storage" to listOf("sync", "state", "ui", "i18n", "dev"),
            "sync" to listOf("state", "ui", "i18n", "dev"),
            "i18n" to listOf("state", "ui", "dev"),
            "state" to listOf("ui", "dev"),
            "ui" to listOf("storage", "sync", "dev"),
        )
        // Keep the allowlist keyed by file and forbidden package, so a new boundary crossing in an
        // already-known file still fails the guard.
        val knownViolations = setOf<String>()
        val violations = mutableListOf<String>()
        sourceRoots.forEach rootLoop@ { root ->
            val rootFile = root.asFile
            if (!rootFile.exists()) return@rootLoop
            rootFile.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.path }
                .forEach fileLoop@ { file ->
                    val relative = file.relativeTo(rootFile).path
                    val packageName = relative.substringBefore('/')
                    val denied = forbiddenImports[packageName] ?: return@fileLoop
                    file.readLines().forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("import ")) return@forEachIndexed
                        val imported = trimmed.removePrefix("import ").substringBefore(" as ").trim()
                        val hit = denied.firstOrNull { imported.startsWith("com.crko.$it.") }
                            ?: return@forEachIndexed
                        if ("$relative|$hit" in knownViolations) return@forEachIndexed
                        violations += "$relative:${index + 1}: $packageName must not import $imported"
                    }
                }
        }
        if (violations.isNotEmpty()) {
            error("Architecture guard failed:\n" + violations.joinToString("\n"))
        }
    }
}

/**
 * File-size guard for `commonMain`. Every file is held to the 800-line budget; the allowlist is
 * empty after the B1–B5, C1–C4 and D1–D2 splits landed.
 */
val verifyFileSizes = tasks.register("verifyFileSizes") {
    group = "verification"
    description = "Keeps commonMain Kotlin files under the 800-line budget."
    val commonMain = layout.projectDirectory.dir("src/commonMain/kotlin/com/crko")
    inputs.dir(commonMain.asFile)

    doLast {
        val maxLines = 800
        val knownOversize = setOf<String>()
        val violations = mutableListOf<String>()
        commonMain.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .forEach fileLoop@ { file ->
                val relative = file.relativeTo(commonMain.asFile).path
                if (relative in knownOversize) return@fileLoop
                val lines = file.readLines().size
                if (lines > maxLines) {
                    violations += "$relative: $lines lines exceeds the $maxLines-line budget"
                }
            }
        if (violations.isNotEmpty()) {
            error("File-size guard failed:\n" + violations.joinToString("\n"))
        }
    }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(verifyDomainModel, verifyArchitecture, verifyFileSizes)
}

/**
 * F2.1: `./gradlew :shared:testAndroidHostTest -PperfBaseline` runs the deterministic persistence
 * baseline only. Without the property the class returns immediately, so normal test runs stay fast.
 * The JVM default zone is pinned to UTC because `Event.day` and reminder planning project through it.
 */
val perfBaselineRequested = providers.gradleProperty("perfBaseline").isPresent
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (perfBaselineRequested) {
        systemProperty("crko.perfBaseline", "true")
        systemProperty("user.timezone", "UTC")
        filter {
            includeTestsMatching("com.crko.perf.PerfBaselineTest")
        }
        testLogging {
            showStandardStreams = true
            events("passed", "failed")
        }
        // A fresh baseline run should never be served from Gradle's up-to-date check.
        outputs.upToDateWhen { false }
    }
}
