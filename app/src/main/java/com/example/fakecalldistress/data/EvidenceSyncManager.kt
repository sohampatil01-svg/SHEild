package com.example.fakecalldistress.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Backend-facing layer for syncing collected evidence (audio, future photos, etc.)
 * to a remote service. For now this is a local stub that simply logs what would be sent.
 *
 * This keeps a clean separation so you can later plug in your own backend
 * (Firebase Storage, custom API, S3, etc.) without touching the recording logic.
 */
class EvidenceSyncManager private constructor(private val appContext: Context) {

    enum class EvidenceType {
        AUDIO,
        PHOTO,
        VIDEO
    }

    fun enqueueUpload(file: File, type: EvidenceType) {
        if (!file.exists()) {
            Log.w("EvidenceSyncManager", "enqueueUpload called with missing file: ${file.absolutePath}")
            return
        }

        // In a real implementation, enqueue a WorkManager job here that:
        // 1) uploads the file to your backend
        // 2) marks it as synced in local DB / SharedPreferences
        Log.d(
            "EvidenceSyncManager",
            "Queued evidence for upload. type=$type path=${file.absolutePath} size=${file.length()}"
        )
    }

    companion object {
        @Volatile
        private var instance: EvidenceSyncManager? = null

        fun getInstance(context: Context): EvidenceSyncManager {
            return instance ?: synchronized(this) {
                instance ?: EvidenceSyncManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

