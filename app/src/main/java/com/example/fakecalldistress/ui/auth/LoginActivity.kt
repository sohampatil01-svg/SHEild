package com.example.fakecalldistress.ui.auth

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fakecalldistress.databinding.ActivityLoginBinding
import com.example.fakecalldistress.ui.MainActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("auth_prefs", MODE_PRIVATE)
        
        // Check if user is registered
        if (!prefs.contains("pin")) {
            // First time - show registration view logic
            binding.tvTitle.text = "Create PIN"
            binding.btnLogin.text = "Register"
        } else {
            binding.tvTitle.text = "Enter PIN"
            binding.btnLogin.text = "Login"
        }

        binding.btnLogin.setOnClickListener {
            val pin = binding.etPin.text.toString()
            if (pin.length < 4) {
                Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!prefs.contains("pin")) {
                // Register
                prefs.edit().putString("pin", pin).apply()
                Toast.makeText(this, "Registration Successful", Toast.LENGTH_SHORT).show()
                startApp()
            } else {
                // Login
                val storedPin = prefs.getString("pin", "")
                if (pin == storedPin) {
                    startApp()
                } else {
                    Toast.makeText(this, "Invalid PIN", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startApp() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
