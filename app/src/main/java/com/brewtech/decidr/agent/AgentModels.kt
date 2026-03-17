package com.brewtech.decidr.agent

enum class AgentState {
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    IDLE,
    ERROR
}

data class TranscriptEntry(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long
)
