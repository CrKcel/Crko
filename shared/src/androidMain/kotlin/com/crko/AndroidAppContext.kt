package com.crko

import android.content.Context

/**
 * Application context captured from the Android entry point. The shared UI layer uses it for
 * preferences and configuration; it never holds an Activity reference.
 */
object AndroidAppContext {
    private var context: Context? = null

    fun install(context: Context) {
        this.context = context.applicationContext
    }

    fun get(): Context = checkNotNull(context) { "AndroidAppContext.install must run before the UI" }
}
