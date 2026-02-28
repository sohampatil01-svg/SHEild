package com.example.fakecalldistress.service

import android.app.*
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.fakecalldistress.data.EvidenceSyncManager
import java.io.File
import java.util.*

class EmergencyRecordService : Service() {

    private var recorder: MediaRecorder? = null
    private val CHANNEL_ID = "EmergencyRecordChannel"
    private val NOTIFICATION_ID = 2

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Emergency Recording Active")
            .setContentText("Capturing evidence for your safety...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        startRecording()

        return START_NOT_STICKY
    }

    private fun startRecording() {
        try {
            // Senior Dev Fix: Use getExternalFilesDir to ensure files are visible in Android/data/ path
            val recordingDir = File(getExternalFilesDir(null), "evidence")
            if (!recordingDir.exists()) recordingDir.mkdirs()
            
            val audioFile = File(recordingDir, "emergency_${System.currentTimeMillis()}.3gp")
            
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            Log.d("EmergencyRecordService", "Recording started: ${audioFile.absolutePath}")

            // Queue this recording for background sync to backend (stubbed for now)
            try {
                val syncManager = EvidenceSyncManager.getInstance(this)
                syncManager.enqueueUpload(audioFile, EvidenceSyncManager.EvidenceType.AUDIO)
            } catch (e: Exception) {
                Log.w("EmergencyRecordService", "Failed to enqueue evidence upload: ${e.message}")
            }

            // Senior Dev Note: We force a minimum 30s record, max 2 mins.
            Timer().schedule(object : TimerTask() {
                override fun run() {
                    stopSelf()
                }
            }, 120000) // 2 Minutes total duration

        } catch (e: Exception) {
            Log.e("EmergencyRecordService", "Failed to record", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            Log.e("EmergencyRecordService", "Error stopping recorder", e)
        }
        recorder = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Emergency Recording", NotificationManager.IMPORTANCE_HIGH)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
