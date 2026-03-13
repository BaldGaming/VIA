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
    private val audio_queue = mutableListOf<CloudSong>()
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

        // Loads the last saved audio file index if exists. Else, defaults to the first file.
        current_audio_index = prefs.getInt("last_active_index", 0)

        // Initializes the TTS engine
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Sets language to Hebrew
                val result = tts?.setLanguage(Locale.forLanguageTag("he"))

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "TTS Error? *PLACEHOLDER*")
                }
            }
        }

        // Starts the cloud sync
        fetch_audio_files()
        Log.d("Audio", "Cloud sync started")


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
                previous_audio_stack.add(current_audio_index)

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

        if (audio_queue.isNotEmpty())
            speak(audio_queue[current_audio_index].title, id)
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
    private fun play_audio(song: CloudSong) {
        media_player?.release()

        // Creates the player manually for URLs
        media_player = MediaPlayer().apply {
            setDataSource(song.url)

            // Cloud files need to buffer before they can play
            prepareAsync()

            setOnPreparedListener {
                // Seek to saved position if it exists
                val saved_pos = prefs.getInt("last_pos_${song.title}", 0)
                seekTo(saved_pos)
                start()
                Log.d("Audio", "Playing: ${song.title}")
            }
        }
    }

    // Function that handles pausing audio.
    private fun pause_audio() {
        media_player?.let { player ->
            if (player.isPlaying && audio_queue.isNotEmpty()) {
                val current_song = audio_queue[current_audio_index]
                val raw_position = player.currentPosition

                // Your 3-second recovery logic
                val adjusted_position = if (raw_position > 3000) raw_position - 3000 else 0

                // Save using the title as the key
                prefs.edit().putInt("last_pos_${current_song.title}", adjusted_position).apply()
                prefs.edit().putInt("last_active_index", current_audio_index).apply()

                player.pause()
            }
        }
    }

    // Function that handles going back to previous audio.
    private fun previous() {
        // Checks if the previous stack not empty
        if (previous_audio_stack.isNotEmpty()) {
            pause_audio()

            // Pulls the last index from the stack and updates index
            current_audio_index = previous_audio_stack.removeAt(previous_audio_stack.size - 1)

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

    // Function for fetching audio files from Firebase
    private fun fetch_audio_files() {
        val database = com.google.firebase.database.FirebaseDatabase.getInstance()
        val myRef = database.getReference("audio_files")

        // Listen for the data to arrive from the cloud
        myRef.get().addOnSuccessListener { snapshot ->
            // Clears the "Loading..." state
            audio_queue.clear()

            // Loops through everything Firebase sent back
            for (song_snapshot in snapshot.children) {
                val song = song_snapshot.getValue(CloudSong::class.java)
                if (song != null) {
                    audio_queue.add(song)
                    Log.d("Firebase", "Loaded: ${song.title}")
                }
            }

//            // Now that the queue is full, we can tell the user we're ready
//            if (audio_queue.isNotEmpty()) {
//                speak("הרשימה עודכנה. ישנם ${audio_queue.size} קבצים.")
//            }

        }.addOnFailureListener { // If something goes wrong...
            Log.e("Firebase", "Error fetching songs", it)
        }
    }
}

// A data container for the Firebase audio files
data class CloudSong(
    val title: String = "",
    val url: String = ""
)