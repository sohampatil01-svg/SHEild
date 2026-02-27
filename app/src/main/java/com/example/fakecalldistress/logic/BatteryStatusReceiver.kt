package com.example.fakecalldistress.logic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.fakecalldistress.data.ContactRepository

class BatteryStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BATTERY_LOW) {
            Log.w("BatteryReceiver", "Low Battery Detected! Triggering last location broadcast...")
            // Senior Dev Note: In a real app, we'd trigger a specialized 'Low Power SOS' 
            // that doesn't start video/audio to save the last bit of juice.
            val repository = ContactRepository(context)
            val sosManager = SosManager(context)
            val contacts = repository.getContacts()
            if (contacts.isNotEmpty()) {
                sosManager.triggerSos(contacts)
            }
        }
    }
}
