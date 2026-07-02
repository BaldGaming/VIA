package com.example.via

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {

    // Initiate player and mediaSession
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    // Builder
    override fun onCreate() {
        super.onCreate()

        // Tells the OS this is a dedicated music player
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Build the engine with the strict Wake Modes included
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .build()

        // Build the bridge to the OS
        mediaSession = MediaSession.Builder(this, player!!).build()
    }

    // Function that releases the player and the session to prevent memory leaks
    override fun onDestroy() {
        player?.release()
        mediaSession?.release()
        super.onDestroy()
    }
}