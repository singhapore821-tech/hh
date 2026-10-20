package com.example.ui.matching

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.data.MatchingRepository
import com.example.model.ChatMessage
import com.example.model.MatchingState
import com.example.model.ReactionEvent
import com.example.model.RoomSession
import com.example.model.UserProfile
import com.example.ui.components.FullscreenEmojiBurst
import com.example.ui.components.RadarSearchingView
import com.example.ui.components.ThreeDotsTypingIndicator
import com.example.ui.theme.VibeAccentCyan
import com.example.ui.theme.VibeGreen
import com.example.ui.theme.VibeOnline
import com.example.ui.theme.VibePink
import com.example.ui.theme.VibePurpleDark
import com.example.ui.theme.VibeRed
import com.example.ui.theme.VibeSurfaceVariant
import com.example.webrtc.WebRtcManager
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import org.webrtc.SurfaceViewRenderer
import kotlin.math.roundToInt

@Composable
fun VideoCallScreen(
    currentUser: UserProfile,
    matchingRepository: MatchingRepository,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true &&
                permissions[Manifest.permission.RECORD_AUDIO] == true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
        matchingRepository.setOnlinePresence(currentUser.uid, currentUser.username, currentUser.name)
    }

    val onlineUsersCount by matchingRepository.observeOnlineUsersCount().collectAsState(initial = 1)

    val webRtcManager = remember { WebRtcManager(context) }
    var matchingState by remember { mutableStateOf<MatchingState>(MatchingState.Idle) }
    var activeReaction by remember { mutableStateOf<ReactionEvent?>(null) }
    var currentSession by remember { mutableStateOf<RoomSession?>(null) }

    var isMicMuted by remember { mutableStateOf(false) }
    var isCameraOff by remember { mutableStateOf(false) }
    var chatExpanded by remember { mutableStateOf(true) }
    var messageText by remember { mutableStateOf("") }
    var isPeerTyping by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val chatListState = rememberLazyListState()

    var dragOffset by remember { mutableFloatStateOf(0f) }

    fun startSearching() {
        matchingState = MatchingState.Searching
        messages.clear()
        currentSession = null
        isPeerTyping = false
        webRtcManager.endCall()

        coroutineScope.launch {
            matchingRepository.findMatch(currentUser) { session ->
                currentSession = session
                matchingState = MatchingState.Connected(session)

                // Start WebRTC Call with the matched real user
                webRtcManager.startCall(
                    roomId = session.roomId,
                    myUid = currentUser.uid,
                    peerUid = session.peerUid,
                    isInitiator = session.isInitiator
                )

                // Listen for session close (e.g. peer leaves or swipes away)
                matchingRepository.listenToRoomSessionClosed(session.roomId) {
                    // Session ended by peer: automatically search for another real person!
                    startSearching()
                }

                // Listen for real-time reactions
                matchingRepository.observeReactions(session.roomId).onEach { reaction ->
                    activeReaction = reaction
                }.launchIn(coroutineScope)

                // Listen for messages
                matchingRepository.observeMessages(session.roomId).onEach { msg ->
                    if (messages.none { it.id == msg.id }) {
                        messages.add(msg)
                        chatListState.animateScrollToItem(messages.size - 1)
                    }
                }.launchIn(coroutineScope)

                // Listen for peer typing
                matchingRepository.observePeerTyping(session.roomId, session.peerUid).onEach { typing ->
                    isPeerTyping = typing
                }.launchIn(coroutineScope)
            }
        }
    }

    fun leaveAndSearchNext() {
        val session = currentSession
        coroutineScope.launch {
            if (session != null) {
                matchingRepository.leaveRoom(session.roomId, currentUser.uid)
            } else {
                matchingRepository.cancelMatching(currentUser.uid)
            }
            startSearching()
        }
    }

    // Smooth horizontal swipe gesture handling
    val swipeModifier = Modifier.pointerInput(matchingState) {
        detectHorizontalDragGestures(
            onDragEnd = {
                if (kotlin.math.abs(dragOffset) > 200f) {
                    // Swiped far enough: smooth skip without closing app
                    leaveAndSearchNext()
                }
                dragOffset = 0f
            },
            onDragCancel = {
                dragOffset = 0f
            },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                dragOffset += dragAmount
            }
        )
    }

    // Intercept back navigation: don't close app, cleanly handle call or prompt
    BackHandler {
        if (matchingState is MatchingState.Connected || matchingState is MatchingState.Searching) {
            coroutineScope.launch {
                val session = currentSession
                if (session != null) {
                    matchingRepository.leaveRoom(session.roomId, currentUser.uid)
                }
                matchingRepository.cancelMatching(currentUser.uid)
                webRtcManager.endCall()
                matchingState = MatchingState.Idle
            }
        } else {
            onLogout()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            coroutineScope.launch {
                currentSession?.let { matchingRepository.leaveRoom(it.roomId, currentUser.uid) }
                matchingRepository.cancelMatching(currentUser.uid)
            }
            webRtcManager.release()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .then(swipeModifier),
        color = VibePurpleDark
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(dragOffset.roundToInt(), 0) }
        ) {
            when (matchingState) {
                is MatchingState.Idle -> {
                    // Welcome & Start Vibing Screen
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF1E1128), VibePurpleDark, Color(0xFF0F0818))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(28.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(110.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(listOf(VibeRed, VibePink))
                                    )
                                    .border(3.dp, VibePink, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(56.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Text(
                                text = "Welcome, ${currentUser.name}!",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )

                            Text(
                                text = "@${currentUser.username}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = VibeAccentCyan,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )

                            Spacer(modifier = Modifier.height(30.dp))

                            Text(
                                text = "Ready to connect with real people worldwide?\nSwipe anytime during calls to find the next match.",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = Color(0xFFBCAFCB),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    lineHeight = 22.sp
                                )
                            )

                            Spacer(modifier = Modifier.height(36.dp))

                            Button(
                                onClick = {
                                    if (hasCameraPermission) {
                                        startSearching()
                                    } else {
                                        permissionLauncher.launch(
                                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VibePink,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .fillMaxWidth(0.85f)
                                    .height(56.dp)
                            ) {
                                Icon(Icons.Default.Videocam, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Start Vibing Now",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Logout option
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onLogout() }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ExitToApp,
                                    contentDescription = "Log Out",
                                    tint = Color(0xFFA693B8),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Log Out",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Color(0xFFA693B8)
                                    )
                                )
                            }
                        }
                    }
                }

                is MatchingState.Searching -> {
                    // Real-time radar searching view
                    RadarSearchingView(onlineUsersCount = onlineUsersCount)

                    // Top Bar during search
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "VIBE TV",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                color = VibePink,
                                letterSpacing = 1.sp
                            )
                        )

                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    matchingRepository.cancelMatching(currentUser.uid)
                                    matchingState = MatchingState.Idle
                                }
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0x55000000))
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel search", tint = Color.White)
                        }
                    }
                }

                is MatchingState.Connected -> {
                    val session = (matchingState as MatchingState.Connected).session

                    // 1. Fullscreen Opponent Remote Video Feed
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                SurfaceViewRenderer(ctx).apply {
                                    webRtcManager.initRemoteView(this)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Subtle dark gradient vignette for readable controls
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0xAA000000),
                                            Color.Transparent,
                                            Color(0xCC000000)
                                        )
                                    )
                                )
                        )
                    }

                    // 2. User's Own Camera Preview (Left Side)
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 14.dp)
                            .width(115.dp)
                            .height(165.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(2.dp, VibePink, RoundedCornerShape(16.dp))
                            .background(Color.Black)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                SurfaceViewRenderer(ctx).apply {
                                    webRtcManager.initLocalMedia(this)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // "YOU" Label
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x99000000))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "YOU",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            )
                        }
                    }

                    // 3. Top Call Header (Opponent Info, Status & Controls)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Opponent info badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0x881E1528))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(VibeOnline)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = session.peerName,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = "@${session.peerUsername}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = VibeAccentCyan
                                    )
                                )
                            }
                        }

                        // Top Controls (Switch Camera, Mic toggle, End)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Switch Camera
                            IconButton(
                                onClick = { webRtcManager.switchCamera() },
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x88281D35))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cameraswitch,
                                    contentDescription = "Switch Camera",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Mic Toggle
                            IconButton(
                                onClick = {
                                    isMicMuted = !isMicMuted
                                    webRtcManager.toggleMic(!isMicMuted)
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (isMicMuted) VibeRed else Color(0x88281D35))
                            ) {
                                Icon(
                                    imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = "Toggle Mic",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Leave / End Call
                            IconButton(
                                onClick = { leaveAndSearchNext() },
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(VibeRed)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CallEnd,
                                    contentDescription = "End call",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Swipe hint overlay at top center
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x55000000))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Swipe,
                            contentDescription = null,
                            tint = Color(0xCCFFFFFF),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Swipe horizontally to match next user",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color(0xCCFFFFFF),
                                fontSize = 11.sp
                            )
                        )
                    }

                    // 4. Bottom Section: Chat Overlay + 5 Emoji Reaction Buttons
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .imePadding()
                            .navigationBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        // Chat Message List (Translucent overlay)
                        if (chatExpanded) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0x33000000))
                                    .padding(8.dp)
                            ) {
                                LazyColumn(
                                    state = chatListState,
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(messages) { msg ->
                                        val isMe = msg.senderId == currentUser.uid
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(
                                                        if (isMe) Color(0xBBFF0055) else Color(0xBB1E1528)
                                                    )
                                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                            ) {
                                                Text(
                                                    text = "${if (isMe) "You" else msg.senderName}: ${msg.text}",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        color = Color.White,
                                                        fontSize = 12.sp
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Three dots typing indicator if opponent is typing
                        AnimatedVisibility(
                            visible = isPeerTyping,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            ThreeDotsTypingIndicator(
                                peerName = session.peerName,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Text input field with send button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0xCC1E1528))
                                .border(1.dp, Color(0x33FF2A6D), RoundedCornerShape(24.dp))
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = messageText,
                                onValueChange = {
                                    messageText = it
                                    matchingRepository.setTyping(session.roomId, currentUser.uid, it.isNotEmpty())
                                },
                                placeholder = {
                                    Text("Say something cool…", color = Color(0x88FFFFFF), fontSize = 13.sp)
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        if (messageText.isNotBlank()) {
                                            coroutineScope.launch {
                                                matchingRepository.sendMessage(
                                                    roomId = session.roomId,
                                                    text = messageText,
                                                    senderId = currentUser.uid,
                                                    senderName = currentUser.name
                                                )
                                                matchingRepository.setTyping(session.roomId, currentUser.uid, false)
                                                messageText = ""
                                            }
                                        }
                                        focusManager.clearFocus()
                                    }
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    cursorColor = VibePink,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = {
                                    if (messageText.isNotBlank()) {
                                        coroutineScope.launch {
                                            matchingRepository.sendMessage(
                                                roomId = session.roomId,
                                                text = messageText,
                                                senderId = currentUser.uid,
                                                senderName = currentUser.name
                                            )
                                            matchingRepository.setTyping(session.roomId, currentUser.uid, false)
                                            messageText = ""
                                        }
                                    }
                                    focusManager.clearFocus()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = VibePink
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Exactly Five Emoji Reaction Buttons
                        val reactionEmojis = listOf("🔥", "❤️", "😂", "👏", "🚀")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xAA120E18))
                                .border(1.dp, Color(0x33FF2A6D), RoundedCornerShape(20.dp))
                                .padding(vertical = 4.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            reactionEmojis.forEach { emoji ->
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x33FFFFFF))
                                        .clickable {
                                            coroutineScope.launch {
                                                matchingRepository.sendReaction(
                                                    roomId = session.roomId,
                                                    emoji = emoji,
                                                    myUid = currentUser.uid
                                                )
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = emoji,
                                        fontSize = 24.sp
                                    )
                                }
                            }
                        }
                    }

                    // Full-screen burst animation when any user triggers a reaction
                    FullscreenEmojiBurst(reaction = activeReaction) {
                        activeReaction = null
                    }
                }

                is MatchingState.Error -> {
                    val err = (matchingState as MatchingState.Error).message
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                text = "Connection Issue",
                                style = MaterialTheme.typography.titleLarge.copy(color = VibeRed)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = err,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = { startSearching() },
                                colors = ButtonDefaults.buttonColors(containerColor = VibePink)
                            ) {
                                Text("Retry Matching")
                            }
                        }
                    }
                }
            }
        }
    }
}
