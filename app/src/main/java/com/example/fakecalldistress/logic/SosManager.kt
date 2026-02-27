package com.example.fakecalldistress.logic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.example.fakecalldistress.data.Contact
import com.example.fakecalldistress.data.SafetySettingsRepository
import com.example.fakecalldistress.service.EmergencyRecordService
import com.example.fakecalldistress.service.LiveLocationShareService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

class SosManager(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private val safetySettingsRepository = SafetySettingsRepository(context)
    private var voiceAuthenticator: VoiceAuthenticator? = null

    fun triggerSos(contacts: List<Contact>): String {
        try {
            if (contacts.isEmpty()) return "No contacts found"

            val sessionId = java.util.UUID.randomUUID().toString()
            val trackingUrl = "https://sheild-safety.web.app/track/$sessionId"

            // 1. Voice Authentication (Wait for safe-word to cancel)
            if (safetySettingsRepository.isVoiceAuthEnabled()) {
                startVoiceAuthentication(contacts)
            } else {
                dispatchSos(contacts, trackingUrl)
            }
            
            return "SOS Initiated"

        } catch (e: Exception) {
            Log.e("SosManager", "CRITICAL FAILURE in triggerSos", e)
            return "Failed to dispatch SOS: ${e.message}"
        }
    }

    private fun startVoiceAuthentication(contacts: List<Contact>) {
        voiceAuthenticator = VoiceAuthenticator(context, safetySettingsRepository) { success ->
            if (success) {
                Log.d("SosManager", "SOS CANCELLED BY SAFE-WORD")
                Toast.makeText(context, "SOS Cancelled by Safe-Word", Toast.LENGTH_LONG).show()
            } else {
                Log.w("SosManager", "Safe-word FAILED - Dispatching SOS now")
                val sessionId = java.util.UUID.randomUUID().toString()
                dispatchSos(contacts, "https://sheild-safety.web.app/track/$sessionId")
            }
        }
        voiceAuthenticator?.startListening()
        Toast.makeText(context, "Speak Safe-Word to cancel SOS...", Toast.LENGTH_LONG).show()
    }

    private fun dispatchSos(contacts: List<Contact>, trackingUrl: String) {
        // 1. Start Audio Recording (Critical Evidence)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startEmergencyRecordingService()
        }

        // 2. Start Live Location (If Firebase is available)
        try {
            val sessionId = trackingUrl.substringAfterLast("/")
            startLiveLocationShareService(sessionId)
        } catch (e: Exception) {
            Log.e("SosManager", "Firebase/LiveTracking failed - continuing with SMS", e)
        }

        getLastLocation { location ->
            val highRisk = isHighRiskSituation(location)
            val basePrefix = if (highRisk) "HIGH-RISK SOS! " else "SOS! "

            val message = if (location != null) {
                "${basePrefix}I need help. \nLive Tracking: $trackingUrl \nLocation: https://maps.google.com/?q=${location.latitude},${location.longitude}"
            } else {
                "${basePrefix}I need help. GPS unavailable. \nTrack me here: $trackingUrl"
            }
            
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                val smsManager = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                } catch (e: Exception) {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                for (contact in contacts) {
                    if (contact.phoneNumber.isNotBlank()) {
                        try {
                            // Senior Dev Fix: Use multi-part SMS to handle messages > 160 characters
                            val parts = smsManager.divideMessage(message)
                            smsManager.sendMultipartTextMessage(contact.phoneNumber, null, parts, null, null)
                            Log.d("SosManager", "CRITICAL: MULTIPART SMS SENT TO ${contact.phoneNumber}")
                        } catch (e: Exception) {
                            Log.e("SosManager", "Failed to send SMS to ${contact.phoneNumber}", e)
                        }
                    }
                }
            } else {
                Log.e("SosManager", "PERMISSION DENIED: Cannot send SMS")
            }
        }

        // 4. Multi-Channel SOS (Share to WhatsApp/Telegram etc.)
        if (safetySettingsRepository.isMultiChannelSosEnabled()) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "CRITICAL SOS ALERT! I need help. \nTrack me live: $trackingUrl")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "Share SOS to other apps").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(chooser)
            } catch (e: Exception) {
                Log.e("SosManager", "Failed to start multi-channel share")
            }
        }

        // 5. External Channels (Email / Share)
        if (!safetySettingsRepository.isSilentSosEnabled()) {
            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf("police@example.com"))
                putExtra(Intent.EXTRA_SUBJECT, "SOS ALERT! I NEED HELP")
                putExtra(Intent.EXTRA_TEXT, "I have triggered an SOS. \nTrack me live: $trackingUrl \n\nAudio evidence is recorded on device.")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(emailIntent)
            } catch (e: Exception) {
                Log.e("SosManager", "No email app found")
            }
        }

        // 6. Auto-Callback (Simulate incoming call from trusted contact after 30s)
        if (safetySettingsRepository.isAutoCallbackEnabled()) {
            Handler(Looper.getMainLooper()).postDelayed({
                val intent = Intent(context, com.example.fakecalldistress.ui.FakeCallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }, 30000) // 30 seconds delay
        }
    }

    fun stopAllServices() {
        context.stopService(Intent(context, EmergencyRecordService::class.java))
        context.stopService(Intent(context, LiveLocationShareService::class.java))
        voiceAuthenticator?.stop()
    }

    private fun startEmergencyRecordingService() {
        val intent = Intent(context, EmergencyRecordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Log.d("SosManager", "Signaled EmergencyRecordService to start.")
    }

    private fun isHighRiskSituation(location: Location?): Boolean {
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val isNight = hour >= 22 || hour < 6

        val unsafeLat = safetySettingsRepository.getUnsafeZoneLat()
        val unsafeLon = safetySettingsRepository.getUnsafeZoneLon()

        if (location != null && unsafeLat != 0.0 && unsafeLon != 0.0) {
            val result = FloatArray(1)
            Location.distanceBetween(
                location.latitude,
                location.longitude,
                unsafeLat,
                unsafeLon,
                result
            )
            val distance = result[0]
            if (distance <= 200) { // 200 meters threshold
                return true
            }
        }

        return isNight
    }

    private fun startLiveLocationShareService(sessionId: String) {
        val intent = Intent(context, LiveLocationShareService::class.java).apply {
            putExtra("SESSION_ID", sessionId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Log.d("SosManager", "Signaled LiveLocationShareService to start session: $sessionId")
    }

    private fun getLastLocation(onLocation: (Location?) -> Unit) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && 
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            onLocation(null)
            return
        }
        
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            onLocation(location)
        }.addOnFailureListener {
            onLocation(null)
        }
    }
}
