package com.crko.storage

import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import com.crko.AndroidAppContext
import com.crko.storage.db.CrkoDatabase

/**
 * The Android driver lives in the app-private `databases/` directory and is created only when the
 * application context is installed. Receivers call [AndroidAppContext.install] first.
 */
actual fun persistenceDriver(): SqlDriver? {
    val context = AndroidAppContext.getOrNull() ?: return null
    return AndroidSqliteDriver(CrkoDatabase.Schema, context, "crko.db")
}
