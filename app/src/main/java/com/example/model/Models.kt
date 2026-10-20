package com.example.model

data class UserProfile(
    val uid: String = "",
    val name: String = "",
    val username: String = "",
    val email: String = "",
    val createdAt: Long = 0L
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "uid" to uid,
            "name" to name,
            "username" to username,
            "email" to email,
            "createdAt" to createdAt
        )
    }
}

data class ChatMessage(
    val id: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val text: String = "",
    val timestamp: Long = 0L
)

data class ReactionEvent(
    val id: String = "",
    val emoji: String = "",
    val senderId: String = "",
    val timestamp: Long = 0L
)

data class RoomSession(
    val roomId: String = "",
    val peerUid: String = "",
    val peerName: String = "",
    val peerUsername: String = "",
    val isInitiator: Boolean = false
)

sealed interface MatchingState {
    object Idle : MatchingState
    object Searching : MatchingState
    data class Connected(val session: RoomSession) : MatchingState
    data class Error(val message: String) : MatchingState
}
