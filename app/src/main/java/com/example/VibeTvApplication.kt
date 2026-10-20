package com.example

import android.app.Application
import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class VibeTvApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initFirebase(this)
    }

    companion object {
        fun initFirebase(context: Context) {
            try {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    // Try default initialization first (works if google-services.json is present)
                    try {
                        FirebaseApp.initializeApp(context)
                    } catch (e: Exception) {
                        Log.w("VibeTv", "Standard Firebase init attempted: ${e.message}")
                    }

                    // Fallback configuration if google-services.json is not yet placed
                    if (FirebaseApp.getApps(context).isEmpty()) {
                        val options = FirebaseOptions.Builder()
                            .setApplicationId("1:302847450550:android:com.aistudio.vibetv.app")
                            .setApiKey("AIzaSyDefaultPlaceholderKeyForPreviewEnv")
                            .setDatabaseUrl("https://vibetv-default-rtdb.firebaseio.com")
                            .setProjectId("vibetv-app")
                            .build()
                        FirebaseApp.initializeApp(context, options)
                        Log.i("VibeTv", "Initialized FirebaseApp with fallback configuration")
                    }
                }
            } catch (e: Exception) {
                Log.e("VibeTv", "Error during Firebase initialization", e)
            }
        }
    }
}
