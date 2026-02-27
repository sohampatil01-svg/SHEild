package com.example.fakecalldistress.utils

class ThreadUtils {
    fun runOnBackground(action: () -> Unit) {
        Thread(action).start()
    }
}
