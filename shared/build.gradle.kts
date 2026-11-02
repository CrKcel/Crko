import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
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
            implementation(libs.compose.uiTooling)
        }
        commonMain.dependencies {
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

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

/**
 * Source-level guard for the RFC 5545 domain model. The parser's private `Raw*` types and the
 * persisted-payload DTOs in `Legacy*.kt` are allowed to mention the old shape; nothing else is.
 */
val verifyDomainModel = tasks.register("verifyDomainModel") {
    group = "verification"
    description = "Rejects legacy Event fields, synthetic UIDs and device-zone use in ICS I/O."
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

        val syntheticMarkers = listOf("#override-", "#future-", "#prior-")
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.name.startsWith("Legacy") }
            .forEach { file ->
                file.lines().forEachIndexed { index, line ->
                    val code = line.trim()
                    val comment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                    if (!comment) {
                        syntheticMarkers.firstOrNull { it in line }?.let { marker ->
                            report(file, index, "synthetic UID marker '$marker' outside migration code")
                        }
                    }
                }
            }

        val ics = root.resolve("domain/Ics.kt")
        ics.lines().forEachIndexed { index, line ->
            if ("TimeZone.currentSystemDefault()" in line) {
                report(ics, index, "device zone must not be used on the ICS import/export path")
            }
        }

        if (violations.isNotEmpty()) {
            error("Domain model regression guard failed:\n" + violations.joinToString("\n"))
        }
    }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(verifyDomainModel)
}