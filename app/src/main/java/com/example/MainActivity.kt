package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.data.AuthRepository
import com.example.data.MatchingRepository
import com.example.model.UserProfile
import com.example.ui.auth.AuthScreen
import com.example.ui.matching.VideoCallScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.VibePink
import com.example.ui.theme.VibePurpleDark

class MainActivity : ComponentActivity() {

    private val authRepository by lazy { AuthRepository(applicationContext) }
    private val matchingRepository by lazy { MatchingRepository() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VibeTvApplication.initFirebase(this)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                VibeTvApp(
                    authRepository = authRepository,
                    matchingRepository = matchingRepository
                )
            }
        }
    }
}

@Composable
fun VibeTvApp(
    authRepository: AuthRepository,
    matchingRepository: MatchingRepository,
    modifier: Modifier = Modifier
) {
    var currentUser by remember { mutableStateOf<UserProfile?>(null) }
    var isCheckingAuth by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (authRepository.isUserLoggedIn) {
            val profile = authRepository.fetchCurrentUserProfile()
            if (profile != null) {
                currentUser = profile
            }
        }
        isCheckingAuth = false
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = VibePurpleDark
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VibePurpleDark)
        ) {
            if (isCheckingAuth) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = VibePink,
                        strokeWidth = androidx.compose.ui.unit.Dp(3f)
                    )
                }
            } else {
                val user = currentUser
                if (user != null) {
                    VideoCallScreen(
                        currentUser = user,
                        matchingRepository = matchingRepository,
                        onLogout = {
                            authRepository.logout()
                            currentUser = null
                        }
                    )
                } else {
                    AuthScreen(
                        authRepository = authRepository,
                        onAuthSuccess = { profile ->
                            currentUser = profile
                        }
                    )
                }
            }
        }
    }
}
