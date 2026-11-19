package com.livingroom.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Runs right after the app updated itself: opens the home screen again. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try { c.startActivity(Intent(c, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    }
}
