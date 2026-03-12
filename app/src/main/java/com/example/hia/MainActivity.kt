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
    private var media_player: MediaPlayer? = null
    private lateinit var prefs: SharedPreferences

    // The stack for previous files (A)
    private val previous_audio_stack = mutableListOf<Int>()

    // The current queue (B)
    private val audio_queue = mutableListOf(R.raw.i_need_a_hero, R.raw.illegal, R.raw.israel_anthem)
    private var current_audio_index = 0

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
        val play_btn = findViewById<Button>(R.id.button1) // Play\Pause\Marked as heard.
        val title_btn = findViewById<Button>(R.id.button) // Title read\Buttons read.
        val forward_btn = findViewById<Button>(R.id.button3) // Forward 10 seconds.
        val rewind_btn = findViewById<Button>(R.id.button2) // Rewind 10 seconds.
        val next_btn = findViewById<Button>(R.id.button5) // Next audio file.
        val previous_btn = findViewById<Button>(R.id.button4) // Previous audio file.

        /**
         * Play logic
         */
        play_btn.setOnClickListener { // tap
            Log.d("Button", "Green button tapped")
            vibrate()

            // Stops the TTS if it's running
            tts?.stop()

            val current_player = media_player

            // If playing, pause it and save position
            if (current_player != null && current_player.isPlaying) {
                pause_audio()
                Log.d("Audio", "Audio paused")
            }

            // If already exists but paused, just resume
            else if (current_player != null && !current_player.isPlaying) {
                current_player.start()
                Log.d("Audio", "Resumed audio")
            }

            // If no player exists at all, create and play
            else {
                play_audio(audio_queue[current_audio_index])
                Log.d("Audio", "Audio started")
            }
        }
        play_btn.setOnLongClickListener { // long press (about 500ms)
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
        title_btn.setOnClickListener { // tap
            Log.d("Button", "Red button tapped")
            vibrate()

            // Stops the TTS if it's running
            tts?.stop()

            speak("שם הקובץ הינו")
            title_read()
        }

        title_btn.setOnLongClickListener { // long press (about 500ms)
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
        forward_btn.setOnClickListener {
            Log.d("Button", "Blue button tapped")
            vibrate()

            media_player?.let { player ->
                // Shifts the current position forwards by 10 seconds
                val new_pos = player.currentPosition + 10000

                // Makes sure it doesn't seek past the end of the audio file
                if (new_pos < player.duration) {
                    player.seekTo(new_pos)
                }
            }
        }


        /**
         * Rewind logic
         */
        rewind_btn.setOnClickListener {
            Log.d("Button", "Yellow button tapped")
            vibrate()

            // Shifts the current position backwards by 10 seconds
            media_player?.let { player ->
                // Uses coerceAtLeast(0) to ensure we don't go negative
                val new_pos = (player.currentPosition - 10000).coerceAtLeast(0)
                player.seekTo(new_pos)
            }
        }

        rewind_btn.setOnLongClickListener {
            Log.d("Button", "Yellow button held")
            vibrate()

            media_player?.let { player ->
                // Shifts the current position to the start
                player.seekTo(0)

                // Updates the SharedPreferences to reflect this
                val audio_id = audio_queue[current_audio_index]
                prefs.edit().putInt("last_pos_$audio_id", 0).apply()
            }
            true
        }


        /**
         * Next logic
         */
        next_btn.setOnClickListener {
            Log.d("Button", "Purple button tapped")
            vibrate()

            // Stops the TTS if it's running
            tts?.stop()

            // Checks if we are not at last audio file
            if (current_audio_index < audio_queue.size - 1) {

                // Save progress and pause the current audio before switching
                pause_audio()

                // Saves the current audio file to the stack before leaving it
                previous_audio_stack.add(audio_queue[current_audio_index])

                // Increments index by 1
                current_audio_index++

                // Ejects the old audio file
                media_player?.release()
                media_player = null

                speak("שם הקובץ הינו")
                title_read()

            } else {
                speak("הגעת לסוף הרשימה")
                Log.d("Audio", "End of playlist reached")
            }
        }


        /**
         * Previous logic
         */
        previous_btn.setOnClickListener {
            Log.d("Button", "White button tapped")
            vibrate()

            // Stops the TTS if it's running
            tts?.stop()

            // Checks if we are not at last audio file
            if (current_audio_index > 0) {
                previous()
                speak("שם הקובץ הינו")
                title_read()
            } else {
                speak("הגעת לתחילת הרשימה")
                Log.d("Audio", "Start of playlist reached")
            }
        }
    }

    // TTS function
    private fun speak(text: String, id: String = "") {
        // Stops the audio file before talking so the user can hear the instructions
        if (media_player?.isPlaying == true)
            pause_audio()

        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    // Function that reads the title of the current audio file
    private fun title_read(id: String = "") {
        // Gets the current ID number
        val current_id = audio_queue[current_audio_index]

        // Translates the ID into a raw file name
        val file_name = resources.getResourceEntryName(current_id)

        // Reads the file name
        speak(file_name, id)
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
    private fun play_audio(audio_res_id: Int) {
        // Releases an old player if it exists
        media_player?.release()

        // Initializes new player
        media_player = MediaPlayer.create(this, audio_res_id)

        // Pulls saved time from SharedPreferences
        val saved_position = prefs.getInt("last_pos_$audio_res_id", 0)

        // Seeks the audio to the saved time if it exists
        media_player?.seekTo(saved_position)

        // Starts audio
        media_player?.start()
    }

    // Function that handles pausing audio.
    private fun pause_audio() {
        media_player?.let { player ->
            if (player.isPlaying) {
                // Gets the audio id
                val audio_id = audio_queue[current_audio_index]

                // Gets the current position
                val raw_position = player.currentPosition

                // Subtract 3 seconds
                val adjusted_position = if (raw_position > 3000) {
                    raw_position - 3000
                } else {
                    0
                }

                // Save position to SharedPreferences
                prefs.edit().putInt("last_pos_$audio_id", adjusted_position).apply()

                // Pauses
                player.pause()
                Log.d("Audio", "Paused at $raw_position, saved as $adjusted_position")
            }
        }
    }

    // Function that handles going back to previous audio.
    private fun previous() {
        // Checks if the previous stack not empty
        if (previous_audio_stack.isNotEmpty()) {

            //
            pause_audio()

            // Pulls newest audio file from the stack and decrements the size of the stack by 1
            val last_id = previous_audio_stack.removeAt(previous_audio_stack.size - 1)

            // Sets the current index to be the previous file
            current_audio_index = audio_queue.indexOf(last_id)

            // Ejects the old audio file
            media_player?.release()
            media_player = null
        }
    }

    // Function that gets triggered automatically by Android the
    // exact moment the app is completely hidden from the user's screen.
    override fun onStop() {
        super.onStop()

        // Save progress whenever app is hidden
        pause_audio()
    } // This function essentially acts as a guard against closing the app before pausing the audio file
}