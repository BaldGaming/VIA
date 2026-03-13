package com.example.via

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.content.Context
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.VibratorManager
import android.os.Build
import android.media.MediaPlayer
import android.content.SharedPreferences

class MainActivity : AppCompatActivity() {
    // TTS memory
    private var tts: TextToSpeech? = null

    // Media player & Shared preferences memory
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var prefs: SharedPreferences

    // The stack for previous files (A)
    private val previousAudioStack = mutableListOf<Int>()

    // The current queue (B)
    private val audioQueue = mutableListOf(R.raw.i_need_a_hero, R.raw.illegal, R.raw.israel_anthem)
    private var currentAudioIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Opens the app's private save file ("via_prefs") to remember things like audio timestamps
        prefs = getSharedPreferences("via_prefs", Context.MODE_PRIVATE)

        // Loads the last saved audio file index if exists. Else, defaults to the first file.
        currentAudioIndex = prefs.getInt("last_active_index", 0)

        // Initializes the TTS engine
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Sets language to Hebrew
                val result = tts?.setLanguage(Locale("he"))

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "TTS Error? *PLACEHOLDER*")
                }
            }
        }

        // Buttons
        val playBtn = findViewById<Button>(R.id.button1) // Play\Pause\Marked as heard.
        val titleBtn = findViewById<Button>(R.id.button) // Title read\Buttons read.
        val forwardBtn = findViewById<Button>(R.id.button3) // Forward 10 seconds.
        val rewindBtn = findViewById<Button>(R.id.button2) // Rewind 10 seconds.
        val nextBtn = findViewById<Button>(R.id.button5) // Next audio file.
        val previousBtn = findViewById<Button>(R.id.button4) // Previous audio file.

        /**
         * Play logic
         */
        playBtn.setOnClickListener { // tap
            Log.d("Button", "Green button tapped")
            vibrate()

            // Stops the TTS if it's running
            tts?.stop()

            val currentPlayer = mediaPlayer

            // If playing, pause it and save position
            if (currentPlayer != null && currentPlayer.isPlaying) {
                pauseAudio()
                Log.d("Audio", "Audio paused")
            }

            // If already exists but paused, just resume
            else if (currentPlayer != null && !currentPlayer.isPlaying) {
                currentPlayer.start()
                Log.d("Audio", "Resumed audio")
            }

            // If no player exists at all, create and play
            else {
                playAudio(audioQueue[currentAudioIndex])
                Log.d("Audio", "Audio started")
            }
        }
        playBtn.setOnLongClickListener { // long press (about 500ms)
            // TODO: add long press functionality that marks the audio file as "heard"
            Log.d("Button", "Green button held")
            vibrate()

            // Stops the TTS if it's running
            tts?.stop()

            true
        }


        /**
         * Title logic
         */
        titleBtn.setOnClickListener { // tap
            Log.d("Button", "Red button tapped")
            vibrate()

            // Stops the TTS if it's running
            tts?.stop()

            speak("שם הקובץ הינו")
            titleRead()
        }

        titleBtn.setOnLongClickListener { // long press (about 500ms)
            Log.d("Button", "Red button held")
            vibrate()

            // Stops the TTS if it's running
            tts?.stop()

            speak("כפתור ירוק: הקשה קצרה תתחיל ותפסיק את השמע.")
            speak("כפתור ירוק: הקשה ארוכה תסמן את השמע כ'הושלם'.")
            speak("כפתור אדום: הקשה קצרה תשמיע את הכותרת.")
            speak("כפתור אדום: הקשה ארוכה תקריא את כל הכפתורים.")
            speak("כפתור כחול: הקשה תדלג עשר שניות קדימה.")
            speak("כפתור צהוב: הקשה תחזור עשר שניות אחורה.")
            speak("כפתור לבן: הקשה תחזור לשמע הקודם.")
            speak("כפתור סגול: הקשה תעבור לשמע הבא.")
            true
        }


        /**
         * Forward logic
         */
        forwardBtn.setOnClickListener {
            Log.d("Button", "Blue button tapped")
            vibrate()

            mediaPlayer?.let { player ->
                // Shifts the current position forwards by 10 seconds
                val newPos = player.currentPosition + 10000

                // Makes sure it doesn't seek past the end of the audio file
                if (newPos < player.duration) {
                    player.seekTo(newPos)
                }
            }
        }


        /**
         * Rewind logic
         */
        rewindBtn.setOnClickListener {
            Log.d("Button", "Yellow button tapped")
            vibrate()

            // Shifts the current position backwards by 10 seconds
            mediaPlayer?.let { player ->
                // Uses coerceAtLeast(0) to ensure we don't go negative
                val newPos = (player.currentPosition - 10000).coerceAtLeast(0)
                player.seekTo(newPos)
            }
        }

        rewindBtn.setOnLongClickListener {
            Log.d("Button", "Yellow button held")
            vibrate()

            mediaPlayer?.let { player ->
                // Shifts the current position to the start
                player.seekTo(0)

                // Updates the SharedPreferences to reflect this
                val audioId = audioQueue[currentAudioIndex]
                prefs.edit().putInt("last_pos_$audioId", 0).apply()
            }
            true
        }


        /**
         * Next logic
         */
        nextBtn.setOnClickListener {
            Log.d("Button", "Purple button tapped")
            vibrate()

            // Stops the TTS if it's running
            tts?.stop()

            // Checks if we are not at last audio file
            if (currentAudioIndex < audioQueue.size - 1) {

                // Save progress and pause the current audio before switching
                pauseAudio()

                // Saves the current audio file to the stack before leaving it
                previousAudioStack.add(audioQueue[currentAudioIndex])

                // Increments index by 1
                currentAudioIndex++

                // Ejects the old audio file
                mediaPlayer?.release()
                mediaPlayer = null

                speak("שם הקובץ הינו")
                titleRead()

            } else {
                speak("הגעת לסוף הרשימה")
                Log.d("Audio", "End of playlist reached")
            }
        }


        /**
         * Previous logic
         */
        previousBtn.setOnClickListener {
            Log.d("Button", "White button tapped")
            vibrate()

            // Stops the TTS if it's running
            tts?.stop()

            // Checks if we are not at last audio file
            if (currentAudioIndex > 0) {
                previous()
                speak("שם הקובץ הינו")
                titleRead()
            } else {
                speak("הגעת לתחילת הרשימה")
                Log.d("Audio", "Start of playlist reached")
            }
        }
    }

    // TTS function
    private fun speak(text: String, id: String = "") {
        // Stops the audio file before talking so the user can hear the instructions
        if (mediaPlayer?.isPlaying == true)
            pauseAudio()

        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    // Function that reads the title of the current audio file
    private fun titleRead(id: String = "") {
        // Gets the current ID number
        val currentId = audioQueue[currentAudioIndex]

        // Translates the ID into a raw file name
        val fileName = resources.getResourceEntryName(currentId)

        // Reads the file name
        speak(fileName, id)
    }

    // TTS cleanup function when app stops
    override fun onDestroy() {
        if (tts != null) {
            tts?.stop()
            tts?.shutdown()
        }
        super.onDestroy()
    }

    // Function that increases the heptic feedback
    private fun vibrate() {
        // Gets the vibrator service
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Create an effect: (Duration in ms, Amplitude 1-255)
        val effect = VibrationEffect.createOneShot(150, 255)
        vibrator.vibrate(effect)
    }

    // Function that handles playing audio.
    private fun playAudio(audioResId: Int) {
        // Releases an old player if it exists
        mediaPlayer?.release()

        // Initializes new player
        mediaPlayer = MediaPlayer.create(this, audioResId)

        // Pulls saved time from SharedPreferences
        val savedPosition = prefs.getInt("last_pos_$audioResId", 0)

        // Seeks the audio to the saved time if it exists
        mediaPlayer?.seekTo(savedPosition)

        // Starts audio
        mediaPlayer?.start()
    }

    // Function that handles pausing audio.
    private fun pauseAudio() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                // Gets the audio id
                val audioId = audioQueue[currentAudioIndex]

                // Gets the current position
                val rawPosition = player.currentPosition

                // Subtract 3 seconds
                val adjustedPosition = if (rawPosition > 3000) {
                    rawPosition - 3000
                } else {
                    0
                }

                // Save position and current index to SharedPreferences
                prefs.edit().putInt("last_pos_$audioId", adjustedPosition).apply()
                prefs.edit().putInt("last_active_index", currentAudioIndex).apply()

                // Pauses
                player.pause()
                Log.d("Audio", "Paused at $rawPosition, saved as $adjustedPosition")
            }
        }
    }

    // Function that handles going back to previous audio.
    private fun previous() {
        // Checks if the previous stack not empty
        if (previousAudioStack.isNotEmpty()) {

            //
            pauseAudio()

            // Pulls newest audio file from the stack and decrements the size of the stack by 1
            val lastId = previousAudioStack.removeAt(previousAudioStack.size - 1)

            // Sets the current index to be the previous file
            currentAudioIndex = audioQueue.indexOf(lastId)

            // Ejects the old audio file
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    // Function that gets triggered automatically by Android the
    // exact moment the app is completely hidden from the user's screen.
    override fun onStop() {
        super.onStop()

        // Save progress whenever app is hidden
        pauseAudio()
    } // This function essentially acts as a guard against closing the app before pausing the audio file
}