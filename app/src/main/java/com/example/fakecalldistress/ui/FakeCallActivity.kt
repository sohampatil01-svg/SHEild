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
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fakecalldistress.data.ContactRepository
import com.example.fakecalldistress.logic.SosManager
import com.example.fakecalldistress.databinding.ActivityFakeCallBinding
import java.text.SimpleDateFormat
import java.util.*

class FakeCallActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityFakeCallBinding
    private var ringtone: Ringtone? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private lateinit var sosManager: SosManager
    private lateinit var contactRepository: ContactRepository
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecognizing = false
    
    private var selectedSkin = "android" // default
    
    // Call UI State
    private var isMuted = false
    private var isSpeakerOn = false
    
    // Timer to track call duration visually
    private var callSeconds = 0
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            callSeconds++
            val minutes = callSeconds / 60
            val seconds = callSeconds % 60
            val timeStr = String.format("%02d:%02d", minutes, seconds)
            if (selectedSkin == "android") binding.tvAndroidStatus.text = timeStr
            else binding.tvIosStatus.text = timeStr
            timerHandler.postDelayed(this, 1000)
        }
    }

    // Dead Man's Switch (Triggers SOS if not answered/declined in 30s)
    private var deadManTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFakeCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactRepository = ContactRepository(this)
        sosManager = SosManager(this)
        tts = TextToSpeech(this, this)
        initSpeechRecognizer()

        // Detect preferred skin
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        selectedSkin = prefs.getString("call_skin", "android") ?: "android"
        
        setupSkin(selectedSkin)

        // Load Custom Caller ID
        val callerInfo = contactRepository.getCallerInfo()
        binding.tvAndroidName.text = callerInfo.name
        binding.tvIosName.text = callerInfo.name

        // Play default ringtone
        val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(applicationContext, notification)
        ringtone?.play()

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
        
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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

        private fun initSpeechRecognizer() {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isRecognizing = true
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isRecognizing = false
                }
    
                override fun onError(error: Int) {
                    isRecognizing = false
                    // Ignore transient errors and keep listening
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        if ((selectedSkin == "android" && binding.groupAndroidInCall.visibility == View.VISIBLE) ||
                            (selectedSkin == "ios" && binding.groupIosInCall.visibility == View.VISIBLE)) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                startListening()
                            }, 500)
                        }
                    }
                }
    
                override fun onResults(results: Bundle?) {
                    isRecognizing = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        processVoiceInput(matches[0])
                    }
                    
                    // Keep listening after processing a full result
                    if ((selectedSkin == "android" && binding.groupAndroidInCall.visibility == View.VISIBLE) ||
                        (selectedSkin == "ios" && binding.groupIosInCall.visibility == View.VISIBLE)) {
                        startListening()
                    }
                }
    
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0].lowercase(Locale.getDefault())
                        if (text.contains("help") || text.contains("emergency") || text.contains("yes") || text.contains("okay")) {
                            speechRecognizer?.cancel()
                            isRecognizing = false
                            processVoiceInput(matches[0])
                        }
                    }
                }
    
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    private fun startListening() {
        if (!isRecognizing) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer?.startListening(intent)
            isRecognizing = true
        }
    }

    private fun processVoiceInput(text: String) {
        val transcript = "User: $text"
        contactRepository.saveCallLog("${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}: $transcript")
        
        if (text.contains("help", ignoreCase = true) || text.contains("emergency", ignoreCase = true)) {
            tts?.speak("I am sending help to your location immediately. Stay on the line.", TextToSpeech.QUEUE_FLUSH, null, null)
            triggerEmergency()
        } else if (text.contains("yes", ignoreCase = true) || text.contains("okay", ignoreCase = true)) {
            tts?.speak("Okay, I am glad you are safe. I will stay on the line with you until you get home.", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun answerCall() {
        ringtone?.stop()
        
        isSpeakerOn = false
        
        if (selectedSkin == "android") {
            binding.groupAndroidIncoming.visibility = View.GONE
            binding.groupAndroidInCall.visibility = View.VISIBLE
            binding.btnAndroidEndCall.visibility = View.VISIBLE
            binding.tvAndroidStatus.text = "00:00"
        } else {
            binding.groupIosIncoming.visibility = View.GONE
            binding.groupIosInCall.visibility = View.VISIBLE
            binding.btnIosEndCall.visibility = View.VISIBLE
            binding.tvIosStatus.text = "00:00"
        }
        
        timerHandler.postDelayed(timerRunnable, 1000)

        if (isTtsReady) {
            val script = "Hey. I'm just checking in. Are you safe? Just say yes or no."
            tts?.speak(script, TextToSpeech.QUEUE_FLUSH, null, "CHECK_IN")
            
            // Wait for TTS to finish then start listening
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread { startListening() }
                }
                override fun onError(utteranceId: String?) {}
            })
        }
    }

        private fun finishCall() {
            ringtone?.stop()
            tts?.stop()
            tts?.shutdown()
            timerHandler.removeCallbacks(timerRunnable)
            
            finish()
        }
        override fun onInit(status: Int) {
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                
                // Set default pitch and speech rate to sound a bit more natural
                tts?.setPitch(1.0f)
                tts?.setSpeechRate(0.9f)
            } else {
                Toast.makeText(this, "TTS Initialization failed", Toast.LENGTH_SHORT).show()
            }
        }
        override fun onDestroy() {
            ringtone?.stop()
            timerHandler.removeCallbacks(timerRunnable)
            speechRecognizer?.destroy()
            if (tts != null) {
                tts?.stop()
                tts?.shutdown()
            }
    
            super.onDestroy()
        }}