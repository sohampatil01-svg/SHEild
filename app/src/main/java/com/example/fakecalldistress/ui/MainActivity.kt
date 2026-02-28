package com.example.fakecalldistress.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.fakecalldistress.R
import com.example.fakecalldistress.data.Contact
import com.example.fakecalldistress.data.ContactRepository
import com.example.fakecalldistress.data.SafetySettingsRepository
import com.example.fakecalldistress.databinding.ActivityMainBinding
import com.example.fakecalldistress.logic.ShakeDetector
import com.example.fakecalldistress.logic.SosManager
import com.example.fakecalldistress.service.JourneyService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.FusedLocationProviderClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var contactRepository: ContactRepository
    private lateinit var safetySettingsRepository: SafetySettingsRepository
    private lateinit var sosManager: SosManager
    private lateinit var sensorManager: SensorManager
    private lateinit var shakeDetector: ShakeDetector
    private lateinit var fallDetector: com.example.fakecalldistress.logic.FallDetector
    private lateinit var audioTrigger: com.example.fakecalldistress.logic.AudioTrigger
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sirenManager: com.example.fakecalldistress.logic.SirenManager
    
    private var isShakeEnabled = false
    private var isFallDetectionEnabled = false
    private var isProgrammaticSwitchChange = false
    private var isTracking = false

    private val handler = Handler(Looper.getMainLooper())
    private val trackingRunnable = object : Runnable {
        override fun run() {
            if (isTracking) {
                updateTrackingLog()
                handler.postDelayed(this, 5000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactRepository = ContactRepository(this)
        safetySettingsRepository = SafetySettingsRepository(this)
        sosManager = SosManager(this)
        sirenManager = com.example.fakecalldistress.logic.SirenManager(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupShakeDetector()
        setupFallDetector()
        setupUI()
        setupBottomNav()
        startPulseAnimation()
        
        checkPermissions()
        checkAndAutoArmSensors()
    }

    private fun startPulseAnimation() {
        val pulse = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse)
        binding.cardSos.startAnimation(pulse)
    }

    private fun setupShakeDetector() {
        shakeDetector = ShakeDetector {
            if (isShakeEnabled) {
                runOnUiThread { startFakeCall() }
            }
        }
    }

    private fun setupFallDetector() {
        fallDetector = com.example.fakecalldistress.logic.FallDetector {
            if (isFallDetectionEnabled) {
                runOnUiThread {
                    Toast.makeText(this, "Fall/Impact detected! Triggering SOS...", Toast.LENGTH_LONG).show()
                    triggerSos()
                }
            }
        }
    }

    private fun checkAndAutoArmSensors() {
        if (!safetySettingsRepository.isAutoNightModeEnabled()) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour >= 22 || hour < 6) {
            isProgrammaticSwitchChange = true
            binding.switchShake.isChecked = true
            isShakeEnabled = true
            startShakeDetection()
            
            if (checkPermissions()) {
                binding.switchScream.isChecked = true
                audioTrigger.startListening()
            }
            isProgrammaticSwitchChange = false
            Toast.makeText(this, "Night Mode: Safety Sensors Auto-Armed", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupUI() {
        // Load Settings
        val contacts = contactRepository.getContacts()
        if (contacts.isNotEmpty()) {
            val contact = contacts[0]
            binding.etContactName.setText(contact.name)
            binding.etContactNumber.setText(contact.phoneNumber)
            binding.tvSavedContact.text = "Saved: ${contact.name}"
        }

        binding.etFakeName.setText(contactRepository.getCallerInfo().name)

        // Skin Preference
        val settingsPrefs = getSharedPreferences("settings", MODE_PRIVATE)
        val currentSkin = settingsPrefs.getString("call_skin", "android")
        if (currentSkin == "ios") binding.toggleSkin.check(R.id.btnIosSkin) 
        else binding.toggleSkin.check(R.id.btnAndroidSkin)

        binding.toggleSkin.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val skin = if (checkedId == R.id.btnIosSkin) "ios" else "android"
                settingsPrefs.edit().putString("call_skin", skin).apply()
            }
        }

        // Safety Switches
        binding.switchSilentSos.isChecked = safetySettingsRepository.isSilentSosEnabled()
        binding.switchMultiChannelSos.isChecked = safetySettingsRepository.isMultiChannelSosEnabled()
        binding.switchAutoCallback.isChecked = safetySettingsRepository.isAutoCallbackEnabled()
        binding.switchNightMode.isChecked = safetySettingsRepository.isAutoNightModeEnabled()
        isFallDetectionEnabled = safetySettingsRepository.isFallDetectionEnabled()
        binding.switchFallDetection.isChecked = isFallDetectionEnabled
        if (isFallDetectionEnabled) startFallDetection()
        binding.switchInactivityCheck.isChecked = safetySettingsRepository.isInactivityCheckEnabled()
        binding.switchSafeArrival.isChecked = safetySettingsRepository.isSafeArrivalEnabled()
        binding.switchVoiceAuth.isChecked = safetySettingsRepository.isVoiceAuthEnabled()
        binding.etSafeWord.setText(safetySettingsRepository.getSecretSafeWord())

        // Listeners
        binding.switchSilentSos.setOnCheckedChangeListener { _, isChecked -> safetySettingsRepository.setSilentSosEnabled(isChecked) }
        binding.switchMultiChannelSos.setOnCheckedChangeListener { _, isChecked -> safetySettingsRepository.setMultiChannelSosEnabled(isChecked) }
        binding.switchAutoCallback.setOnCheckedChangeListener { _, isChecked -> safetySettingsRepository.setAutoCallbackEnabled(isChecked) }
        binding.switchNightMode.setOnCheckedChangeListener { _, isChecked -> safetySettingsRepository.setAutoNightModeEnabled(isChecked) }
        binding.switchFallDetection.setOnCheckedChangeListener { _, isChecked -> 
            safetySettingsRepository.setFallDetectionEnabled(isChecked)
            isFallDetectionEnabled = isChecked
            if (isChecked) startFallDetection() else stopFallDetection()
        }
        binding.switchInactivityCheck.setOnCheckedChangeListener { _, isChecked -> safetySettingsRepository.setInactivityCheckEnabled(isChecked) }
        binding.switchSafeArrival.setOnCheckedChangeListener { _, isChecked -> safetySettingsRepository.setSafeArrivalEnabled(isChecked) }
        binding.switchVoiceAuth.setOnCheckedChangeListener { _, isChecked -> 
            safetySettingsRepository.setVoiceAuthEnabled(isChecked)
            if (isChecked) {
                Toast.makeText(this, "Voice Authentication enabled. Set your safe word below.", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Save safe word when text changes (with debounce)
        binding.etSafeWord.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val word = s?.toString()?.trim() ?: ""
                if (word.length >= 3) {
                    safetySettingsRepository.setSecretSafeWord(word)
                }
            }
        })
        
        // Load and setup SOS trigger word
        binding.etSosTriggerWord.setText(safetySettingsRepository.getSosTriggerWord())
        binding.etSosTriggerWord.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val word = s?.toString()?.trim() ?: ""
                if (word.length >= 3) {
                    safetySettingsRepository.setSosTriggerWord(word)
                } else if (word.isEmpty()) {
                    safetySettingsRepository.setSosTriggerWord("")
                }
            }
        })

        binding.btnSaveContact.setOnClickListener {
            val name = binding.etContactName.text.toString()
            val number = binding.etContactNumber.text.toString()
            if (name.isNotEmpty() && number.isNotEmpty()) {
                contactRepository.saveContacts(listOf(Contact(name, number)))
                binding.tvSavedContact.text = "Saved: $name"
                Toast.makeText(this, "Contact Saved", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSaveCaller.setOnClickListener {
            val name = binding.etFakeName.text.toString()
            if (name.isNotEmpty()) {
                contactRepository.saveCallerInfo(com.example.fakecalldistress.data.CallerInfo(name, "Mobile"))
                Toast.makeText(this, "Caller ID Updated", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnScheduleCall.setOnClickListener { showTimePicker() }
        binding.btnCancelSchedule.setOnClickListener { cancelScheduledCall() }

        binding.switchShake.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticSwitchChange) return@setOnCheckedChangeListener
            isShakeEnabled = isChecked
            if (isChecked) startShakeDetection() else stopShakeDetection()
        }

        audioTrigger = com.example.fakecalldistress.logic.AudioTrigger(this) {
            runOnUiThread {
                Toast.makeText(this, "AI Threat Detected! Triggering SOS...", Toast.LENGTH_LONG).show()
                triggerSos()
                binding.switchScream.isChecked = false
            }
        }

        binding.switchScream.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticSwitchChange) return@setOnCheckedChangeListener
            if (isChecked && checkPermissions()) audioTrigger.startListening() else audioTrigger.stopListening()
        }

        binding.btnMarkUnsafeZone.setOnClickListener { markCurrentLocationUnsafe() }
        binding.btnClearUnsafeZone.setOnClickListener { 
            safetySettingsRepository.clearUnsafeZone()
            Toast.makeText(this, "Unsafe zone cleared", Toast.LENGTH_SHORT).show()
        }

        binding.cardFakeCall.setOnClickListener { startFakeCall() }
        binding.cardJourney.setOnClickListener { toggleJourneyTracking() }

        binding.cardSiren.setOnClickListener {
            val isRunning = sirenManager.toggleSiren()
            if (isRunning) {
                binding.cardSiren.setCardBackgroundColor(getColor(R.color.m3_crimson_sos))
                binding.ivSiren.setColorFilter(getColor(R.color.white))
                Toast.makeText(this, "Panic Siren ACTIVE", Toast.LENGTH_SHORT).show()
            } else {
                binding.cardSiren.setCardBackgroundColor(getColor(R.color.white))
                binding.ivSiren.setColorFilter(getColor(R.color.m3_navy_primary))
            }
        }

        binding.cardMap.setOnClickListener {
            val gmmIntentUri = Uri.parse("geo:0,0?q=police+station")
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
            mapIntent.setPackage("com.google.android.apps.maps")
            startActivity(mapIntent)
        }

        binding.cardSos.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    if (checkPermissions()) binding.cardSos.setCardBackgroundColor(getColor(R.color.m3_crimson_sos))
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    binding.cardSos.setCardBackgroundColor(getColor(R.color.m3_crimson_sos))
                    triggerSos()
                    true
                }
                else -> true
            }
        }

        binding.btnPostIncident.setOnClickListener {
            val text = binding.etIncident.text.toString()
            if (text.isNotEmpty()) {
                saveIncident(text)
                binding.etIncident.text?.clear()
                loadIncidents()
            }
        }
    }

    private fun markCurrentLocationUnsafe() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 102)
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                safetySettingsRepository.saveUnsafeZone(location.latitude, location.longitude)
                Toast.makeText(this, "Unsafe zone marked", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerSos() {
        val contacts = contactRepository.getContacts()
        if (contacts.isEmpty()) {
            Toast.makeText(this, "Save a contact first!", Toast.LENGTH_LONG).show()
            return
        }
        sosManager.triggerSos(contacts)
        if (!safetySettingsRepository.isSilentSosEnabled()) {
            Toast.makeText(this, "SOS SENT!", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleJourneyTracking() {
        val intent = Intent(this, JourneyService::class.java)
        if (!isTracking) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            isTracking = true
            binding.tvJourneyStatus.text = "Stop"
            handler.post(trackingRunnable)
        } else {
            stopService(intent)
            isTracking = false
            binding.tvJourneyStatus.text = "Journey"
            handler.removeCallbacks(trackingRunnable)
        }
    }

    private fun updateTrackingLog() {
        // UI placeholder update if needed
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showView(binding.viewHome)
                R.id.nav_history -> { showView(binding.viewCommunity); loadIncidents() }
                R.id.nav_settings -> showView(binding.viewSettings)
                R.id.nav_help -> showView(binding.viewInfo)
            }
            true
        }
    }

    private fun showView(view: View) {
        binding.viewHome.visibility = View.GONE
        binding.viewCommunity.visibility = View.GONE
        binding.viewSettings.visibility = View.GONE
        binding.viewInfo.visibility = View.GONE
        view.visibility = View.VISIBLE
    }

    private fun saveIncident(text: String) {
        val prefs = getSharedPreferences("incidents", MODE_PRIVATE)
        val current = prefs.getStringSet("logs", mutableSetOf()) ?: mutableSetOf()
        current.add("${SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())}\n$text")
        prefs.edit().putStringSet("logs", current).apply()
    }

    private fun loadIncidents() {
        val prefs = getSharedPreferences("incidents", MODE_PRIVATE)
        val logs = prefs.getStringSet("logs", emptySet())?.toList()?.sortedDescending() ?: emptyList()
        
        val container = findViewById<android.widget.LinearLayout>(R.id.llTimelineContainer)
        if (container == null) return
        container.removeAllViews()

        if (logs.isEmpty()) {
            val emptyText = TextView(this)
            emptyText.text = "No safety logs yet."
            emptyText.gravity = android.view.Gravity.CENTER
            emptyText.setPadding(0, 100, 0, 0)
            emptyText.setTextColor(getColor(R.color.text_grey))
            container.addView(emptyText)
        } else {
            for ((index, log) in logs.withIndex()) {
                val itemView = layoutInflater.inflate(R.layout.item_timeline_log, container, false)
                val tvTime = itemView.findViewById<TextView>(R.id.tvLogTimestamp)
                val tvContent = itemView.findViewById<TextView>(R.id.tvLogContent)
                val topLine = itemView.findViewById<View>(R.id.viewLineTop)
                val bottomLine = itemView.findViewById<View>(R.id.viewLineBottom)

                // Timeline visual logic
                if (index == 0) {
                    topLine.visibility = View.INVISIBLE
                }
                if (index == logs.size - 1) {
                    bottomLine.visibility = View.INVISIBLE
                }

                // Split timestamp from content (format is "dd/MM HH:mm\nContent")
                val parts = log.split("\n", limit = 2)
                if (parts.size == 2) {
                    tvTime.text = parts[0]
                    tvContent.text = parts[1]
                } else {
                    tvContent.text = log
                }

                container.addView(itemView)
            }
        }
    }

    private fun startFakeCall() { startActivity(Intent(this, FakeCallActivity::class.java)) }

    private fun showTimePicker() {
        val c = Calendar.getInstance()
        android.app.TimePickerDialog(this, { _, h, m -> scheduleFakeCall(h, m) }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
    }

    private fun scheduleFakeCall(h: Int, m: Int) {
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0) }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        val pi = android.app.PendingIntent.getBroadcast(this, 0, Intent(this, com.example.fakecalldistress.logic.CallReceiver::class.java), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        getSystemService(android.app.AlarmManager::class.java).setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        Toast.makeText(this, "Scheduled for ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(cal.time)}", Toast.LENGTH_SHORT).show()
    }

    private fun cancelScheduledCall() {
        val pi = android.app.PendingIntent.getBroadcast(this, 0, Intent(this, com.example.fakecalldistress.logic.CallReceiver::class.java), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        getSystemService(android.app.AlarmManager::class.java).cancel(pi)
    }

    private fun startShakeDetection() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(shakeDetector, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopShakeDetection() { sensorManager.unregisterListener(shakeDetector) }

    private fun startFallDetection() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(fallDetector, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopFallDetection() { sensorManager.unregisterListener(fallDetector) }

    private fun checkPermissions(): Boolean {
        val perms = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE)
        val missing = perms.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
            return false
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        stopShakeDetection()
        stopFallDetection()
        if (this::audioTrigger.isInitialized) audioTrigger.stopListening()
        handler.removeCallbacks(trackingRunnable)
    }
}
