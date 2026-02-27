package com.example.fakecalldistress.logic

import android.content.Context
import android.content.Intent
import android.os.Bundle
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

    fun startListening() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val safeWord = repository.getSecretSafeWord()
                
                val isAuthenticated = matches?.any { it.equals(safeWord, ignoreCase = true) } ?: false
                
                if (isAuthenticated) {
                    Log.d("VoiceAuth", "Voice Authentication SUCCESS")
                    onResult(true)
                } else {
                    Log.w("VoiceAuth", "Voice Authentication FAILED - Incorrect word")
                    onResult(false)
                }
                stop()
            }

            override fun onReadyForSpeech(p0: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(p0: Float) {}
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Log.e("VoiceAuth", "Speech Recognition Error: $error")
                onResult(false)
                stop()
            }
            override fun onPartialResults(p0: Bundle?) {}
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    fun stop() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
