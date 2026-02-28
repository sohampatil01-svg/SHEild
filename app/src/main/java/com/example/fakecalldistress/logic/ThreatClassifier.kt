package com.example.fakecalldistress.logic

import android.content.Context
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

class ThreatClassifier(val context: Context, val onThreatDetected: (String) -> Unit) {

    private var audioClassifier: AudioClassifier? = null
    private var audioRecord: AudioRecord? = null
    private var timer: Timer? = null
    private val isRunning = AtomicBoolean(false)
    private var consecutiveDetections = 0
    
    companion object {
        private const val MODEL_FILE = "yamnet.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.45f
        private const val REQUIRED_CONSECUTIVE_DETECTIONS = 2
        private const val CLASSIFICATION_INTERVAL_MS = 500L
        
        private val THREAT_LABELS = setOf(
            "Screaming", "Scream", "Shout", "Yell",
            "Glass", "Glass breaking", "Shatter",
            "Explosion", "Gunshot, gunfire", "Gunshot",
            "Emergency vehicle", "Siren", "Civil defense siren",
            "Crying, sobbing", "Wail, moan", "Whimper"
        )
        
        private val HIGH_PRIORITY_LABELS = setOf(
            "Screaming", "Scream", "Gunshot, gunfire", "Gunshot", "Explosion"
        )
    }

    fun start() {
        if (isRunning.get()) {
            Log.d("ThreatClassifier", "Already running")
            return
        }
        
        try {
            // Load the model from assets
            audioClassifier = AudioClassifier.createFromFile(context, MODEL_FILE)
            audioRecord = audioClassifier?.createAudioRecord()
            
            if (audioRecord == null) {
                Log.e("ThreatClassifier", "Failed to create audio record")
                showError("Microphone access failed")
                return
            }
            
            audioRecord?.startRecording()
            isRunning.set(true)
            consecutiveDetections = 0
            
            // Run inference at regular intervals
            timer = Timer("ThreatClassifier")
            timer?.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    if (isRunning.get()) {
                        classifyAudio()
                    }
                }
            }, 500, CLASSIFICATION_INTERVAL_MS)

            Log.d("ThreatClassifier", "AI Threat Detection Started")

        } catch (e: Exception) {
            Log.e("ThreatClassifier", "Failed to init AI Model", e)
            showError("AI model initialization failed")
            isRunning.set(false)
        }
    }

    private fun classifyAudio() {
        if (!isRunning.get()) return
        
        try {
            audioClassifier?.let { classifier ->
                // 1. Get audio tensor
                val tensorAudio = classifier.createInputTensorAudio()
                tensorAudio.load(audioRecord)

                // 2. Run inference
                val output = classifier.classify(tensorAudio)
                
                if (output.isEmpty() || output[0].categories.isEmpty()) return

                // 3. Find matching threat categories
                val threats = output[0].categories.filter { category ->
                    category.score > CONFIDENCE_THRESHOLD && 
                    THREAT_LABELS.any { label -> 
                        category.label.contains(label, ignoreCase = true) 
                    }
                }

                if (threats.isNotEmpty()) {
                    val highestThreat = threats.maxByOrNull { it.score }
                    val isHighPriority = HIGH_PRIORITY_LABELS.any { 
                        highestThreat?.label?.contains(it, ignoreCase = true) == true 
                    }
                    
                    Log.w("ThreatClassifier", "Potential threat: ${highestThreat?.label} (${String.format("%.2f", highestThreat?.score)})")
                    
                    // For high priority threats, trigger immediately on high confidence
                    if (isHighPriority && (highestThreat?.score ?: 0f) > 0.7f) {
                        Log.w("ThreatClassifier", "HIGH PRIORITY THREAT DETECTED!")
                        triggerAlert(highestThreat?.label ?: "Unknown")
                        return
                    }
                    
                    // For other threats, require consecutive detections
                    consecutiveDetections++
                    if (consecutiveDetections >= REQUIRED_CONSECUTIVE_DETECTIONS) {
                        Log.w("ThreatClassifier", "THREAT CONFIRMED: ${highestThreat?.label}")
                        triggerAlert(highestThreat?.label ?: "Unknown")
                    }
                } else {
                    // Reset if no threat detected
                    consecutiveDetections = 0
                }
            }
        } catch (e: Exception) {
            Log.e("ThreatClassifier", "Classification error", e)
        }
    }
    
    private fun triggerAlert(threatLabel: String) {
        isRunning.set(false)
        Handler(Looper.getMainLooper()).post {
            onThreatDetected(threatLabel)
        }
        stop()
    }
    
    private fun showError(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun stop() {
        isRunning.set(false)
        consecutiveDetections = 0
        
        try {
            timer?.cancel()
            timer = null
        } catch (e: Exception) { }
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) { }
        
        try {
            audioClassifier?.close()
            audioClassifier = null
        } catch (e: Exception) { }
        
        Log.d("ThreatClassifier", "AI Threat Detection Stopped")
    }
}
