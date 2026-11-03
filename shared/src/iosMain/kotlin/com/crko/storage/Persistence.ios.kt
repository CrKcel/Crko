@file:OptIn(ExperimentalForeignApi::class)

package com.crko.storage

import kotlinx.cinterop.ExperimentalForeignApi

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.crko.storage.db.CrkoDatabase
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * The iOS database lives in `Application Support/Crko/crko.db`. The default file protection
 * (first unlock) matches the Android backup rules and keeps background reminder work readable.
 */
actual fun persistenceDriver(): SqlDriver? {
    val directory = applicationSupportDirectory() ?: return null
    val manager = NSFileManager.defaultManager
    if (!manager.fileExistsAtPath(directory)) {
        manager.createDirectoryAtPath(
            directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }
    if (!manager.fileExistsAtPath(directory)) return null
    return NativeSqliteDriver(CrkoDatabase.Schema, "$directory/crko.db")
}

private fun applicationSupportDirectory(): String? {
    val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: return null
    return "$base/Crko"
}
