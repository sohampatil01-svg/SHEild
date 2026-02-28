package com.example.fakecalldistress.ui

import android.media.Ringtone
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fakecalldistress.data.ContactRepository
import com.example.fakecalldistress.data.SafetySettingsRepository
import com.example.fakecalldistress.logic.SosManager
import com.example.fakecalldistress.databinding.ActivityFakeCallBinding
import java.text.SimpleDateFormat
import java.util.*

class FakeCallActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "FakeCallActivity"
        private const val LISTEN_RESTART_DELAY = 1500L
        private const val MIN_SPEECH_INTERVAL = 2000L
    }

    private lateinit var binding: ActivityFakeCallBinding
    private var ringtone: Ringtone? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isCallAnswered = false
    private var isCallActive = false
    private var pendingTtsScript: String? = null
    private lateinit var sosManager: SosManager
    private lateinit var contactRepository: ContactRepository
    private lateinit var safetySettingsRepository: SafetySettingsRepository
    private lateinit var audioManager: AudioManager
    
    // Speech Recognition State
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningEnabled = false
    private var isCurrentlyListening = false
    private var isTtsSpeaking = false
    private var lastProcessedTime = 0L
    private var recognitionRetryCount = 0
    private val maxRetries = 3
    
    // SOS Trigger Word
    private var sosTriggerWord: String = ""
    
    private var selectedSkin = "android" // default
    
    // Call UI State
    private var isMuted = false
    private var isSpeakerOn = false
    
    // Timer to track call duration visually
    private var callSeconds = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isCallActive) return
            callSeconds++
            val minutes = callSeconds / 60
            val seconds = callSeconds % 60
            val timeStr = String.format("%02d:%02d", minutes, seconds)
            if (selectedSkin == "android") binding.tvAndroidStatus.text = timeStr
            else binding.tvIosStatus.text = timeStr
            mainHandler.postDelayed(this, 1000)
        }
    }

    // Dead Man's Switch (Triggers SOS if not answered/declined in 30s)
    private var deadManTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFakeCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactRepository = ContactRepository(this)
        safetySettingsRepository = SafetySettingsRepository(this)
        sosManager = SosManager(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Load SOS trigger word from settings
        sosTriggerWord = safetySettingsRepository.getSosTriggerWord().lowercase(Locale.getDefault())
        Log.d(TAG, "SOS Trigger Word loaded: '$sosTriggerWord'")
        
        // Initialize TTS first, speech recognizer will be created fresh when needed
        tts = TextToSpeech(this, this)

        // Detect preferred skin
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        selectedSkin = prefs.getString("call_skin", "android") ?: "android"
        
        setupSkin(selectedSkin)

        // Load Custom Caller ID
        val callerInfo = contactRepository.getCallerInfo()
        binding.tvAndroidName.text = callerInfo.name
        binding.tvIosName.text = callerInfo.name

        // Play default ringtone with proper audio attributes
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, notification)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone?.isLooping = true
            }
            ringtone?.play()
        } catch (e: Exception) {
            Toast.makeText(this, "Incoming call...", Toast.LENGTH_SHORT).show()
        }

        // Start Dead Man's Switch (30 seconds)
        startDeadManTimer()

        // Android Skin Actions
        binding.fabAndroidAnswer.setOnClickListener { handleAnswer() }
        binding.fabAndroidDecline.setOnClickListener { handleDecline() }
        binding.btnAndroidEndCall.setOnClickListener { handleDecline() }

        // iOS Skin Actions
        binding.fabIosAnswer.setOnClickListener { handleAnswer() }
        binding.fabIosDecline.setOnClickListener { handleDecline() }
        binding.btnIosEndCall.setOnClickListener { handleDecline() }
        
        setupGridActions()
    }

    private fun setupGridActions() {
        // Android Grid
        binding.btnAndroidMute.setOnClickListener { toggleMute() }
        binding.btnAndroidKeypad.setOnClickListener { toggleKeypad() }
        binding.btnAndroidSpeaker.setOnClickListener { toggleSpeaker() }
        binding.btnAndroidAddCall.setOnClickListener { showMockAction("Add call dialog") }
        binding.btnAndroidVideo.setOnClickListener { showMockAction("Switching to video...") }
        binding.btnAndroidContacts.setOnClickListener { showMockAction("Opening contacts...") }
        binding.btnAndroidHideKeypad.setOnClickListener { toggleKeypad() }

        // iOS Grid
        binding.btnIosMute.setOnClickListener { toggleMute() }
        binding.btnIosKeypad.setOnClickListener { toggleKeypad() }
        binding.btnIosSpeaker.setOnClickListener { toggleSpeaker() }
        binding.btnIosAddCall.setOnClickListener { showMockAction("Add call dialog") }
        binding.btnIosVideo.setOnClickListener { showMockAction("Switching to FaceTime...") }
        binding.btnIosContacts.setOnClickListener { showMockAction("Opening contacts...") }
        binding.btnIosHideKeypad.setOnClickListener { toggleKeypad() }
    }

    private var isKeypadOpen = false

    private fun toggleKeypad() {
        isKeypadOpen = !isKeypadOpen
        if (selectedSkin == "android") {
            binding.groupAndroidInCall.visibility = if (isKeypadOpen) View.GONE else View.VISIBLE
            binding.layoutAndroidKeypad.visibility = if (isKeypadOpen) View.VISIBLE else View.GONE
            binding.btnAndroidHideKeypad.visibility = if (isKeypadOpen) View.VISIBLE else View.GONE
        } else {
            binding.groupIosInCall.visibility = if (isKeypadOpen) View.GONE else View.VISIBLE
            binding.layoutIosKeypad.visibility = if (isKeypadOpen) View.VISIBLE else View.GONE
            binding.btnIosHideKeypad.visibility = if (isKeypadOpen) View.VISIBLE else View.GONE
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        val color = if (isMuted) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#FFFFFF")
        binding.ivAndroidMute.setColorFilter(color)
        binding.ivIosMute.setColorFilter(color)
        Toast.makeText(this, if (isMuted) "Microphone muted" else "Microphone unmuted", Toast.LENGTH_SHORT).show()
    }

    private fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        val color = if (isSpeakerOn) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#FFFFFF")
        binding.ivAndroidSpeaker.setColorFilter(color)
        binding.ivIosSpeaker.setColorFilter(color)
        
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = isSpeakerOn
        
        Toast.makeText(this, if (isSpeakerOn) "Speaker turned ON" else "Speaker turned OFF", Toast.LENGTH_SHORT).show()
    }

    private fun showMockAction(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setupSkin(skin: String) {
        if (skin == "ios") {
            binding.layoutAndroid.visibility = View.GONE
            binding.layoutIos.visibility = View.VISIBLE
        } else {
            binding.layoutAndroid.visibility = View.VISIBLE
            binding.layoutIos.visibility = View.GONE
        }
    }

    private fun handleAnswer() {
        cancelDeadManTimer()
        answerCall()
    }

    private fun handleDecline() {
        cancelDeadManTimer()
        finishCall()
    }

    private fun startDeadManTimer() {
        deadManTimer = object : CountDownTimer(30000, 1000) {
            override fun onTick(millisUntilFinished: Long) { }
            override fun onFinish() {
                // If call wasn't answered/cancelled, ASSUME DANGER
                triggerEmergency()
            }
        }.start()
    }

    private fun cancelDeadManTimer() {
        deadManTimer?.cancel()
    }

    private fun triggerEmergency() {
        ringtone?.stop()
        val contacts = contactRepository.getContacts()
        if (contacts.isNotEmpty()) {
            sosManager.triggerSos(contacts)
            if (getSharedPreferences("safety_settings", MODE_PRIVATE).getBoolean("silent_sos", false) == false) {
                Toast.makeText(this@FakeCallActivity, "No response - SOS SENT!", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    private fun createSpeechRecognizer() {
        // Destroy existing recognizer first
        destroySpeechRecognizer()
        
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available")
            Toast.makeText(this, "Voice recognition unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                isCurrentlyListening = true
                recognitionRetryCount = 0
                updateStatusText("Listening...")
            }
            
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech started")
            }
            
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            
            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
                isCurrentlyListening = false
            }

            override fun onError(error: Int) {
                isCurrentlyListening = false
                val errorMsg = getErrorText(error)
                Log.e(TAG, "Recognition error: $errorMsg")
                
                // Only retry on recoverable errors
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // No speech detected, restart listening after delay
                        scheduleRestartListening()
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // Recognizer busy, recreate and restart
                        mainHandler.postDelayed({
                            if (isListeningEnabled && isCallActive) {
                                createSpeechRecognizer()
                                scheduleRestartListening()
                            }
                        }, 1000)
                    }
                    SpeechRecognizer.ERROR_CLIENT -> {
                        // Client error, recreate recognizer
                        if (recognitionRetryCount < maxRetries) {
                            recognitionRetryCount++
                            mainHandler.postDelayed({
                                if (isListeningEnabled && isCallActive) {
                                    createSpeechRecognizer()
                                    scheduleRestartListening()
                                }
                            }, 1500)
                        }
                    }
                    else -> {
                        // Other errors, try to restart
                        scheduleRestartListening()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                isCurrentlyListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "Results: $matches")
                
                if (!matches.isNullOrEmpty()) {
                    val bestMatch = matches[0]
                    if (bestMatch.isNotBlank()) {
                        handleSpeechResult(bestMatch)
                    }
                }
                
                // Schedule restart after processing
                scheduleRestartListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // Don't process partial results to avoid false triggers
                // We wait for final results for accuracy
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        
        Log.d(TAG, "Speech recognizer created")
    }
    
    private fun getErrorText(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown error $errorCode"
        }
    }
    
    private fun destroySpeechRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying speech recognizer", e)
        }
        speechRecognizer = null
        isCurrentlyListening = false
    }
    
    private fun scheduleRestartListening() {
        if (!isListeningEnabled || !isCallActive || isTtsSpeaking) {
            Log.d(TAG, "Not scheduling restart: enabled=$isListeningEnabled, active=$isCallActive, speaking=$isTtsSpeaking")
            return
        }
        
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (isListeningEnabled && isCallActive && !isTtsSpeaking) {
                startVoiceListening()
            }
        }, LISTEN_RESTART_DELAY)
    }
    
    private fun startVoiceListening() {
        if (!isListeningEnabled || !isCallActive || isTtsSpeaking) {
            Log.d(TAG, "Cannot start listening: enabled=$isListeningEnabled, active=$isCallActive, speaking=$isTtsSpeaking")
            return
        }
        
        if (isCurrentlyListening) {
            Log.d(TAG, "Already listening, skipping")
            return
        }
        
        if (speechRecognizer == null) {
            createSpeechRecognizer()
        }
        
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) // Disable partial for stability
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500)
            }
            
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "Started listening")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recognition", e)
            isCurrentlyListening = false
            scheduleRestartListening()
        }
    }
    
    private fun stopVoiceListening() {
        isListeningEnabled = false
        mainHandler.removeCallbacksAndMessages(null)
        
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognition", e)
        }
        isCurrentlyListening = false
    }
    
    private fun updateStatusText(text: String) {
        runOnUiThread {
            if (selectedSkin == "android") {
                binding.tvAndroidStatus.text = text
            } else {
                binding.tvIosStatus.text = text
            }
        }
    }
    
    private fun handleSpeechResult(text: String) {
        // Debounce to prevent processing same speech multiple times
        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < MIN_SPEECH_INTERVAL) {
            Log.d(TAG, "Ignoring speech result (too soon)")
            return
        }
        lastProcessedTime = now
        
        Log.d(TAG, "Processing speech: '$text'")
        
        val transcript = "User: $text"
        contactRepository.saveCallLog("${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}: $transcript")
        
        val lowerText = text.lowercase(Locale.getDefault()).trim()
        
        // Check for SOS trigger word first (custom word set by user)
        if (sosTriggerWord.isNotEmpty() && lowerText.contains(sosTriggerWord)) {
            Log.d(TAG, "SOS TRIGGER WORD DETECTED: $sosTriggerWord")
            stopVoiceListening()
            speakWithAi("Emergency triggered. Sending help now.")
            mainHandler.postDelayed({ triggerEmergency() }, 2000)
            return
        }
        
        // Check for emergency keywords
        val emergencyKeywords = listOf("help", "emergency", "danger", "save me", "call police", "attack")
        if (emergencyKeywords.any { lowerText.contains(it) }) {
            Log.d(TAG, "Emergency keyword detected")
            stopVoiceListening()
            speakWithAi("I am sending help to your location immediately. Stay on the line.")
            mainHandler.postDelayed({ triggerEmergency() }, 2500)
            return
        }
        
        // Check for negative/unsafe responses  
        val unsafeKeywords = listOf("no", "not safe", "not okay", "trouble", "scared", "afraid", "hurt")
        if (unsafeKeywords.any { lowerText.contains(it) }) {
            Log.d(TAG, "Unsafe response detected")
            stopVoiceListening()
            speakWithAi("I understand. I'm alerting your emergency contacts right now. Help is on the way.")
            mainHandler.postDelayed({ triggerEmergency() }, 2500)
            return
        }
        
        // Check for safe responses
        val safeKeywords = listOf("yes", "yeah", "yep", "okay", "ok", "fine", "safe", "good", "alright", "i'm fine", "i am fine", "i'm okay", "i am okay")
        if (safeKeywords.any { lowerText.contains(it) || lowerText == it }) {
            Log.d(TAG, "Safe response detected")
            speakWithAi("Okay, I'm glad you're safe. I'll stay on the line with you. Let me know if anything changes.")
            return
        }
        
        // Unknown response - ask again
        Log.d(TAG, "Unknown response, asking again")
        speakWithAi("I didn't quite catch that. Are you safe? Say yes if you're okay, or say help if you need assistance.")
    }

    private fun answerCall() {
        ringtone?.stop()
        isCallAnswered = true
        isCallActive = true
        
        // Set audio mode for voice communication
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        isSpeakerOn = true
        audioManager.isSpeakerphoneOn = true
        
        // Update speaker button visual
        binding.ivAndroidSpeaker.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
        binding.ivIosSpeaker.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
        
        if (selectedSkin == "android") {
            binding.groupAndroidIncoming.visibility = View.GONE
            binding.groupAndroidInCall.visibility = View.VISIBLE
            binding.btnAndroidEndCall.visibility = View.VISIBLE
            binding.tvAndroidStatus.text = "Connected"
        } else {
            binding.groupIosIncoming.visibility = View.GONE
            binding.groupIosInCall.visibility = View.VISIBLE
            binding.btnIosEndCall.visibility = View.VISIBLE
            binding.tvIosStatus.text = "Connected"
        }
        
        // Start call timer
        callSeconds = 0
        mainHandler.postDelayed(timerRunnable, 1000)
        
        // Create speech recognizer for this call
        createSpeechRecognizer()

        // Speak with AI voice after a short delay
        mainHandler.postDelayed({
            val script = "Hey. I'm just checking in. Are you safe? Just say yes or no."
            speakWithAi(script)
        }, 500)
    }
    
    private fun speakWithAi(text: String) {
        if (!isCallActive) return
        
        // Stop listening while speaking
        isTtsSpeaking = true
        stopVoiceListening()
        
        Log.d(TAG, "AI Speaking: $text")
        
        if (isTtsReady && tts != null) {
            // Set up utterance progress listener before speaking
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started")
                    runOnUiThread {
                        updateStatusText("AI Speaking...")
                    }
                }
                
                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS completed")
                    isTtsSpeaking = false
                    runOnUiThread { 
                        if (isCallActive) {
                            // Enable listening and start after TTS completes
                            isListeningEnabled = true
                            mainHandler.postDelayed({
                                if (isCallActive && !isTtsSpeaking) {
                                    startVoiceListening()
                                }
                            }, 800)
                        }
                    }
                }
                
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS error")
                    isTtsSpeaking = false
                    runOnUiThread { 
                        if (isCallActive) {
                            isListeningEnabled = true
                            startVoiceListening()
                        }
                    }
                }
            })
            
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString())
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "AI_VOICE_${System.currentTimeMillis()}")
        } else {
            // TTS not ready, save for later
            pendingTtsScript = text
            isTtsSpeaking = false
            
            mainHandler.postDelayed({
                if (isTtsReady && pendingTtsScript != null && isCallActive) {
                    speakWithAi(pendingTtsScript!!)
                    pendingTtsScript = null
                } else if (isCallActive) {
                    // Fallback: just start listening without TTS
                    isListeningEnabled = true
                    startVoiceListening()
                }
            }, 1500)
        }
    }

    private fun finishCall() {
        Log.d(TAG, "Finishing call")
        isCallAnswered = false
        isCallActive = false
        isListeningEnabled = false
        isTtsSpeaking = false
        
        ringtone?.stop()
        
        // Stop all handlers
        mainHandler.removeCallbacksAndMessages(null)
        
        // Stop speech recognition
        stopVoiceListening()
        destroySpeechRecognizer()
        
        // Stop TTS
        try {
            tts?.stop()
        } catch (e: Exception) { }
        
        // Reset audio mode
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (e: Exception) { }
        
        finish()
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            
            if (isTtsReady) {
                // Set voice parameters for more natural sound
                tts?.setPitch(1.1f)
                tts?.setSpeechRate(0.9f)
                
                Log.d(TAG, "TTS initialized successfully")
                
                // If call was already answered and there's pending speech
                if (isCallAnswered && pendingTtsScript != null) {
                    mainHandler.postDelayed({
                        speakWithAi(pendingTtsScript!!)
                        pendingTtsScript = null
                    }, 300)
                }
            } else {
                Log.e(TAG, "TTS language not supported")
            }
        } else {
            isTtsReady = false
            Log.e(TAG, "TTS initialization failed")
            
            // TTS failed but don't block the call - start listening anyway if call is answered
            if (isCallAnswered && isCallActive) {
                mainHandler.postDelayed({
                    isListeningEnabled = true
                    startVoiceListening()
                }, 1000)
            }
        }
    }
    
    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isCallAnswered = false
        isCallActive = false
        isListeningEnabled = false
        
        ringtone?.stop()
        mainHandler.removeCallbacksAndMessages(null)
        
        // Clean up speech recognizer
        destroySpeechRecognizer()
        
        // Clean up TTS
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) { }
        tts = null
        
        // Reset audio
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (e: Exception) { }

        super.onDestroy()
    }
}