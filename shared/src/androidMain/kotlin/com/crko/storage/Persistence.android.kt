package com.crko.storage

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import com.crko.AndroidAppContext
import com.crko.storage.db.CrkoDatabase
import java.io.File

/**
 * The Android driver lives in the app-private `databases/` directory and is created only when the
 * application context is installed. Receivers call [AndroidAppContext.install] first.
 */
actual fun persistenceDriver(): SqlDriver? {
    val context = AndroidAppContext.getOrNull() ?: return null
    return AndroidSqliteDriver(CrkoDatabase.Schema, context, "crko.db")
}

private class FilePersistenceBackup(private val file: File) : PersistenceBackup {
    override fun readPreSqlite(): String? =
        runCatching { if (file.isFile) file.readText() else null }.getOrNull()

    override fun writePreSqlite(raw: String) {
        file.parentFile?.mkdirs()
        // A temporary sibling plus rename keeps a crash from leaving a truncated backup; the JSON
        // source is never deleted, so a failed backup only means "migration aborted".
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(raw)
        if (file.exists()) file.delete()
        check(temporary.renameTo(file)) { "could not install ${file.name}" }
    }

    override fun quarantineDatabase(): String? {
        val db = File(file.parentFile, "crko.db")
        if (!db.exists()) return null
        val target = File(file.parentFile, "crko.db.corrupt-${System.currentTimeMillis()}")
        return runCatching { if (db.renameTo(target)) target.path else null }.getOrNull()
    }
}

actual fun persistenceBackup(): PersistenceBackup {
    val context: Context = AndroidAppContext.getOrNull() ?: return InMemoryPersistenceBackup
    return FilePersistenceBackup(File(context.filesDir, "state.v1.pre-sqlite"))
}
