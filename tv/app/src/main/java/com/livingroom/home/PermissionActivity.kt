package com.livingroom.home

import android.Manifest
import android.app.Activity
import android.os.Bundle

/** Asks Android once for Bluetooth access (for the phone's Bluetooth page), then closes. */
class PermissionActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (android.os.Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1) else finish()
    }
    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) { finish() }
}
