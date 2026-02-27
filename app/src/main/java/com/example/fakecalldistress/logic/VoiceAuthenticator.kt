package com.example.fakecalldistress.logic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.fakecalldistress.data.SafetySettingsRepository
import java.util.*

class VoiceAuthenticator(
    private val context: Context,
    private val repository: SafetySettingsRepository,
    private val onResult: (Boolean) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var retryCount = 0
    private val MAX_RETRIES = 3

    fun startListening() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                processResults(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    val safeWord = repository.getSecretSafeWord()
                    val isMatch = matches.any { it.contains(safeWord, ignoreCase = true) }
                    if (isMatch) {
                        Log.d("VoiceAuth", "Voice Authentication SUCCESS (Partial Match)")
                        onResult(true)
                        stop()
                    }
                }
            }

            override fun onReadyForSpeech(p0: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(p0: Float) {}
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEndOfSpeech() {}
            
            override fun onError(error: Int) {
                Log.e("VoiceAuth", "Speech Recognition Error: $error")
                if (retryCount < MAX_RETRIES) {
                    retryCount++
                    Log.d("VoiceAuth", "Retrying speech recognition... Attempt $retryCount")
                    Handler(Looper.getMainLooper()).postDelayed({
                        speechRecognizer?.startListening(intent)
                    }, 500)
                } else {
                    Log.w("VoiceAuth", "Voice Authentication FAILED - Max retries reached")
                    onResult(false)
                    stop()
                }
            }
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun processResults(matches: List<String>?) {
        val safeWord = repository.getSecretSafeWord()
        val isAuthenticated = matches?.any { it.contains(safeWord, ignoreCase = true) } ?: false
        
        if (isAuthenticated) {
            Log.d("VoiceAuth", "Voice Authentication SUCCESS")
            onResult(true)
        } else {
            Log.w("VoiceAuth", "Voice Authentication FAILED - Incorrect word")
            onResult(false)
        }
        stop()
    }

    fun stop() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
