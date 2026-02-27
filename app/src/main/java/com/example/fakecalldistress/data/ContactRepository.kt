package com.example.fakecalldistress.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ContactRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("sos_contacts", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val CONTACTS_KEY = "saved_contacts"
    private val CALLER_INFO_KEY = "caller_info"
    private val CALL_LOGS_KEY = "call_logs"

    fun saveCallLog(log: String) {
        val current = prefs.getStringSet(CALL_LOGS_KEY, mutableSetOf()) ?: mutableSetOf()
        current.add(log)
        prefs.edit().putStringSet(CALL_LOGS_KEY, current).apply()
    }

    fun getCallLogs(): List<String> {
        return prefs.getStringSet(CALL_LOGS_KEY, emptySet())?.toList()?.sortedDescending() ?: emptyList()
    }

    // Save contacts locally (Offline first)
    fun saveContacts(contacts: List<Contact>) {
        val json = gson.toJson(contacts)
        prefs.edit().putString(CONTACTS_KEY, json).apply()
        
        syncWithFirebase(contacts)
    }

    private fun syncWithFirebase(contacts: List<Contact>) {
        try {
            // Get or generate a unique ID for this user
            var userId = prefs.getString("user_id", null)
            if (userId == null) {
                userId = java.util.UUID.randomUUID().toString()
                prefs.edit().putString("user_id", userId).apply()
            }

            // Sync with Firebase Realtime Database
            // Structure: users/{userId}/contacts
            val database = com.google.firebase.database.FirebaseDatabase.getInstance()
            val myRef = database.getReference("users").child(userId).child("contacts")
            myRef.setValue(contacts)
                .addOnSuccessListener { 
                    android.util.Log.d("ContactRepository", "Firebase sync successful")
                }
                .addOnFailureListener { e ->
                    android.util.Log.e("ContactRepository", "Firebase sync failed", e)
                }

        } catch (e: Exception) {
            // Firebase might not be initialized (missing google-services.json) or network issue
            android.util.Log.w("ContactRepository", "Firebase sync skipped: ${e.message}")
        }
    }

    // Get contacts
    fun getContacts(): List<Contact> {
        val json = prefs.getString(CONTACTS_KEY, null)
        return if (json != null) {
            val type = object : TypeToken<List<Contact>>() {}.type
            gson.fromJson(json, type)
        } else {
            emptyList()
        }
    }

    // Save Fake Caller Details
    fun saveCallerInfo(info: CallerInfo) {
        val json = gson.toJson(info)
        prefs.edit().putString(CALLER_INFO_KEY, json).apply()
    }

    // Get Fake Caller Details
    fun getCallerInfo(): CallerInfo {
        val json = prefs.getString(CALLER_INFO_KEY, null)
        return if (json != null) {
            gson.fromJson(json, CallerInfo::class.java)
        } else {
            CallerInfo("Dad", "Mobile") // Default
        }
    }
}
