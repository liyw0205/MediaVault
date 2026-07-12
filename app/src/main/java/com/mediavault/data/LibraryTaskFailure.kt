package com.mediavault.data

object LibraryTaskFailure {
    fun redact(message: String): String = message
        .replace(Regex("(?i)(password|passwd|token|api[_-]?key)=([^&\\s]+)"), "$1=***")
        .replace(Regex("(https?://[^:/\\s]+:)[^@/\\s]+@"), "$1***@")
        .trim()
        .take(500)

    fun category(message: String): String = when {
        message.contains("timeout", ignoreCase = true) -> "timeout"
        message.contains("unauthorized", ignoreCase = true) || message.contains("forbidden", ignoreCase = true) -> "credential"
        message.contains("network", ignoreCase = true) || message.contains("connect", ignoreCase = true) -> "network"
        else -> "unknown"
    }
}
