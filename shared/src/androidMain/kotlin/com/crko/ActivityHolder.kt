package com.crko

import android.app.Activity
import java.lang.ref.WeakReference

/** Current foreground activity, surfaced to shared code that needs the host window. */
object ActivityHolder {
    private var reference: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        reference = WeakReference(activity)
    }

    fun current(): Activity? = reference?.get()
}
