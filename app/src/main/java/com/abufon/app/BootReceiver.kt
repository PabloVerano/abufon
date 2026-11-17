package com.abufon.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // AbufonApp.onCreate has already started the core; make sure the service is running.
        AbufonService.start(context)
    }
}
