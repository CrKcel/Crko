package com.crko.storage


import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIWindowScene
import platform.UIKit.UIApplication

/** Pure-Swift hosts on iOS cannot supply wallpaper colors, so the default seed palette is used. */
actual fun platformSupportsMonet(): Boolean = false

private class UserDefaultsStore(private val suite: String?) : KeyValueStore {
    private val defaults: NSUserDefaults
        get() = suite?.let { NSUserDefaults(suiteName = it) } ?: NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }
}

actual fun appStore(): KeyValueStore = UserDefaultsStore(null)

actual fun settingsStore(): KeyValueStore = UserDefaultsStore("crko.settings")

actual fun newId(): String = NSUUID().UUIDString()

actual fun systemLocaleTag(): String = NSLocale.currentLocale.languageCode

actual fun systemDarkMode(): Boolean {
    val style = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstNotNullOfOrNull { it.keyWindow() }
        ?.traitCollection
        ?.userInterfaceStyle
    return style == UIUserInterfaceStyle.UIUserInterfaceStyleDark
}
