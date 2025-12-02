package com.example.test3

data class LogEntry(
    val time: String,
    val formattedTime: String,
    val temps: Map<String, Any>
)