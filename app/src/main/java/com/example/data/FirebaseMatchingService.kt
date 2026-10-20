package com.example.data

import android.util.Log
import com.example.model.ChatMessage
import com.example.model.ReactionEvent
import com.example.model.RoomSession
import com.example.model.UserProfile
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume

/**
 * Production-ready matching service using Firebase Realtime Database.
 * Atomically matches online users searching for 1-on-1 video chats,
 * manages presence, handles session lifecycle, and prevents race conditions.
 */
class FirebaseMatchingService {

    companion object {
        private const val TAG = "VibeMatchingService"
    }

    private val database: FirebaseDatabase?
        get() = try {
            FirebaseDatabase.getInstance()
        } catch (e: Exception) {
            null
        }

    private var activeMatchListener: ValueEventListener? = null
    private var roomStatusListener: ValueEventListener? = null
    private var currentRoomId: String? = null

    /**
     * Registers real-time online presence for the current user.
     * When the app disconnects or loses network, Firebase automatically
     * removes the user from online_users and match_queue.
     */
    fun registerPresence(uid: String, username: String, name: String) {
        val db = database ?: return
        val userStatusRef = db.getReference("online_users").child(uid)
        val connectedRef = db.getReference(".info/connected")

        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    userStatusRef.onDisconnect().removeValue()
                    val presenceData = hashMapOf(
                        "uid" to uid,
                        "username" to username,
                        "name" to name,
                        "lastSeen" to ServerValue.TIMESTAMP
                    )
                    userStatusRef.setValue(presenceData)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Presence listener cancelled: ${error.message}")
            }
        })
    }

    /**
     * Observes the count of real online users in real-time.
     */
    fun observeOnlineUsersCount(): Flow<Int> = callbackFlow {
        val db = database
        if (db == null) {
            trySend(1)
            close()
            return@callbackFlow
        }
        val onlineRef = db.getReference("online_users")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot.childrenCount.toInt().coerceAtLeast(1))
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        onlineRef.addValueEventListener(listener)
        awaitClose { onlineRef.removeEventListener(listener) }
    }

    /**
     * Starts matching:
     * 1. Cancels any stale search ticket.
     * 2. Atomically attempts to claim an existing waiting candidate in match_queue.
     * 3. If claimed, sets up a private room and alerts both users.
     * 4. If no candidate exists, puts self in match_queue and listens for peer claims.
     */
    suspend fun startMatching(
        currentUser: UserProfile,
        onMatched: (RoomSession) -> Unit
    ) {
        val db = database ?: return
        val myUid = currentUser.uid
        cancelSearch(myUid)

        // Attempt atomic claim from match_queue
        val claimedCandidate = attemptAtomicClaim(currentUser)
        if (claimedCandidate != null) {
            val (candidateUid, candidateName, candidateUsername) = claimedCandidate

            val roomId = "room_${System.currentTimeMillis()}_${(1000..9999).random()}"
            currentRoomId = roomId

            // Create active room
            val roomData = hashMapOf(
                "user1" to candidateUid,
                "user2" to myUid,
                "status" to "active",
                "createdAt" to ServerValue.TIMESTAMP
            )
            db.getReference("rooms").child(roomId).setValue(roomData).await()

            // Post match assignment for the waiting candidate (receiver)
            val candidateMatch = hashMapOf(
                "roomId" to roomId,
                "peerUid" to myUid,
                "peerName" to currentUser.name,
                "peerUsername" to currentUser.username,
                "isInitiator" to false
            )
            db.getReference("active_matches").child(candidateUid).setValue(candidateMatch).await()

            // Trigger match for self (initiator)
            val initiatorSession = RoomSession(
                roomId = roomId,
                peerUid = candidateUid,
                peerName = candidateName,
                peerUsername = candidateUsername,
                isInitiator = true
            )
            onMatched(initiatorSession)
        } else {
            // No candidate found: place self in match_queue
            val queueRef = db.getReference("match_queue").child(myUid)
            queueRef.onDisconnect().removeValue()

            val queueData = hashMapOf(
                "uid" to myUid,
                "name" to currentUser.name,
                "username" to currentUser.username,
                "timestamp" to ServerValue.TIMESTAMP
            )
            queueRef.setValue(queueData).await()

            // Listen for active match notification dispatched by another user
            listenForIncomingMatch(myUid, onMatched)
        }
    }

    /**
     * Uses a Firebase atomic transaction to find and claim the first available
     * candidate in the queue, preventing race conditions when multiple users join.
     */
    private suspend fun attemptAtomicClaim(
        currentUser: UserProfile
    ): Triple<String, String, String>? = suspendCancellableCoroutine { continuation ->
        val db = database
        if (db == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val queueRef = db.getReference("match_queue")
        var claimed: Triple<String, String, String>? = null

        queueRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                claimed = null
                val now = System.currentTimeMillis()

                for (child in currentData.children) {
                    val candidateUid = child.key ?: continue
                    if (candidateUid != currentUser.uid) {
                        val timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L
                        // Skip stale queue entries older than 2 minutes
                        if (now - timestamp > 120_000 && timestamp != 0L) {
                            child.value = null
                            continue
                        }

                        val name = child.child("name").getValue(String::class.java) ?: "User"
                        val username = child.child("username").getValue(String::class.java) ?: "user"
                        claimed = Triple(candidateUid, name, username)

                        // Atomically claim by removing candidate from queue
                        child.value = null
                        break
                    }
                }
                return Transaction.success(currentData)
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                currentData: DataSnapshot?
            ) {
                if (committed && claimed != null) {
                    continuation.resume(claimed)
                } else {
                    continuation.resume(null)
                }
            }
        })
    }

    private fun listenForIncomingMatch(
        myUid: String,
        onMatched: (RoomSession) -> Unit
    ) {
        val db = database ?: return
        val matchRef = db.getReference("active_matches").child(myUid)
        activeMatchListener?.let { matchRef.removeEventListener(it) }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val roomId = snapshot.child("roomId").getValue(String::class.java) ?: return
                    val peerUid = snapshot.child("peerUid").getValue(String::class.java) ?: return
                    val peerName = snapshot.child("peerName").getValue(String::class.java) ?: "User"
                    val peerUsername = snapshot.child("peerUsername").getValue(String::class.java) ?: "user"
                    val isInitiator = snapshot.child("isInitiator").getValue(Boolean::class.java) ?: false

                    currentRoomId = roomId
                    // Remove self from queue and consume match ticket
                    db.getReference("match_queue").child(myUid).removeValue()
                    matchRef.removeValue()

                    onMatched(
                        RoomSession(
                            roomId = roomId,
                            peerUid = peerUid,
                            peerName = peerName,
                            peerUsername = peerUsername,
                            isInitiator = isInitiator
                        )
                    )
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Incoming match listener cancelled: ${error.message}")
            }
        }
        activeMatchListener = listener
        matchRef.addValueEventListener(listener)
    }

    /**
     * Monitors when the current active room is closed by the peer or system.
     */
    fun listenToRoomSessionClosed(roomId: String, onClosed: () -> Unit) {
        val db = database ?: return
        val statusRef = db.getReference("rooms").child(roomId).child("status")
        roomStatusListener?.let { statusRef.removeEventListener(it) }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(String::class.java)
                if (status == "closed") {
                    onClosed()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        roomStatusListener = listener
        statusRef.addValueEventListener(listener)
    }

    /**
     * Leaves and cleans up the active video chat session.
     */
    suspend fun leaveRoom(roomId: String, myUid: String) {
        val db = database ?: return
        try {
            db.getReference("rooms").child(roomId).child("status").setValue("closed").await()
            db.getReference("active_matches").child(myUid).removeValue().await()
            db.getReference("rooms").child(roomId).child("typing").child(myUid).removeValue()
        } catch (ignored: Exception) {}
        currentRoomId = null
    }

    /**
     * Cancels search and removes user from match_queue.
     */
    fun cancelSearch(myUid: String) {
        val db = database ?: return
        db.getReference("match_queue").child(myUid).removeValue()
        activeMatchListener?.let {
            db.getReference("active_matches").child(myUid).removeEventListener(it)
            activeMatchListener = null
        }
    }

    // Reaction Broadcaster
    suspend fun sendReaction(roomId: String, emoji: String, myUid: String) {
        val db = database ?: return
        val reactionRef = db.getReference("rooms").child(roomId).child("reactions").push()
        val event = ReactionEvent(
            id = reactionRef.key ?: "",
            emoji = emoji,
            senderId = myUid,
            timestamp = System.currentTimeMillis()
        )
        val data = hashMapOf(
            "id" to event.id,
            "emoji" to event.emoji,
            "senderId" to event.senderId,
            "timestamp" to event.timestamp
        )
        reactionRef.setValue(data).await()
    }

    fun observeReactions(roomId: String): Flow<ReactionEvent> = callbackFlow {
        val db = database
        if (db == null) {
            close()
            return@callbackFlow
        }
        val reactionsRef = db.getReference("rooms").child(roomId).child("reactions")
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val id = snapshot.child("id").getValue(String::class.java) ?: snapshot.key ?: ""
                val emoji = snapshot.child("emoji").getValue(String::class.java) ?: return
                val senderId = snapshot.child("senderId").getValue(String::class.java) ?: ""
                val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                trySend(ReactionEvent(id, emoji, senderId, timestamp))
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        reactionsRef.addChildEventListener(listener)
        awaitClose { reactionsRef.removeEventListener(listener) }
    }

    // Real-time Chat Messages
    suspend fun sendMessage(roomId: String, text: String, senderId: String, senderName: String) {
        val db = database ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val msgRef = db.getReference("rooms").child(roomId).child("messages").push()
        val data = hashMapOf(
            "id" to (msgRef.key ?: ""),
            "text" to trimmed,
            "senderId" to senderId,
            "senderName" to senderName,
            "timestamp" to ServerValue.TIMESTAMP
        )
        msgRef.setValue(data).await()
    }

    fun observeMessages(roomId: String): Flow<ChatMessage> = callbackFlow {
        val db = database
        if (db == null) {
            close()
            return@callbackFlow
        }
        val messagesRef = db.getReference("rooms").child(roomId).child("messages")
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val id = snapshot.child("id").getValue(String::class.java) ?: snapshot.key ?: ""
                val text = snapshot.child("text").getValue(String::class.java) ?: ""
                val senderId = snapshot.child("senderId").getValue(String::class.java) ?: ""
                val senderName = snapshot.child("senderName").getValue(String::class.java) ?: ""
                val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()
                trySend(ChatMessage(id, senderId, senderName, text, timestamp))
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        messagesRef.addChildEventListener(listener)
        awaitClose { messagesRef.removeEventListener(listener) }
    }

    // Typing Status
    fun setTyping(roomId: String, myUid: String, isTyping: Boolean) {
        val db = database ?: return
        val typingRef = db.getReference("rooms").child(roomId).child("typing").child(myUid)
        if (isTyping) {
            typingRef.onDisconnect().setValue(false)
            typingRef.setValue(true)
        } else {
            typingRef.setValue(false)
        }
    }

    fun observePeerTyping(roomId: String, peerUid: String): Flow<Boolean> = callbackFlow {
        val db = database
        if (db == null) {
            close()
            return@callbackFlow
        }
        val peerTypingRef = db.getReference("rooms").child(roomId).child("typing").child(peerUid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isTyping = snapshot.getValue(Boolean::class.java) ?: false
                trySend(isTyping)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        peerTypingRef.addValueEventListener(listener)
        awaitClose { peerTypingRef.removeEventListener(listener) }
    }
}
