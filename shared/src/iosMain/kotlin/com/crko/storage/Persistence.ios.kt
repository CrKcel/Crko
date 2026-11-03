@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.crko.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.crko.storage.db.CrkoDatabase
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.getBytes
import platform.Foundation.writeToFile

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

private class IosPersistenceBackup(private val path: String) : PersistenceBackup {
    override fun readPreSqlite(): String? {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        return runCatching {
            NSData.dataWithContentsOfFile(path)?.toByteArray()?.decodeToString()
        }.getOrNull()
    }

    override fun writePreSqlite(raw: String) {
        path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { directory ->
            NSFileManager.defaultManager.createDirectoryAtPath(
                directory,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }
        val bytes = raw.encodeToByteArray()
        val data = if (bytes.isEmpty()) {
            NSData.create(bytes = null, length = 0u)
        } else {
            bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()) }
        }
        check(data.writeToFile(path, atomically = true)) { "could not write $path" }
    }

    override fun quarantineDatabase(): String? {
        val manager = NSFileManager.defaultManager
        val dbPath = "${path.substringBeforeLast('/')}/crko.db"
        if (!manager.fileExistsAtPath(dbPath)) return null
        val target = "$dbPath.corrupt-${platform.Foundation.NSUUID().UUIDString}"
        return if (manager.moveItemAtPath(dbPath, target, error = null)) target else null
    }
}

actual fun persistenceBackup(): PersistenceBackup {
    val directory = applicationSupportDirectory() ?: return InMemoryPersistenceBackup
    return IosPersistenceBackup("$directory/state.v1.pre-sqlite")
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { pinned -> getBytes(pinned.addressOf(0), length = length) }
    return bytes
}
