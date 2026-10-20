package com.example.data

import android.content.Context
import com.example.model.UserProfile
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

class AuthRepository(private val context: Context) {
    private val localStore = LocalAccountStore(context)

    private val auth: FirebaseAuth?
        get() = try {
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            null
        }

    private val database: FirebaseDatabase?
        get() = try {
            FirebaseDatabase.getInstance()
        } catch (e: Exception) {
            null
        }

    val isFirebaseConfigured: Boolean
        get() {
            return try {
                val app = FirebaseApp.getInstance()
                val apiKey = app.options.apiKey
                apiKey.isNotBlank() && apiKey != "AIzaSyDefaultPlaceholderKeyForPreviewEnv"
            } catch (e: Exception) {
                false
            }
        }

    val currentUserUid: String?
        get() = auth?.currentUser?.uid ?: localStore.getCurrentUser()?.uid

    val isUserLoggedIn: Boolean
        get() = (auth?.currentUser != null) || (localStore.getCurrentUser() != null)

    suspend fun register(
        name: String,
        username: String,
        email: String,
        password: String
    ): Result<UserProfile> {
        val trimmedName = name.trim()
        val trimmedUsername = username.trim().lowercase(Locale.ROOT)
        val trimmedEmail = email.trim()

        if (trimmedName.length < 2) {
            return Result.failure(IllegalArgumentException("Please enter a valid full name (at least 2 characters)."))
        }

        if (!trimmedUsername.matches(Regex("^[a-zA-Z0-9_]{3,20}$"))) {
            return Result.failure(IllegalArgumentException("Username must be 3-20 characters long and contain only letters, numbers, or underscores."))
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            return Result.failure(IllegalArgumentException("Please enter a valid email address."))
        }

        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("Password must be at least 6 characters long."))
        }

        // If real Firebase is configured, attempt Firebase registration first
        if (isFirebaseConfigured && auth != null && database != null) {
            try {
                val fbResult = withTimeoutOrNull(6000) {
                    val currentDb = database!!
                    val currentAuth = auth!!

                    val usernameRef = currentDb.getReference("usernames").child(trimmedUsername)
                    val usernameSnapshot = usernameRef.get().await()
                    if (usernameSnapshot.exists()) {
                        return@withTimeoutOrNull Result.failure<UserProfile>(
                            IllegalArgumentException("Username '@$trimmedUsername' is already taken. Please choose a different username.")
                        )
                    }

                    val authResult = currentAuth.createUserWithEmailAndPassword(trimmedEmail, password).await()
                    val uid = authResult.user?.uid ?: return@withTimeoutOrNull Result.failure<UserProfile>(
                        Exception("Registration failed: could not obtain user ID.")
                    )

                    val profile = UserProfile(
                        uid = uid,
                        name = trimmedName,
                        username = trimmedUsername,
                        email = trimmedEmail,
                        createdAt = System.currentTimeMillis()
                    )

                    val updates = hashMapOf<String, Any>(
                        "users/$uid" to profile.toMap(),
                        "usernames/$trimmedUsername" to uid
                    )
                    currentDb.reference.updateChildren(updates).await()
                    localStore.register(trimmedName, trimmedUsername, trimmedEmail, password)
                    Result.success(profile)
                }
                if (fbResult != null) return fbResult
            } catch (e: FirebaseAuthWeakPasswordException) {
                return Result.failure(IllegalArgumentException("Password is too weak. Please use at least 6 characters."))
            } catch (e: FirebaseAuthUserCollisionException) {
                return Result.failure(IllegalArgumentException("An account with email '$trimmedEmail' already exists. Please log in instead."))
            } catch (e: Exception) {
                // If Firebase has an unexpected error, fall through to local registration
            }
        }

        // Local registration fallback
        return localStore.register(trimmedName, trimmedUsername, trimmedEmail, password)
    }

    suspend fun login(
        emailOrUsername: String,
        password: String
    ): Result<UserProfile> {
        val identifier = emailOrUsername.trim()
        if (identifier.isEmpty()) {
            return Result.failure(IllegalArgumentException("Please enter your email or username."))
        }
        if (password.isEmpty()) {
            return Result.failure(IllegalArgumentException("Please enter your password."))
        }

        // Try Firebase if configured
        if (isFirebaseConfigured && auth != null && database != null) {
            try {
                val fbResult = withTimeoutOrNull(6000) {
                    val currentDb = database!!
                    val currentAuth = auth!!
                    val emailToUse: String
                    if (identifier.contains("@")) {
                        emailToUse = identifier
                    } else {
                        val cleanUsername = identifier.lowercase(Locale.ROOT)
                        val usernameSnapshot = currentDb.getReference("usernames").child(cleanUsername).get().await()
                        if (!usernameSnapshot.exists()) {
                            return@withTimeoutOrNull Result.failure<UserProfile>(
                                IllegalArgumentException("No account found with username '@$cleanUsername'. Please check the spelling or register.")
                            )
                        }
                        val uid = usernameSnapshot.getValue(String::class.java)
                            ?: return@withTimeoutOrNull Result.failure<UserProfile>(IllegalArgumentException("Unable to resolve username account."))

                        val userSnapshot = currentDb.getReference("users").child(uid).get().await()
                        emailToUse = userSnapshot.child("email").getValue(String::class.java)
                            ?: return@withTimeoutOrNull Result.failure<UserProfile>(IllegalArgumentException("Account email not found for this username."))
                    }

                    val authResult = currentAuth.signInWithEmailAndPassword(emailToUse, password).await()
                    val uid = authResult.user?.uid ?: return@withTimeoutOrNull Result.failure<UserProfile>(Exception("Login failed: missing user credentials."))

                    val profileSnapshot = currentDb.getReference("users").child(uid).get().await()
                    val profile = if (profileSnapshot.exists()) {
                        UserProfile(
                            uid = uid,
                            name = profileSnapshot.child("name").getValue(String::class.java) ?: "User",
                            username = profileSnapshot.child("username").getValue(String::class.java) ?: "user",
                            email = profileSnapshot.child("email").getValue(String::class.java) ?: emailToUse,
                            createdAt = profileSnapshot.child("createdAt").getValue(Long::class.java) ?: 0L
                        )
                    } else {
                        UserProfile(uid = uid, name = "User", username = "user", email = emailToUse)
                    }
                    Result.success(profile)
                }
                if (fbResult != null) return fbResult
            } catch (e: FirebaseAuthInvalidUserException) {
                return Result.failure(IllegalArgumentException("No account registered with this email or username. Please register first."))
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                return Result.failure(IllegalArgumentException("Incorrect password. Please verify your credentials and try again."))
            } catch (e: Exception) {
                // Fallback to local store check
            }
        }

        // Try local store login
        return localStore.login(identifier, password)
    }

    suspend fun fetchCurrentUserProfile(): UserProfile? {
        val uid = currentUserUid ?: return null
        if (isFirebaseConfigured && database != null) {
            try {
                val snapshot = withTimeoutOrNull(4000) {
                    database?.getReference("users")?.child(uid)?.get()?.await()
                }
                if (snapshot != null && snapshot.exists()) {
                    return UserProfile(
                        uid = uid,
                        name = snapshot.child("name").getValue(String::class.java) ?: "",
                        username = snapshot.child("username").getValue(String::class.java) ?: "",
                        email = snapshot.child("email").getValue(String::class.java) ?: "",
                        createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0L
                    )
                }
            } catch (e: Exception) {}
        }
        return localStore.getCurrentUser()
    }

    fun logout() {
        try {
            auth?.signOut()
        } catch (e: Exception) {}
        localStore.logout()
    }
}
