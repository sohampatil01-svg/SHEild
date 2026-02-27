package com.example.fakecalldistress.utils

class ValidationUtils {
    fun isValidPhone(phone: String): Boolean {
        return phone.length >= 10
    }
}
