package com.example.via

import android.os.Bundle // Passes the saved state when the app screen is created.
import android.util.Log // Prints debugging messages to the Logcat console.
import android.widget.Button // Hooks up the UI buttons (Play, Rewind, etc.).
import androidx.activity.enableEdgeToEdge // Lets the app draw behind the status and navigation bars.
import androidx.appcompat.app.AppCompatActivity // Acts as the base class for the main screen (MainActivity).
import androidx.core.view.ViewCompat // Applies the window insets so UI doesn't overlap system bars.
import androidx.core.view.WindowInsetsCompat // Measures the exact size of the system bars.
import android.content.Context // Accesses system-level services (Preferences, Vibrator, Power).
import android.os.Vibrator // Triggers haptic feedback on older Android versions.
import android.os.VibrationEffect // Defines the exact strength and length of the vibration.
import android.os.VibratorManager // Triggers haptic feedback on newer Android versions (Android 12+).
import android.os.Build // Checks the device's Android version to pick the right vibrator service.
import android.media.MediaPlayer // Streams and plays the actual audio files.
import android.content.SharedPreferences // Saves simple data locally (timestamps, last played song).
import android.os.PowerManager // Controls the WakeLock to prevent the CPU from sleeping during playback.
import retrofit2.Retrofit // The main tool to handle network requests to the Dropbox API.
import retrofit2.converter.gson.GsonConverterFactory // Translates Dropbox's JSON text into Kotlin objects.
import androidx.lifecycle.lifecycleScope // Runs background API tasks safely without crashing the UI.
import kotlinx.coroutines.launch // Actually starts the background tasks (coroutines).
import android.widget.Toast // Shows the little pop-up message for the double-tap exit.
import androidx.activity.OnBackPressedCallback // Handles the modern system back-button gestures safely.
import androidx.core.content.edit // Simplifies saving data to SharedPreferences (KTX extension).
import okhttp3.MediaType.Companion.toMediaType // Converts strings to MediaType for Retrofit.
import okhttp3.RequestBody.Companion.toRequestBody // Converts strings to RequestBody for Retrofit.
import java.io.File // Handles creating the temporary audio file.
import java.io.FileOutputStream // Handles writing the audio bytes to the file.
import com.example.via.BuildConfig // Exposes the Azure keys from local.properties.
import android.speech.tts.TextToSpeech // The fallback voice engine for offline use.
import java.util.Locale // Sets the fallback TTS language specifically to Hebrew.

// Song data class
data class AudioFile(val title: String, val path: String)

class MainActivity : AppCompatActivity() {

    // Voice player memory
    private var voicePlayer: MediaPlayer? = null
    private var fallbackTts: TextToSpeech? = null
    private var ttsJob: kotlinx.coroutines.Job? = null
    private var progressJob: kotlinx.coroutines.Job? = null // Tracks playback progress to auto-mark files

    // Media player & Shared preferences memory
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var prefs: SharedPreferences

    // The current queue
    private var audioQueue = mutableListOf<AudioFile>()
    private var currentAudioIndex = 0

    // PowerManager instance
    private var wakeLock: PowerManager.WakeLock? = null

    // Memory for the double-tap exit logic
    private var pressedTime: Long = 0

    // Tracks if the music should start after the current TTS ends
    private var shouldAutoPlayNext = false

    // Initializes the main activity when the app launches
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Initializes the offline fallback TTS engine
        fallbackTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                fallbackTts?.setLanguage(Locale.forLanguageTag("he"))

                // Listens to exactly when the fallback voice starts and stops
                fallbackTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        keepScreenAwake(true)
                    }
                    override fun onDone(utteranceId: String?) {
                        keepScreenAwake(false)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        keepScreenAwake(false)
                    }
                })
            }
        }

        // Handles the system back button for the double-tap to exit feature (Android 13+ standard)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Checks if the back button was pressed twice within 2 seconds
                if (pressedTime + 2000 > System.currentTimeMillis()) {
                    finish() // Closes the app completely
                } else {
                    // Notifies the user to press again
                    Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                }
                // Updates the memory to the time of the latest press
                pressedTime = System.currentTimeMillis()
            }
        })

        // Adjusts UI to fit system bars (status bar, navigation bar) so it doesn't overlap
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Opens the app's private save file ("via_prefs") to remember things like audio timestamps
        prefs = getSharedPreferences("via_prefs", Context.MODE_PRIVATE)

        // Loads the last saved audio file index if exists. Else, defaults to the first file.
        currentAudioIndex = prefs.getInt("last_active_index", 0)

        // Buttons
        val playBtn = findViewById<Button>(R.id.button1) // Play / Pause / Marked as heard.
        val titleBtn = findViewById<Button>(R.id.button) // Title read / Buttons read.
        val forwardBtn = findViewById<Button>(R.id.button3) // Forward 10 seconds.
        val rewindBtn = findViewById<Button>(R.id.button2) // Rewind 10 seconds / Goto start of file.
        val nextBtn = findViewById<Button>(R.id.button5) // Next / Last audio file.
        val previousBtn = findViewById<Button>(R.id.button4) // Previous / First audio file.

        // Wakelock object
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        // A unique tag for identification
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VIA::WakeLockTag")

        // Builder for RetroFit (the tool that talks to the Dropbox API)
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.dropboxapi.com/") // The API endpoint
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        // API instance
        val apiService = retrofit.create(ApiService::class.java)

        /**
         * Play logic
         */
        playBtn.setOnClickListener { // tap
            Log.d("VIA_Button", "Play/Pause tapped")
            vibrate()

            // Cancels active TTS requests
            ttsJob?.cancel()

            // Stops the TTS if it's running
            voicePlayer?.release()
            voicePlayer = null
            fallbackTts?.stop()

            // Waits until playlist is downloaded
            if (audioQueue.isEmpty()) {
                speak("רשימת ההקובץה עדיין בטעינה, אנא המתן")
                Log.w("VIA_Audio", "Playback blocked: audioQueue is empty")
                return@setOnClickListener
            }

            // Gets the current media player instance
            val currentPlayer = mediaPlayer

            // Pauses the player and saves position if already playing
            if (currentPlayer != null && currentPlayer.isPlaying) {
                pauseAudio()
                Log.d("VIA_Audio", "Audio paused manually")
            }

            // Resumes the player if already exists but paused
            else if (currentPlayer != null && !currentPlayer.isPlaying) {
                currentPlayer.start()
                keepScreenAwake(true) // Forces screen on when resumed

                // Starts wakeLock with a 4-hour timeout (14400000ms) to save battery if forgotten
                if (wakeLock?.isHeld == false)
                    wakeLock?.acquire(14400000L)
                Log.d("VIA_Audio", "Audio resumed manually")
            }

            // Creates and plays audio if no player exists at all
            else {
                startPlaybackWorkflow(apiService)
                // Starts wakeLock with a 4-hour timeout (14400000ms) to save battery if forgotten
                if (wakeLock?.isHeld == false) wakeLock?.acquire(14400000L)
                Log.d("VIA_Audio", "Audio started workflow")
            }
        }

        playBtn.setOnLongClickListener { // long press (about 500ms)
            Log.d("VIA_Button", "Play/Pause held")
            vibrate()

            // Cancels active TTS requests
            ttsJob?.cancel()

            // Stops the TTS if it's running
            voicePlayer?.release()
            voicePlayer = null
            fallbackTts?.stop()

            // Toggles the heard status of the current file
            if (audioQueue.isNotEmpty()) {
                val currentPath = audioQueue[currentAudioIndex].path
                val isCurrentlyHeard = prefs.getBoolean("heard_$currentPath", false)
                val newHeardState = !isCurrentlyHeard

                prefs.edit { putBoolean("heard_$currentPath", newHeardState) }

                if (newHeardState) {
                    speak("סומן כהושלם")
                } else {
                    speak("הסימון הוסר")
                }
            }

            true
        }


        /**
         * Title logic
         */
        titleBtn.setOnClickListener { // tap
            Log.d("VIA_Button", "Title tapped")
            vibrate()
            readCurrentTitle()
        }

        titleBtn.setOnLongClickListener { // long press (about 500ms)
            Log.d("VIA_Button", "Title held")
            vibrate()

            speak("כפתור ירוק: לחיצה קצרה תתחיל ותפסיק את הקובץ. כפתור ירוק: לחיצה ארוכה תסמן את הקובץ כהושלם. כפתור אדום: לחיצה קצרה תשמיע את הכותרת. כפתור אדום: לחיצה ארוכה תקריא את כל הכפתורים. כפתור כחול: לחיצה תדלג עשר שניות קדימה. כפתור צהוב: לחיצה תחזור עשר שניות אחורה. כפתור צהוב: לחיצה ארוכה תחזור לתחילת הקובץ. כפתור ורוד: לחיצה תעבור לקובץ הבא. כפתור ורוד: לחיצה ארוכה תעבור לקובץ הבא שלא הושמע. כפתור לבן: לחיצה תחזור לקובץ הקודם. כפתור לבן: לחיצה ארוכה תעבור לתחילת רשימת הקבצים... עבור הסברים נוספים, תפנה לירדן.")
            true
        }



        /**
         * Forward logic
         */
        forwardBtn.setOnClickListener { // tap
            Log.d("VIA_Button", "Forward tapped")
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
        rewindBtn.setOnClickListener { // tap
            Log.d("VIA_Button", "Rewind tapped")
            vibrate()

            // Shifts the current position backwards by 10 seconds
            mediaPlayer?.let { player ->
                // Uses coerceAtLeast(0) to ensure we don't go negative
                val newPos = (player.currentPosition - 10000).coerceAtLeast(0)
                player.seekTo(newPos)
            }
        }

        rewindBtn.setOnLongClickListener { // long press (about 500ms)
            Log.d("VIA_Button", "Rewind held")
            vibrate()

            mediaPlayer?.let { player ->
                // Shifts the current position to the start of the current audio file
                player.seekTo(0)

                // Updates the SharedPreferences to reflect this reset
                val audioPath = audioQueue[currentAudioIndex].path
                prefs.edit { putInt("last_pos_$audioPath", 0) }
            }
            speak("חזרתא לתחילת הקובץ.")
            true
        }


        /**
         * Next logic
         */
        nextBtn.setOnClickListener { // tap
            Log.d("VIA_Button", "Next tapped")
            vibrate()

            // Checks if we are not at the last audio file
            if (currentAudioIndex < audioQueue.size - 1) {

                // Pauses and saves progress of the current audio before switching
                pauseAudio()

                // Increments index by 1
                currentAudioIndex++

                // Ejects the old audio file
                mediaPlayer?.release()
                mediaPlayer = null

                readCurrentTitle()

            } else {
                speak("הגעת לסוף הרשימה")
                Log.d("VIA_Audio", "End of playlist reached")
            }
        }

        nextBtn.setOnLongClickListener {
            Log.d("VIA_Button", "Next held")
            vibrate()

            var targetIndex = -1

            // Searches forward from the current position for the nearest unread file
            for (i in currentAudioIndex + 1 until audioQueue.size) {
                if (!prefs.getBoolean("heard_${audioQueue[i].path}", false)) {
                    targetIndex = i
                    break
                }
            }

            // Wraps around and searches from the beginning if no unread file is found ahead
            if (targetIndex == -1) {
                for (i in 0 until currentAudioIndex) {
                    if (!prefs.getBoolean("heard_${audioQueue[i].path}", false)) {
                        targetIndex = i
                        break
                    }
                }
            }

            // Handles the logic if an unread file was found anywhere in the list
            if (targetIndex != -1) {
                // Pauses and saves progress of the current audio before switching
                pauseAudio()

                // Shifts the audio index to the found unread file
                currentAudioIndex = targetIndex

                // Ejects the old audio file
                mediaPlayer?.release()
                mediaPlayer = null

                // Reads the new title aloud
                readCurrentTitle()
            } else {
                // Notifies the user if literally all files have been marked as heard
                speak("כל הקבצים סומנו כהושלמו")
                Log.d("VIA_Audio", "No unheard files found")
            }

            true
        }


        /**
         * Previous logic
         */
        previousBtn.setOnClickListener { // tap
            Log.d("VIA_Button", "Previous tapped")
            vibrate()

            // Checks if we are not at the first audio file
            if (currentAudioIndex > 0) {
                // Pauses and saves progress of the current audio
                pauseAudio()

                // Subtracts 1 from the index to go back
                currentAudioIndex--

                // Ejects the old audio file
                mediaPlayer?.release()
                mediaPlayer = null

                readCurrentTitle()

            } else {
                speak("הגעת לתחילת הרשימה")
                Log.d("VIA_Audio", "Start of playlist reached")
            }
        }

        previousBtn.setOnLongClickListener { // long press (about 500ms)
            Log.d("VIA_Button", "Previous held")
            vibrate()
            speak("חוזר לתחילת הרשימה")

            // Resets the audio index
            currentAudioIndex = 0
            true
        }
    }

    // Function that strips the file extension and leading numbers
    private fun getCleanTitle(rawTitle: String): String {
        return rawTitle
            .substringBeforeLast(".")
            .replace(Regex("_"), " .")
    }

    // Function that reads the title and appends the heard status
    private fun readCurrentTitle() {
        if (audioQueue.isEmpty()) {
            speak("רשימת הקבצים ריקה")
            return
        }

        val currentPath = audioQueue[currentAudioIndex].path
        val cleanTitle = getCleanTitle(audioQueue[currentAudioIndex].title)
        val isHeard = prefs.getBoolean("heard_$currentPath", false)

        if (isHeard) {
            speak("שם הקובץ הינו $cleanTitle. כבר האזנת לקובץ זה.")
        } else {
            speak("שם הקובץ הינו $cleanTitle")
        }
    }

    // TTS function
    private fun speak(text: String) {
        // Cancels active TTS requests
        ttsJob?.cancel()

        // Stops the TTS if it's running and instantly lets the screen sleep
        voicePlayer?.release()
        voicePlayer = null
        fallbackTts?.stop()

        // Lets the screen sleep if a voice was interrupted
        keepScreenAwake(false)

        // Pauses the main music player
        if (mediaPlayer?.isPlaying == true) pauseAudio()

        // Creates a separate Retrofit instance for the Azure endpoint
        val azureRetrofit = Retrofit.Builder()
            .baseUrl("https://${BuildConfig.AZURE_TTS_REGION}.tts.speech.microsoft.com/cognitiveservices/")
            .build()
            .create(ApiService::class.java)

        // Formats the SSML request
        val ssmlText = """
            <speak version='1.0' xml:lang='he-IL'>
                <voice xml:lang='he-IL' xml:gender='Male' name='he-IL-AvriNeural'>
                    $text
                </voice>
            </speak>
        """.trimIndent()

        // Converts the string to a Retrofit request body
        val mediaType = "application/ssml+xml".toMediaType()
        val requestBody = ssmlText.toRequestBody(mediaType)

        // Starts a tracked coroutine for the API call
        ttsJob = lifecycleScope.launch {
            try {
                val response = azureRetrofit.getAzureTTS(
                    apiKey = BuildConfig.AZURE_TTS_KEY,
                    ssml = requestBody
                )

                if (response.isSuccessful) {
                    response.body()?.bytes()?.let { audioBytes ->
                        // Saves the voice stream to a temporary file
                        val tempVoiceFile = File(cacheDir, "temp_voice.wav")
                        FileOutputStream(tempVoiceFile).use { it.write(audioBytes) }

                        // Plays the voice
                        voicePlayer = MediaPlayer().apply {
                            setDataSource(tempVoiceFile.absolutePath)

                            setOnCompletionListener {
                                keepScreenAwake(false)

                                // Checks if the system is waiting to autoplay the next track
                                if (shouldAutoPlayNext) {
                                    shouldAutoPlayNext = false // Resets the flag
                                    findViewById<Button>(R.id.button1).performClick() // Clicks play
                                }
                            }

                            prepare()
                            keepScreenAwake(true)
                            start()
                        }
                    }
                } else {
                    Log.e("VIA_TTS", "Azure Error: ${response.code()} - ${response.message()}")
                    // Falls back to Android TTS if Azure rejects the request (Note the added "VIA_ID")
                    fallbackTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VIA_ID")
                }
            } catch (e: Exception) {
                // Ignores cancellation exceptions
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e("VIA_TTS", "Failed to connect to Azure: ${e.message}")
                    // Falls back to Android TTS if internet drops (Note the added "VIA_ID")
                    fallbackTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VIA_ID")
                }
            }
        }
    }

    // Cleans up memory and services when the app is completely destroyed
    override fun onDestroy() {
        ttsJob?.cancel()
        progressJob?.cancel() // Stops the background progress tracker
        voicePlayer?.release()
        mediaPlayer?.release()
        fallbackTts?.shutdown()
        super.onDestroy()
    }

    // Function that handles the haptic feedback (vibration)
    private fun vibrate() {
        // Gets the correct vibrator service depending on the Android version
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Creates an effect: (Duration in ms, Amplitude 1-255)
        val effect = VibrationEffect.createOneShot(150, 255)
        vibrator.vibrate(effect)
    }

    // Function that handles playing audio.
    private fun playAudio(url: String) {
        // Releases an old player if it exists
        mediaPlayer?.release()

        // Initializes a new player
        mediaPlayer = MediaPlayer().apply {

            // Tells the player to hold a wake lock while playing
            setWakeMode(this@MainActivity, PowerManager.PARTIAL_WAKE_LOCK)

            setDataSource(url)

            // Sets a listener to catch and report playback errors
            setOnErrorListener { _, what, extra ->
                Log.e("VIA_Audio", "MediaPlayer error: code=$what, extra=$extra")
                speak("שגיאה בהפעלת הקובץ")
                true
            }

            // Sets a listener to start playing only when buffering is done
            setOnPreparedListener { player ->
                val savedPosition = prefs.getInt("last_pos_${audioQueue[currentAudioIndex].path}", 0)
                player.seekTo(savedPosition)
                player.start()
                keepScreenAwake(true) // Forces screen on when new audio starts
                Log.d("VIA_Audio", "Playback ready and streaming")

                // Cancels any existing progress tracker
                progressJob?.cancel()

                // Starts a background loop to check if the file reached 98% completion
                progressJob = lifecycleScope.launch {
                    while (mediaPlayer == player) {
                        if (player.isPlaying && player.duration > 0) {
                            val progress = player.currentPosition.toFloat() / player.duration
                            if (progress >= 0.98f) {
                                val currentPath = audioQueue[currentAudioIndex].path
                                prefs.edit { putBoolean("heard_$currentPath", true) }
                                Log.d("VIA_Audio", "Auto-marked as heard at 98%")
                                break // Stops tracking once successfully marked
                            }
                        }
                        kotlinx.coroutines.delay(1000) // Checks every second
                    }
                }
            }

            // Triggers autoplay logic when a song finishes naturally
            setOnCompletionListener {
                keepScreenAwake(false)

                var targetIndex = -1

                // Searches forward for the nearest unread file
                for (i in currentAudioIndex + 1 until audioQueue.size) {
                    if (!prefs.getBoolean("heard_${audioQueue[i].path}", false)) {
                        targetIndex = i
                        break
                    }
                }

                // Attempts autoplay only if a fresh, unheard file was found ahead
                if (targetIndex != -1) {
                    Log.d("VIA_Audio", "Autoplay: Skips to next unheard file at index $targetIndex")

                    // Advances the index manually to the unread file
                    currentAudioIndex = targetIndex

                    // Clears out the old media player
                    mediaPlayer?.release()
                    mediaPlayer = null

                    // Prepares the clean transition speech
                    val cleanTitle = getCleanTitle(audioQueue[currentAudioIndex].title)
                    val text = "הקובץ הסתיים, עובר לקובץ הבא. שם הקובץ הינו $cleanTitle"

                    // Sets the flag to play the song immediately after speaking, and triggers speech
                    shouldAutoPlayNext = true
                    speak(text)
                } else {
                    Log.d("VIA_Audio", "Autoplay: Stopped. No unheard files ahead.")
                    // Autoplay naturally stops here.
                }
            }

            // Starts the background buffer
            prepareAsync()
        }
    }

    // Function that handles pausing audio.
    private fun pauseAudio() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                // Gets the audio path
                val audioPath = audioQueue[currentAudioIndex].path

                // Gets the current position
                val rawPosition = player.currentPosition

                // Subtracts 3 seconds so the user has a slight overlap when resuming
                val adjustedPosition = if (rawPosition > 3000) {
                    rawPosition - 3000
                } else {
                    0
                }

                // Saves position and current index to SharedPreferences cleanly
                prefs.edit {
                    putInt("last_pos_$audioPath", adjustedPosition)
                    putInt("last_active_index", currentAudioIndex)
                }

                // Pauses the player
                player.pause()
                keepScreenAwake(false) // Lets screen sleep when paused

                // Safely releases the CPU WakeLock so it doesn't drain the battery
                if (wakeLock?.isHeld == true) wakeLock?.release()

                Log.d("VIA_Audio", "Paused at $rawPosition ms, saved as $adjustedPosition ms")
            }
        }
    }


    // Function that handles fetching the direct streaming link and starting playback
    private fun startPlaybackWorkflow(apiService: ApiService) {
        lifecycleScope.launch {
            try {
                // Validates the token before fetching the link
                val token = DropboxAuth.getValidToken(apiService)
                if (token.isEmpty()) return@launch

                val currentFile = audioQueue[currentAudioIndex]

                // Asks Dropbox for a direct streaming link
                val linkResponse = apiService.getTemporaryLink(token, TempLinkRequest(currentFile.path))

                Log.d("VIA_Dropbox", "Streaming link fetched successfully")
                playAudio(linkResponse.link) // Now we play the valid link
            } catch (e: Exception) {
                Log.e("VIA_Dropbox", "Failed to fetch streaming link: ${e.message}")
                speak("שגיאה בהפעלת הקובץ")
            }
        }
    }

    // Refreshes the library of audio files
    private fun refreshLibrary(apiService: ApiService) {
        lifecycleScope.launch {
            try {
                // Validates the token before scanning the folder
                val token = DropboxAuth.getValidToken(apiService)
                if (token.isEmpty()) return@launch

                val response = apiService.listFolder(token, ListFolderArgs("/via_audio"))

                // Gets the count saved last time (defaults to 0 if it's the first time ever)
                val lastKnownCount = prefs.getInt("last_known_count", 0)

                // Clears the audio queue
                audioQueue.clear()

                response.entries.forEach { entry ->
                    val nameLower = entry.name.lowercase()
                    if (nameLower.endsWith(".mp3") || nameLower.endsWith(".wav") ||
                        nameLower.endsWith(".aac") || nameLower.endsWith(".m4a")) {
                        audioQueue.add(AudioFile(title = entry.name, path = entry.pathDisplay))
                    }
                }

                // Sorts the audio queue alphabetically
                audioQueue.sortBy { it.title }

                // Calculates the difference between what we have in memory and what we currently have
                val newFilesCount = audioQueue.size - lastKnownCount

                // Checks if the folder size is bigger than what we have stored in "prefs"
                if (newFilesCount == 1) {
                    speak("נוסף קובץ אחד חדש")
                } else if (newFilesCount > 1) {
                    speak("נוספו $newFilesCount קבצים חדשים")
                }

                // Saves the current size for the next check cleanly
                prefs.edit { putInt("last_known_count", audioQueue.size) }

                Log.d("VIA_Dropbox", "Library synced. Total files: ${audioQueue.size}")

            } catch (e: Exception) {
                Log.e("VIA_Dropbox", "Library refresh failed: ${e.message}")
            }
        }
    }

    // Function to dynamically turn screen enforcement on and off
    private fun keepScreenAwake(keepAwake: Boolean) {
        runOnUiThread {
            if (keepAwake) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Log.d("VIA_Screen", "Screen forced AWAKE")
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Log.d("VIA_Screen", "Screen allowed to SLEEP")
            }
        }
    }

    // Performs a silent library refresh when the app is brought back to the screen
    override fun onResume() {
        super.onResume()

        // Re-initializes Retrofit for the silent library scan
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.dropboxapi.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val apiService = retrofit.create(ApiService::class.java)

        refreshLibrary(apiService)
    }

    // Function that gets triggered automatically by Android the
    // exact moment the app is completely hidden from the user's screen.
    override fun onStop() {
        super.onStop()
    } // Acts as a guard against closing the app before pausing the audio file
}

// Object that handles the permanent authentication with Dropbox
object DropboxAuth {
    private var cachedToken: String? = null

    // Checks if there is a valid token, and fetches a new one if there isn't
    suspend fun getValidToken(apiService: ApiService): String {
        // Returns the cached token if it already exists for this session
        if (cachedToken != null) return "Bearer $cachedToken"

        return try {
            // Uses the permanent Refresh Token to get a fresh Access Token
            val response = apiService.refreshAccessToken(
                refreshToken = BuildConfig.DROPBOX_REFRESH_TOKEN,
                clientId = BuildConfig.DROPBOX_CLIENT_ID,
                clientSecret = BuildConfig.DROPBOX_CLIENT_SECRET
            )
            cachedToken = response.accessToken
            Log.d("VIA_Auth", "Access token successfully refreshed")
            "Bearer $cachedToken"
        } catch (e: Exception) {
            Log.e("VIA_Auth", "Token refresh failed: ${e.message}")
            ""
        }
    }
}