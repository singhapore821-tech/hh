package com.example.webrtc

import android.content.Context
import android.util.Log
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class WebRtcManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "VibeWebRtc"
    }

    private val database: FirebaseDatabase?
        get() = try {
            FirebaseDatabase.getInstance()
        } catch (e: Exception) {
            null
        }

    val eglBase: EglBase = EglBase.create()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var remoteVideoTrack: VideoTrack? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    private var currentRoomId: String? = null
    private var myUid: String? = null
    private var peerUid: String? = null

    private var offerListener: ValueEventListener? = null
    private var answerListener: ValueEventListener? = null
    private var candidatesListener: ChildEventListener? = null

    var onConnectionStateChange: ((PeerConnection.PeerConnectionState) -> Unit)? = null

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun initLocalMedia(localView: SurfaceViewRenderer) {
        this.localRenderer = localView
        localView.init(eglBase.eglBaseContext, null)
        localView.setMirror(true)
        localView.setEnableHardwareScaler(true)

        val factory = peerConnectionFactory ?: return

        // Audio
        val audioConstraints = MediaConstraints()
        audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("VIBE_AUDIO_TRACK", audioSource)

        // Video Capturer
        videoCapturer = createCameraCapturer()
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(videoCapturer?.isScreencast ?: false)

        videoCapturer?.let { capturer ->
            surfaceTextureHelper?.let { helper ->
                capturer.initialize(helper, context, videoSource?.capturerObserver)
                try {
                    capturer.startCapture(640, 480, 30)
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting camera capture: ${e.message}")
                }
            }
        }

        localVideoTrack = factory.createVideoTrack("VIBE_VIDEO_TRACK", videoSource)
        localVideoTrack?.addSink(localView)
    }

    fun initRemoteView(remoteView: SurfaceViewRenderer) {
        this.remoteRenderer = remoteView
        remoteView.init(eglBase.eglBaseContext, null)
        remoteView.setMirror(false)
        remoteView.setEnableHardwareScaler(true)
        remoteVideoTrack?.addSink(remoteView)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Prefer front camera for selfie video
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) return capturer
            }
        }

        // Fallback to back camera
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) return capturer
            }
        }
        return null
    }

    fun startCall(roomId: String, myUid: String, peerUid: String, isInitiator: Boolean) {
        this.currentRoomId = roomId
        this.myUid = myUid
        this.peerUid = peerUid

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pcObserver = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                // Send ICE candidate to Firebase
                val candidateRef = database?.getReference("rooms")
                    ?.child(roomId)
                    ?.child("signaling")
                    ?.child("candidates")
                    ?.child(myUid)
                    ?.push()

                val data = hashMapOf(
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "sdp" to candidate.sdp
                )
                candidateRef?.setValue(data)
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    remoteRenderer?.let { track.addSink(it) }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "WebRTC connection state changed: $newState")
                onConnectionStateChange?.invoke(newState)
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {
                if (stream != null && stream.videoTracks.isNotEmpty()) {
                    val track = stream.videoTracks[0]
                    remoteVideoTrack = track
                    remoteRenderer?.let { track.addSink(it) }
                }
            }
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, pcObserver)

        // Add local tracks
        localVideoTrack?.let { peerConnection?.addTrack(it) }
        localAudioTrack?.let { peerConnection?.addTrack(it) }

        // Listen for peer's ICE candidates
        listenForIceCandidates(roomId, peerUid)

        if (isInitiator) {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }

            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            // Publish offer
                            val offerData = hashMapOf(
                                "type" to desc.type.canonicalForm(),
                                "sdp" to desc.description,
                                "sender" to myUid
                            )
                            database?.getReference("rooms")?.child(roomId)?.child("signaling")?.child("offer")?.setValue(offerData)
                            listenForAnswer(roomId)
                        }
                        override fun onCreateFailure(err: String?) {}
                        override fun onSetFailure(err: String?) {}
                    }, desc)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(err: String?) {}
                override fun onSetFailure(err: String?) {}
            }, constraints)
        } else {
            listenForOffer(roomId)
        }
    }

    private fun listenForOffer(roomId: String) {
        val offerRef = database?.getReference("rooms")?.child(roomId)?.child("signaling")?.child("offer") ?: return
        offerListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                    val desc = SessionDescription(SessionDescription.Type.OFFER, sdp)

                    peerConnection?.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            val constraints = MediaConstraints()
                            peerConnection?.createAnswer(object : SdpObserver {
                                override fun onCreateSuccess(answerDesc: SessionDescription) {
                                    peerConnection?.setLocalDescription(object : SdpObserver {
                                        override fun onSetSuccess() {
                                            val answerData = hashMapOf(
                                                "type" to answerDesc.type.canonicalForm(),
                                                "sdp" to answerDesc.description,
                                                "sender" to myUid
                                            )
                                            database?.getReference("rooms")?.child(roomId)?.child("signaling")?.child("answer")?.setValue(answerData)
                                        }
                                        override fun onCreateSuccess(p0: SessionDescription?) {}
                                        override fun onCreateFailure(err: String?) {}
                                        override fun onSetFailure(err: String?) {}
                                    }, answerDesc)
                                }
                                override fun onSetSuccess() {}
                                override fun onCreateFailure(err: String?) {}
                                override fun onSetFailure(err: String?) {}
                            }, constraints)
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(err: String?) {}
                        override fun onSetFailure(err: String?) {}
                    }, desc)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        offerRef.addValueEventListener(offerListener as ValueEventListener)
    }

    private fun listenForAnswer(roomId: String) {
        val answerRef = database?.getReference("rooms")?.child(roomId)?.child("signaling")?.child("answer") ?: return
        answerListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                    val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                    peerConnection?.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Remote answer set successfully")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(err: String?) {}
                        override fun onSetFailure(err: String?) {}
                    }, desc)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        answerRef.addValueEventListener(answerListener as ValueEventListener)
    }

    private fun listenForIceCandidates(roomId: String, peerUid: String) {
        val candidatesRef = database?.getReference("rooms")
            ?.child(roomId)
            ?.child("signaling")
            ?.child("candidates")
            ?.child(peerUid) ?: return

        candidatesListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val sdpMid = snapshot.child("sdpMid").getValue(String::class.java) ?: ""
                val sdpMLineIndex = snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: 0
                val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return

                val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                peerConnection?.addIceCandidate(candidate)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        candidatesRef.addChildEventListener(candidatesListener as ChildEventListener)
    }

    fun toggleMic(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun toggleCamera(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun endCall() {
        try {
            peerConnection?.close()
            peerConnection = null
        } catch (ignored: Exception) {}

        currentRoomId?.let { rid ->
            offerListener?.let { database?.getReference("rooms")?.child(rid)?.child("signaling")?.child("offer")?.removeEventListener(it) }
            answerListener?.let { database?.getReference("rooms")?.child(rid)?.child("signaling")?.child("answer")?.removeEventListener(it) }
            peerUid?.let { pUid ->
                candidatesListener?.let {
                    database?.getReference("rooms")?.child(rid)?.child("signaling")?.child("candidates")?.child(pUid)?.removeEventListener(it)
                }
            }
        }
        remoteVideoTrack = null
        currentRoomId = null
    }

    fun release() {
        endCall()
        try {
            videoCapturer?.stopCapture()
        } catch (ignored: Exception) {}
        videoCapturer?.dispose()
        videoCapturer = null

        videoSource?.dispose()
        videoSource = null

        audioSource?.dispose()
        audioSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        try {
            localRenderer?.release()
            remoteRenderer?.release()
        } catch (ignored: Exception) {}
        localRenderer = null
        remoteRenderer = null

        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase.release()
    }
}
