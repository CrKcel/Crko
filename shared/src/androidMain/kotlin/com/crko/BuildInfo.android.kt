package com.crko

import android.content.pm.ApplicationInfo

actual fun isDebugBuild(): Boolean =
    (AndroidAppContext.get().applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
