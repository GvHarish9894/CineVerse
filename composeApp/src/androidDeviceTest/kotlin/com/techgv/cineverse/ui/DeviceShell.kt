package com.techgv.cineverse.ui

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** Runs an adb-shell-level command on the test device and waits for its output (settings get/put/delete). */
fun deviceShell(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString().trim() }
}

/** Sets a device setting for [block] and restores the previous value (or its absence) afterwards. */
fun <T> withDeviceSetting(namespace: String, key: String, value: String, block: () -> T): T {
    val original = deviceShell("settings get $namespace $key")
    deviceShell("settings put $namespace $key $value")
    try {
        return block()
    } finally {
        deviceShell(if (original == "null") "settings delete $namespace $key" else "settings put $namespace $key $original")
    }
}
