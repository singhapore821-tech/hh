package com.example.data

import com.example.model.ChatMessage
import com.example.model.ReactionEvent
import com.example.model.RoomSession
import com.example.model.UserProfile
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface wrapping the FirebaseMatchingService for
 * online presence, atomic queue matching, and in-room signaling.
 */
class MatchingRepository(
    private val service: FirebaseMatchingService = FirebaseMatchingService()
) {
    fun setOnlinePresence(uid: String, username: String = "", name: String = "") {
        service.registerPresence(uid, username, name)
    }

    fun observeOnlineUsersCount(): Flow<Int> = service.observeOnlineUsersCount()

    suspend fun findMatch(
        currentUser: UserProfile,
        onMatched: (RoomSession) -> Unit
    ) {
        service.startMatching(currentUser, onMatched)
    }

    fun listenToRoomSessionClosed(roomId: String, onClosed: () -> Unit) {
        service.listenToRoomSessionClosed(roomId, onClosed)
    }

    suspend fun leaveRoom(roomId: String, myUid: String) {
        service.leaveRoom(roomId, myUid)
    }

    fun cancelMatching(myUid: String) {
        service.cancelSearch(myUid)
    }

    suspend fun sendReaction(roomId: String, emoji: String, myUid: String) {
        service.sendReaction(roomId, emoji, myUid)
    }

    fun observeReactions(roomId: String): Flow<ReactionEvent> {
        return service.observeReactions(roomId)
    }

    suspend fun sendMessage(roomId: String, text: String, senderId: String, senderName: String) {
        service.sendMessage(roomId, text, senderId, senderName)
    }

    fun observeMessages(roomId: String): Flow<ChatMessage> {
        return service.observeMessages(roomId)
    }

    fun setTyping(roomId: String, myUid: String, isTyping: Boolean) {
        service.setTyping(roomId, myUid, isTyping)
    }

    fun observePeerTyping(roomId: String, peerUid: String): Flow<Boolean> {
        return service.observePeerTyping(roomId, peerUid)
    }
}
