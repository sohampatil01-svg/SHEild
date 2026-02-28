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
import android.widget.Toast
import com.example.fakecalldistress.data.SafetySettingsRepository
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class VoiceAuthenticator(
    private val context: Context,
    private val repository: SafetySettingsRepository,
    private val onResult: (Boolean) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var retryCount = 0
    private val isActive = AtomicBoolean(false)
    private var hasDeliveredResult = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    companion object {
        private const val TAG = "VoiceAuth"
        private const val MAX_RETRIES = 5
        private const val TIMEOUT_MS = 15000L
        private const val RETRY_DELAY_MS = 800L
    }

    fun startListening() {
        if (isActive.get()) {
            Log.d(TAG, "Already listening")
            return
        }
        
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            mainHandler.post {
                Toast.makeText(context, "Voice auth unavailable - proceeding with SOS", Toast.LENGTH_SHORT).show()
            }
            deliverResult(false)
            return
        }
        
        isActive.set(true)
        hasDeliveredResult = false
        retryCount = 0
        
        // Show toast to user
        mainHandler.post {
            Toast.makeText(context, "Say your safe word to cancel SOS...", Toast.LENGTH_LONG).show()
        }
        
        // Set up timeout
        mainHandler.postDelayed({
            if (isActive.get() && !hasDeliveredResult) {
                Log.w(TAG, "Voice Authentication TIMEOUT after ${TIMEOUT_MS}ms")
                deliverResult(false)
            }
        }, TIMEOUT_MS)
        
        createAndStartRecognizer()
    }
    
    private fun createAndStartRecognizer() {
        if (!isActive.get() || hasDeliveredResult) return
        
        try {
            // Clean up existing recognizer
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (e: Exception) { }
            speechRecognizer = null
            
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p0: Bundle?) {
                    Log.d(TAG, "Ready for speech input")
                }
                
                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech detected")
                }
                
                override fun onRmsChanged(p0: Float) {}
                override fun onBufferReceived(p0: ByteArray?) {}
                override fun onEndOfSpeech() {
                    Log.d(TAG, "End of speech")
                }
                
                override fun onResults(results: Bundle?) {
                    if (!isActive.get() || hasDeliveredResult) return
                    
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    Log.d(TAG, "Results received: $matches")
                    processResults(matches)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // Disabled for stability
                }
                
                override fun onError(error: Int) {
                    if (!isActive.get() || hasDeliveredResult) return
                    
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout"
                        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Busy"
                        SpeechRecognizer.ERROR_CLIENT -> "Client error"
                        else -> "Error $error"
                    }
                    Log.e(TAG, "Recognition error: $errorMsg")
                    
                    // Retry on recoverable errors
                    val isRecoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                                       error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                       error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                       error == SpeechRecognizer.ERROR_CLIENT
                    
                    if (isRecoverable && retryCount < MAX_RETRIES) {
                        retryCount++
                        Log.d(TAG, "Retrying... attempt $retryCount of $MAX_RETRIES")
                        mainHandler.postDelayed({
                            createAndStartRecognizer()
                        }, RETRY_DELAY_MS)
                    } else {
                        Log.w(TAG, "Failed after $retryCount attempts")
                        deliverResult(false)
                    }
                }
                
                override fun onEvent(p0: Int, p1: Bundle?) {}
            })

            speechRecognizer?.startListening(intent)
            Log.d(TAG, "Started listening (attempt ${retryCount + 1})")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recognition", e)
            if (retryCount < MAX_RETRIES) {
                retryCount++
                mainHandler.postDelayed({
                    createAndStartRecognizer()
                }, RETRY_DELAY_MS)
            } else {
                deliverResult(false)
            }
        }
    }

    private fun processResults(matches: List<String>?) {
        if (!isActive.get() || hasDeliveredResult) return
        
        val safeWord = repository.getSecretSafeWord().lowercase(Locale.getDefault()).trim()
        Log.d(TAG, "Checking for safe word: '$safeWord'")
        
        if (safeWord.length < 3) {
            Log.w(TAG, "Safe word too short, failing auth")
            deliverResult(false)
            return
        }
        
        val isAuthenticated = matches?.any { match ->
            val lowerMatch = match.lowercase(Locale.getDefault()).trim()
            val contains = lowerMatch.contains(safeWord)
            Log.d(TAG, "Checking '$lowerMatch' contains '$safeWord': $contains")
            contains
        } ?: false
        
        if (isAuthenticated) {
            Log.d(TAG, "SUCCESS - Safe word matched!")
            deliverResult(true)
        } else {
            // Not the right word, keep listening
            if (retryCount < MAX_RETRIES) {
                retryCount++
                Log.d(TAG, "Wrong word. Retrying... attempt $retryCount")
                mainHandler.postDelayed({
                    createAndStartRecognizer()
                }, RETRY_DELAY_MS)
            } else {
                Log.w(TAG, "Max retries reached, failing auth")
                deliverResult(false)
            }
        }
    }
    
    private fun deliverResult(success: Boolean) {
        if (hasDeliveredResult) return
        hasDeliveredResult = true
        isActive.set(false)
        
        Log.d(TAG, "Delivering result: $success")
        
        // Clean up
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) { }
        speechRecognizer = null
        
        // Deliver callback on main thread
        mainHandler.post {
            onResult(success)
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping")
        isActive.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) { }
        speechRecognizer = null
    }
}
