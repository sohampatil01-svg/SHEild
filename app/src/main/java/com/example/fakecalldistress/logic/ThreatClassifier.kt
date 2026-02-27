package com.example.fakecalldistress.logic

import android.content.Context
import android.media.AudioRecord
import android.util.Log
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import java.util.Timer
import java.util.TimerTask

class ThreatClassifier(val context: Context, val onThreatDetected: (String) -> Unit) {

    private var audioClassifier: AudioClassifier? = null
    private var audioRecord: AudioRecord? = null
    private var timer: Timer? = null
    private val MODEL_FILE = "yamnet.tflite" // You must add this to assets/
    private val THREAT_LABELS = listOf("Scream", "Shout", "Glass breaking", "Explosion", "Gunshot, gunfire")
    private val CONFIDENCE_THRESHOLD = 0.6f

    fun start() {
        try {
            // Load the model from assets
            audioClassifier = AudioClassifier.createFromFile(context, MODEL_FILE)
            audioRecord = audioClassifier?.createAudioRecord()
            
            audioRecord?.startRecording()
            
            // Run inference every 500ms
            timer = Timer()
            timer?.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    classifyAudio()
                }
            }, 0, 500) // 500ms interval (2Hz)

            Log.d("ThreatClassifier", "AI Threat Detection Started")

        } catch (e: Exception) {
            Log.e("ThreatClassifier", "Failed to init AI Model. Missing yamnet.tflite?", e)
        }
    }

    private fun classifyAudio() {
        audioClassifier?.let { classifier ->
            // 1. Get audio tensor
            val tensorAudio = classifier.createInputTensorAudio()
            tensorAudio.load(audioRecord)

            // 2. Run inference
            val output = classifier.classify(tensorAudio)

            // 3. Filter results
            val filtered = output[0].categories.filter { 
                it.score > CONFIDENCE_THRESHOLD && THREAT_LABELS.contains(it.label) 
            }

            if (filtered.isNotEmpty()) {
                val threat = filtered.maxByOrNull { it.score }
                Log.w("ThreatClassifier", "THREAT DETECTED: ${threat?.label} (${threat?.score})")
                threat?.label?.let { onThreatDetected(it) }
            }
        }
    }

    fun stop() {
        timer?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioClassifier?.close() // Fix: Close classifier to free native resources
        Log.d("ThreatClassifier", "AI Threat Detection Stopped")
    }
}
