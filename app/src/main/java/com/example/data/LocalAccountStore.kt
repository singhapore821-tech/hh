package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.UserProfile
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

class LocalAccountStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("vibe_tv_local_auth", Context.MODE_PRIVATE)

    fun isUsernameTaken(username: String): Boolean {
        val clean = username.trim().lowercase(Locale.ROOT)
        return prefs.contains("username_$clean")
    }

    fun isEmailRegistered(email: String): Boolean {
        val clean = email.trim().lowercase(Locale.ROOT)
        return prefs.contains("email_$clean")
    }

    fun register(
        name: String,
        username: String,
        email: String,
        password: String
    ): Result<UserProfile> {
        val cleanUsername = username.trim().lowercase(Locale.ROOT)
        val cleanEmail = email.trim().lowercase(Locale.ROOT)

        if (isUsernameTaken(cleanUsername)) {
            return Result.failure(IllegalArgumentException("Username '@$cleanUsername' is already taken. Please choose another."))
        }
        if (isEmailRegistered(cleanEmail)) {
            return Result.failure(IllegalArgumentException("An account with email '$cleanEmail' already exists. Please sign in instead."))
        }

        val uid = "local_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val hash = hashPassword(password)
        val profile = UserProfile(
            uid = uid,
            name = name.trim(),
            username = cleanUsername,
            email = cleanEmail,
            createdAt = System.currentTimeMillis()
        )

        val json = JSONObject().apply {
            put("uid", profile.uid)
            put("name", profile.name)
            put("username", profile.username)
            put("email", profile.email)
            put("createdAt", profile.createdAt)
            put("passwordHash", hash)
        }

        prefs.edit()
            .putString("user_$uid", json.toString())
            .putString("username_$cleanUsername", uid)
            .putString("email_$cleanEmail", uid)
            .putString("current_uid", uid)
            .apply()

        return Result.success(profile)
    }

    fun login(emailOrUsername: String, password: String): Result<UserProfile> {
        val clean = emailOrUsername.trim().lowercase(Locale.ROOT)
        val uid = if (clean.contains("@")) {
            prefs.getString("email_$clean", null)
        } else {
            prefs.getString("username_$clean", null)
        }

        if (uid == null) {
            return Result.failure(IllegalArgumentException("No account found for '$emailOrUsername'. Please check your credentials or register."))
        }

        val jsonString = prefs.getString("user_$uid", null)
            ?: return Result.failure(IllegalArgumentException("Account record not found."))

        val json = JSONObject(jsonString)
        val storedHash = json.optString("passwordHash", "")
        if (storedHash != hashPassword(password)) {
            return Result.failure(IllegalArgumentException("Incorrect password. Please verify your password and try again."))
        }

        val profile = UserProfile(
            uid = json.getString("uid"),
            name = json.getString("name"),
            username = json.getString("username"),
            email = json.getString("email"),
            createdAt = json.optLong("createdAt", System.currentTimeMillis())
        )

        prefs.edit().putString("current_uid", uid).apply()
        return Result.success(profile)
    }

    fun getCurrentUser(): UserProfile? {
        val uid = prefs.getString("current_uid", null) ?: return null
        val jsonString = prefs.getString("user_$uid", null) ?: return null
        return try {
            val json = JSONObject(jsonString)
            UserProfile(
                uid = json.getString("uid"),
                name = json.getString("name"),
                username = json.getString("username"),
                email = json.getString("email"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            null
        }
    }

    fun logout() {
        prefs.edit().remove("current_uid").apply()
    }

    private fun hashPassword(password: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
