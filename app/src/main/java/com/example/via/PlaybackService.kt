package com.example.via

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import timber.log.Timber

class PlaybackService : MediaSessionService() {

    // Initiate player and mediaSession
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    // Builder
    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Timber.d("VIA_Service: PlaybackService onCreate")

        // Tells the OS this is a dedicated music player
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Build the engine with the strict Wake Modes included
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .setSkipSilenceEnabled(true) // This skips over large parts of complete silence in audiobooks. I have no idea how this works. (true / false)
            .build()

        player = exoPlayer

        // Build the bridge to the OS
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    // Function that releases the player and the session to prevent memory leaks
    override fun onDestroy() {
        Timber.d("VIA_Service: PlaybackService onDestroy")
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}