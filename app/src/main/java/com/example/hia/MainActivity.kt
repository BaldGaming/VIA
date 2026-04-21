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
import android.media.MediaPlayer // Streams and plays the local voice files.
import androidx.media3.exoplayer.ExoPlayer // The modern, high-speed engine for streaming Dropbox files.
import androidx.media3.common.MediaItem // Represents the audio file for ExoPlayer.
import androidx.media3.common.Player // Handles ExoPlayer state changes.
import androidx.media3.common.PlaybackException // Handles ExoPlayer errors.
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
import org.json.JSONObject // Safely builds JSON requests for Dropbox markers.
import java.text.SimpleDateFormat // Shit for daily reminders
import java.util.Date


// Song data class
data class AudioFile(val title: String, val path: String)

class MainActivity : AppCompatActivity() {

    // Voice player memory
    private var voicePlayer: MediaPlayer? = null
    private var fallbackTts: TextToSpeech? = null
    private var ttsJob: kotlinx.coroutines.Job? = null
    private var progressJob: kotlinx.coroutines.Job? = null // Tracks playback progress to auto-mark files

    // Media player & Shared preferences memory
    private var mediaPlayer: ExoPlayer? = null
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
                Log.d("VIA_TTS", "Fallback offline TTS Engine Initialized successfully.")
            } else {
                Log.e("VIA_TTS", "Failed to initialize offline TTS Engine.")
            }
        }

        // Handles the system back button for the double-tap to exit feature (Android 13+ standard)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Checks if the back button was pressed twice within 2 seconds
                if (pressedTime + 2000 > System.currentTimeMillis()) {
                    Log.d("VIA_System", "Double-tap back registered. Exiting app.")
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
        Log.i("VIA_System", "Booting up. Loaded last known index: $currentAudioIndex")

        // Buttons
        val playBtn = findViewById<Button>(R.id.button1) // Play / Pause / Marked as heard.
        val titleBtn = findViewById<Button>(R.id.button) // Read title / Read buttons.
        val forwardBtn = findViewById<Button>(R.id.button3) // Next channel / Refresh list (with Yellow).
        val rewindBtn = findViewById<Button>(R.id.button2) // Previous channel / Goto start / Refresh list (with Blue).
        val nextBtn = findViewById<Button>(R.id.button5) // Next file / Next unheard / Exit app (with White).
        val previousBtn = findViewById<Button>(R.id.button4) // Previous file / Start of list / Exit app (with Pink).

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
                currentPlayer.play()

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
                    Log.i("VIA_System", "File manually marked as HEARD: $currentPath")
                    speak("סומן כהושלם")
                    syncHeardStatusToDropbox(currentPath) // Call sync function
                }
                else {
                    Log.i("VIA_System", "File manually marked as UNHEARD: $currentPath")
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

            speak("אתה משתמש באפליקציה בשם וי אה. כפתור ירוק: לחיצה תתחיל ותפסיק את השמע, לחיצה ארוכה תסמן כהושלם. כפתור אדום: תקריא את הכותרת, ולחיצה ארוכה תשמיע את כל הכפתורים. כפתור כחול: מעבר לערוץ הבא. כפתור צהוב: מעבר לערוץ הקודם, ולחיצה ארוכה תעביר לתחילת הקובץ. לחיצה על כחול וצהוב יחד ירענן את הרשימה. כפתור ורוד: קובץ הבא, ולחיצה ארוכה יעביר לקובץ הבא שלא הושמע עדיין. כפתור לבן: קובץ קודם, ולחיצה ארוכה תעביר לתחילת הרשימה. לחיצה על ורוד ולבן יחד תסגור את האפליקציה. עבור הסברים נוספים, תפנה לירדן.")
            true
        }



        /**
         * Next Channel logic
         */
        forwardBtn.setOnClickListener { // tap
            Log.d("VIA_Button", "Forward tapped - Channel Up")
            hopChannel(1)
        }

        forwardBtn.setOnLongClickListener { // long press
            Log.d("VIA_Button", "Channel up held")
            vibrate()

            if (rewindBtn.isPressed) {
                Log.d("VIA_System", "Dual-hold detected: Refreshing audio list")
                refreshLibrary(apiService)
                speak("האפליקציה בודקת אם יש עדכון ברשימת הקבצים")
                true
            }
            else {true} // Does nothing because long pressing also does nothing
        }


        /**
         * Previous Channel logic
         */
        rewindBtn.setOnClickListener { // tap
            Log.d("VIA_Button", "Channel Down tapped")
            hopChannel(-1)
        }

        rewindBtn.setOnLongClickListener { // long press (about 500ms)
            Log.d("VIA_Button", "Channel down held")
            vibrate()

            if (forwardBtn.isPressed) {
                Log.d("VIA_System", "Dual-hold detected: Refreshing audio list")
                refreshLibrary(apiService)
                speak("האפליקציה בודקת אם יש עדכון ברשימת הקבצים")
                true
            }
            else {
                mediaPlayer?.let { player ->
                    // Shifts the current position to the start of the current audio file
                    player.seekTo(0)

                    // Updates the SharedPreferences to reflect this reset
                    val audioPath = audioQueue[currentAudioIndex].path
                    prefs.edit { putInt("last_pos_$audioPath", 0) }
                    Log.d("VIA_Audio", "Track position reset to 0 for: $audioPath")
                }
                speak("חזרתא לתחילת הקובץ.")
                true
            }
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

                updateSlidingWindow()

                // Ejects the old audio file
                mediaPlayer?.release()
                mediaPlayer = null

                readCurrentTitle()

            } else {
                speak("הגעת לסוף הרשימה")
                Log.d("VIA_Audio", "End of playlist reached")
            }
        }

        nextBtn.setOnLongClickListener { // long press (about 500ms)
            vibrate()

            // If White is already being held down
            if (previousBtn.isPressed) {
                Log.d("VIA_System", "Dual-hold detected: Exiting app")
                exitAppWorkflow()
                true
            } else {
                // Normal skip-to-unheard logic
                Log.d("VIA_Button", "Next held - Seeking unheard track")
                var targetIndex = -1
                for (i in currentAudioIndex + 1 until audioQueue.size) {
                    if (!prefs.getBoolean("heard_${audioQueue[i].path}", false)) {
                        targetIndex = i
                        break
                    }
                }
                if (targetIndex == -1) {
                    for (i in 0 until currentAudioIndex) {
                        if (!prefs.getBoolean("heard_${audioQueue[i].path}", false)) {
                            targetIndex = i
                            break
                        }
                    }
                }
                if (targetIndex != -1) {
                    Log.d("VIA_System", "Found unheard track at index $targetIndex")
                    pauseAudio()
                    currentAudioIndex = targetIndex

                    updateSlidingWindow()

                    mediaPlayer?.release()
                    mediaPlayer = null
                    readCurrentTitle()
                } else {
                    Log.d("VIA_System", "No unheard tracks remaining in queue.")
                    speak("כל הקבצים סומנו כהושלמו")
                }
                true
            }
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

                updateSlidingWindow()

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
            vibrate()

            // If Pink is already being held down
            if (nextBtn.isPressed) {
                Log.d("VIA_System", "Dual-hold detected: Exiting app")
                exitAppWorkflow()
                true
            } else {
                // Normal reset-to-start logic
                Log.d("VIA_Button", "Previous held")
                speak("חוזר לתחילת הרשימה")
                currentAudioIndex = 0

                updateSlidingWindow()

                true
            }
        }
    }

    // Function that shifts the index to the next or previous available channel
    private fun hopChannel(direction: Int) {
        if (audioQueue.isEmpty()) {
            speak("רשימת הקבצים ריקה")
            return
        }

        // Extracts all unique, valid channels currently available in the queue
        val availableChannels = audioQueue.map { file ->
            val num = Regex("\\d+").find(file.title)?.value?.toLong() ?: 0L
            (num / 1000).toInt()
        }.filter { it in 1..9 }.toSortedSet().toList()

        Log.d("VIA_System", "HopChannel: Detected active channels in library: $availableChannels")

        if (availableChannels.isEmpty()) {
            speak("אין ערוצים זמינים")
            return
        }

        val currentFile = audioQueue[currentAudioIndex]
        val currentNum = Regex("\\d+").find(currentFile.title)?.value?.toLong() ?: 0L
        val currentChannel = (currentNum / 1000).toInt()

        var targetChannel = -1

        if (direction == 1) {
            // Finds the next channel strictly greater than the current one
            val nextChannel = availableChannels.firstOrNull { it > currentChannel }
            if (nextChannel != null) {
                targetChannel = nextChannel
            } else {
                Log.d("VIA_System", "HopChannel: User at max channel ($currentChannel), cannot go up.")
                vibrate()
                speak("ערוץ מספר $currentChannel הוא הערוץ הגבוה ביותר כרגע")
                return
            }
        } else if (direction == -1) {
            // Finds the previous channel strictly lesser than the current one
            val prevChannel = availableChannels.lastOrNull { it < currentChannel }
            if (prevChannel != null) {
                targetChannel = prevChannel
            } else {
                Log.d("VIA_System", "HopChannel: User at min channel ($currentChannel), cannot go down.")
                vibrate()
                speak("ערוץ מספר $currentChannel הוא הערוץ הנמוך ביותר כרגע")
                return
            }
        }

        var targetIndex = -1

        // Scans the queue to find the absolute first file belonging to the target channel range
        for (i in 0 until audioQueue.size) {
            val fileNum = Regex("\\d+").find(audioQueue[i].title)?.value?.toLong() ?: 0L
            val channel = (fileNum / 1000).toInt()

            if (channel == targetChannel) {
                targetIndex = i
                break
            }
        }

        if (targetIndex != -1) {
            Log.d("VIA_System", "HopChannel: Jumping to index $targetIndex (Channel $targetChannel)")
            vibrate()
            pauseAudio()

            // Shifts the index to the start of the new channel
            currentAudioIndex = targetIndex
            updateSlidingWindow()

            // Kills the old audio (Requires user to manually press Green Play to start the channel)
            mediaPlayer?.release()
            mediaPlayer = null

            // Announces the new channel strictly
            speak("ערוץ $targetChannel")
        }
    }

    // Function that strips the file extension and leading numbers
    private fun getCleanTitle(rawTitle: String): String {
        return rawTitle
            .substringBeforeLast(".") // Removes .mp3
            .replace(Regex("_"), " .") // Replaces underscore with a period and space for a natural TTS pause
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

    // Function that manages the local cache window and deletes old files
    private fun updateSlidingWindow() {
        if (audioQueue.isEmpty()) return

        val startIndex = maxOf(0, currentAudioIndex - 2)
        val endIndex = minOf(audioQueue.size - 1, currentAudioIndex + 2)

        val validFilenames = mutableSetOf<String>()

        for (i in startIndex..endIndex) {
            val cleanTitle = getCleanTitle(audioQueue[i].title)
            val textNormal = "שם הקובץ הינו $cleanTitle"
            val textHeard = "שם הקובץ הינו $cleanTitle. כבר האזנת לקובץ זה."

            validFilenames.add("${textNormal.hashCode()}.wav")
            validFilenames.add("${textHeard.hashCode()}.wav")

            prefetchTTS(textNormal)
            prefetchTTS(textHeard)
        }

        // Also protects the static channel strings from being deleted
        for (i in 1..9) {
            validFilenames.add("${"ערוץ $i".hashCode()}.wav")
            validFilenames.add("${"ערוץ מספר $i הוא הערוץ הנמוך ביותר כרגע".hashCode()}.wav")
            validFilenames.add("${"ערוץ מספר $i הוא הערוץ הגבוה ביותר כרגע".hashCode()}.wav")
        }
        validFilenames.add("${"ערוץ לא קיים".hashCode()}.wav")
        validFilenames.add("${"אין ערוצים זמינים".hashCode()}.wav")

        val ttsCacheDir = File(cacheDir, "tts_cache")
        if (ttsCacheDir.exists()) {
            ttsCacheDir.listFiles()?.forEach { file ->
                // Checks the cache folder and deletes unneeded files
                if (!validFilenames.contains(file.name)) {
                    file.delete()
                    Log.d("VIA_TTS", "Cleaned up old cache file: ${file.name}")
                }
            }
        }
        Log.d("VIA_TTS", "Sliding window updated. Total valid cache signatures maintained: ${validFilenames.size}")
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

        val ttsCacheDir = File(cacheDir, "tts_cache").apply { mkdirs() }
        val cachedVoiceFile = File(ttsCacheDir, "${text.hashCode()}.wav")

        // Plays instantly if the file is already downloaded
        if (cachedVoiceFile.exists()) {
            Log.i("VIA_TTS", "Local Cache HIT for text hash: ${text.hashCode()}")
            playVoiceFile(cachedVoiceFile)
            return
        }

        Log.i("VIA_TTS", "Local Cache MISS. Requesting network stream from Azure for hash: ${text.hashCode()}")

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
                        // Saves the voice stream to the local cache
                        FileOutputStream(cachedVoiceFile).use { it.write(audioBytes) }

                        // Plays the voice
                        playVoiceFile(cachedVoiceFile)
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

    // Function that handles playing the local TTS file.
    private fun playVoiceFile(file: File) {
        voicePlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                keepScreenAwake(false)

                // Checks if the system is waiting to autoplay the next track
                if (shouldAutoPlayNext) {
                    shouldAutoPlayNext = false // Resets the flag
                    Log.d("VIA_Audio", "TTS complete. Auto-playing next track in sequence.")
                    findViewById<Button>(R.id.button1).performClick() // Clicks play
                }
            }
            prepare()
            keepScreenAwake(true)
            start()
        }
    }

    // Function that silently downloads TTS audio in the background.
    private fun prefetchTTS(text: String) {
        val ttsCacheDir = File(cacheDir, "tts_cache").apply { mkdirs() }
        val cachedVoiceFile = File(ttsCacheDir, "${text.hashCode()}.wav")

        if (cachedVoiceFile.exists()) return

        val azureRetrofit = Retrofit.Builder()
            .baseUrl("https://${BuildConfig.AZURE_TTS_REGION}.tts.speech.microsoft.com/cognitiveservices/")
            .build()
            .create(ApiService::class.java)

        val ssmlText = """
            <speak version='1.0' xml:lang='he-IL'>
                <voice xml:lang='he-IL' xml:gender='Male' name='he-IL-AvriNeural'>
                    $text
                </voice>
            </speak>
        """.trimIndent()

        val requestBody = ssmlText.toRequestBody("application/ssml+xml".toMediaType())

        lifecycleScope.launch {
            try {
                val response = azureRetrofit.getAzureTTS(BuildConfig.AZURE_TTS_KEY, ssml = requestBody)
                if (response.isSuccessful) {
                    response.body()?.bytes()?.let { audioBytes ->
                        FileOutputStream(cachedVoiceFile).use { it.write(audioBytes) }
                        Log.d("VIA_TTS", "Silently pre-fetched TTS to local storage for hash: ${text.hashCode()}")
                    }
                }
            } catch (e: Exception) {
                Log.e("VIA_TTS", "Silent pre-fetch failed for hash: ${text.hashCode()}")
            }
        }
    }

    // Cleans up memory and services when the app is completely destroyed
    override fun onDestroy() {
        Log.i("VIA_System", "App completely destroyed. Releasing all resources.")
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
        mediaPlayer = ExoPlayer.Builder(this@MainActivity).build().apply {
            val mediaItem = MediaItem.fromUri(url)
            setMediaItem(mediaItem)

            // Shifts the player to the saved position before buffering
            val savedPosition = prefs.getInt("last_pos_${audioQueue[currentAudioIndex].path}", 0).toLong()
            Log.d("VIA_Audio", "Restoring track position from prefs: $savedPosition ms")
            seekTo(savedPosition)

            playWhenReady = true

            // Sets a listener to catch errors and track playback state
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e("VIA_Audio", "ExoPlayer error during playback: ${error.message}")
                    speak("שגיאה בהפעלת הקובץ")
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        Log.d("VIA_Audio", "Playback READY and streaming successfully.")

                        // Cancels any existing progress tracker
                        progressJob?.cancel()

                        // Starts a background loop to check if the file reached 98% completion
                        progressJob = lifecycleScope.launch {
                            Log.d("VIA_System", "Background progress tracker started for current track.")
                            while (mediaPlayer == this@apply) {
                                if (isPlaying && duration > 0) {
                                    val progress = currentPosition.toFloat() / duration
                                    val currentPath = audioQueue[currentAudioIndex].path

                                    // Checks progress and ensures we don't spam uploads if already marked
                                    if (progress >= 0.98f && !prefs.getBoolean("heard_$currentPath", false)) {
                                        prefs.edit { putBoolean("heard_$currentPath", true) }
                                        syncHeardStatusToDropbox(currentPath) // Call sync function
                                        Log.i("VIA_Audio", "Auto-marked track as HEARD at 98% completion.")
                                    }
                                }
                                // Lowered to 500ms so it doesn't sleep through short tracks
                                kotlinx.coroutines.delay(500)
                            }
                        }
                    } else if (playbackState == Player.STATE_ENDED) {

                        // THE SAFETY NET: If the track ends naturally or is skipped to the end, guarantee it gets marked!
                        val currentPath = audioQueue[currentAudioIndex].path
                        if (!prefs.getBoolean("heard_$currentPath", false)) {
                            prefs.edit { putBoolean("heard_$currentPath", true) }
                            syncHeardStatusToDropbox(currentPath)
                            Log.i("VIA_Audio", "Auto-marked track as HEARD at 100% completion (Safety Net).")
                        }

                        // Triggers autoplay logic when a song finishes naturally
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
                            Log.d("VIA_Audio", "Autoplay triggered. Skipping to next unheard file at index $targetIndex")

                            // Advances the index manually to the unread file
                            currentAudioIndex = targetIndex

                            updateSlidingWindow()

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
                            Log.i("VIA_Audio", "Autoplay stopped. No unheard files found ahead in queue.")
                            // Autoplay naturally stops here.
                        }
                    }
                }
            })

            // Starts the background buffer
            prepare()
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
                    (rawPosition - 3000).toInt()
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

                Log.d("VIA_Audio", "Track PAUSED at $rawPosition ms, saved overlapping position as $adjustedPosition ms")
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
                Log.d("VIA_Dropbox", "Requesting temporary streaming link for: ${currentFile.path}")

                // Asks Dropbox for a direct streaming link
                val linkResponse = apiService.getTemporaryLink(token, TempLinkRequest(currentFile.path))

                Log.i("VIA_Dropbox", "Streaming link fetched successfully.")
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
                Log.d("VIA_System", "Starting full library refresh cycle.")
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

                    if (nameLower.endsWith(".h"))
                        return@forEach

                    if (nameLower.endsWith(".mp3") || nameLower.endsWith(".wav") ||
                        nameLower.endsWith(".aac") || nameLower.endsWith(".m4a")) {
                        audioQueue.add(AudioFile(title = entry.name, path = entry.pathDisplay))
                    }
                }

                // Sorts the audio queue numerically
                audioQueue.sortWith(Comparator { file1, file2 ->
                    val regex = Regex("(\\d+)") // Finds groups of numbers
                    val parts1 = regex.findAll(file1.title).map { it.value.toLong() }.toList()
                    val parts2 = regex.findAll(file2.title).map { it.value.toLong() }.toList()

                    // Compare the numbers found in the titles
                    if (parts1.isNotEmpty() && parts2.isNotEmpty()) {
                        parts1[0].compareTo(parts2[0])
                    } else {
                        // Fallback to standard alphabetical if no numbers are found
                        file1.title.compareTo(file2.title, ignoreCase = true)
                    }
                })

                // Pre-fetches the standard static channel names so they play instantly
                for (i in 1..9) {
                    prefetchTTS("ערוץ $i")
                    prefetchTTS("ערוץ מספר $i הוא הערוץ הנמוך ביותר כרגע")
                    prefetchTTS("ערוץ מספר $i הוא הערוץ הגבוה ביותר כרגע")
                }
                prefetchTTS("ערוץ לא קיים")
                prefetchTTS("אין ערוצים זמינים")

                updateSlidingWindow()

                // Calculates the difference between what we have in memory and what we currently have
                val newFilesCount = audioQueue.size - lastKnownCount
                Log.i("VIA_System", "Library synced. Total files: ${audioQueue.size}. Delta since last check: $newFilesCount")

                // Checks if the folder size is bigger than what we have stored in "prefs"
                if (newFilesCount == 1) {
                    speak("נוסף קובץ אחד חדש")
                } else if (newFilesCount > 1) {
                    speak("נוספו $newFilesCount קבצים חדשים")
                }

                // Saves the current size for the next check cleanly
                prefs.edit { putInt("last_known_count", audioQueue.size) }

            } catch (e: Exception) {
                Log.e("VIA_Dropbox", "Library refresh completely failed: ${e.message}")
            }
        }
    }

    // Function to dynamically turn screen enforcement on and off
    private fun keepScreenAwake(keepAwake: Boolean) {
        runOnUiThread {
            if (keepAwake) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Log.d("VIA_Screen", "Screen WakeLock forced AWAKE")
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Log.d("VIA_Screen", "Screen WakeLock allowed to SLEEP")
            }
        }
    }

    private fun syncHeardStatusToDropbox(audioPath: String) {
        lifecycleScope.launch {
            try {
                Log.d("VIA_Sync", "Attempting to create marker file on Dropbox for: $audioPath")
                // THE FIX: Dropbox uses the 'content' subdomain for uploads
                val apiService = Retrofit.Builder()
                    .baseUrl("https://content.dropboxapi.com/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(ApiService::class.java)

                val token = DropboxAuth.getValidToken(apiService)
                if (token.isEmpty()) return@launch

                // Chops off the extension (e.g., .mp3) and replaces it with .h
                val markerPath = audioPath.substringBeforeLast(".") + ".h"

                // Uses JSONObject to guarantee formatting doesn't break on weird characters
                val jsonParams = JSONObject()
                jsonParams.put("path", markerPath)
                jsonParams.put("mode", "overwrite")
                jsonParams.put("mute", true)

                val rawJson = jsonParams.toString()

                // Sanitizer: Converts Hebrew letters to ASCII-safe Unicode escapes (\uXXXX)
                // to prevent the "Unexpected char" error in the HTTP header.
                val safeJson = rawJson.map { char ->
                    if (char.code > 127) {
                        "\\u" + String.format("%04x", char.code)
                    } else {
                        char.toString()
                    }
                }.joinToString("")

                val emptyBody = "".toRequestBody("application/octet-stream".toMediaType())

                // Sends the request to the correct content.dropboxapi.com domain
                val response = apiService.uploadFile(token, safeJson, emptyBody)

                if (response.isSuccessful) {
                    Log.i("VIA_Sync", "SUCCESS: Marker file successfully uploaded at $markerPath")
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e("VIA_Sync", "Dropbox Rejected Marker Upload: HTTP ${response.code()} - $errorBody")
                }
            } catch (e: Exception) {
                Log.e("VIA_Sync", "Sync Connection Error while uploading marker: ${e.message}")
            }
        }
    }

    private fun checkAndPlayDailyInstructions() {
        // We leave the 4-hour shift in place, but it won't affect our minute-test
        val shiftedTimeMillis = System.currentTimeMillis() - (4 * 60 * 60 * 1000)

        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val logicalToday = sdf.format(Date(shiftedTimeMillis))

        val lastPlayedDate = prefs.getString("last_instruction_date", "")

        if (logicalToday != lastPlayedDate) {
            Log.i("VIA_Instructions", "New logical day detected ($logicalToday). Triggering daily instruction tip.")

            // List of all possible "tips"
            val allInstructions = listOf(
                "אתה משתמש באפליקציה בשם וי אה.",
                "כפתור ירוק: לחיצה תתחיל ותפסיק את השמע.",
                "כפתור ירוק: לחיצה ארוכה תסמן כהושלם.",
                "כפתור אדום: תקריא את הכותרת.",
                "כפתור אדום: לחיצה ארוכה תשמיע את כל הכפתורים.",
                "כפתור כחול: מעבר לערוץ הבא.",
                "כפתור צהוב: מעבר לערוץ הקודם.",
                "כפתור צהוב: לחיצה ארוכה תעביר לתחילת הקובץ.",
                "לחיצה על כחול וצהוב יחד ירענן את הרשימה.",
                "כפתור ורוד: מעבר לקובץ הבא.",
                "כפתור ורוד: לחיצה ארוכה יעביר לקובץ הבא שלא הושמע עדיין.",
                "כפתור לבן: מעבר לקובץ הקודם.",
                "כפתור לבן: לחיצה ארוכה תעביר לתחילת הרשימה.",
                "לחיצה על ורוד ולבן יחד תסגור את האפליקציה."
            )

            // Pick 1 random tip from the list and play it
            val randomInstruction = allInstructions.random()
            speak(randomInstruction)

            // Save the logical date so it won't repeat itself until the next 04:00 AM
            prefs.edit {
                putString("last_instruction_date", logicalToday)
            }
        } else {
            Log.d("VIA_Instructions", "Daily tip skipped. Already played for logical day: $logicalToday")
        }
    }

    // Saves progress and kills the app completely (removes from tray)
    private fun exitAppWorkflow() {
        vibrate()
        pauseAudio() // This saves the current index and the 3-second-rewound position

        // finishAndRemoveTask() closes the activity and removes it from the 'Recents' screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }

        // Ensures the process is actually killed
        System.exit(0)
    }

    // Performs a silent library refresh when the app is brought back to the screen
    override fun onResume() {
        super.onResume()
        Log.d("VIA_System", "onResume triggered. App brought to foreground.")

        // Check the clock here. This ensures it runs even if the app was just minimized.
        checkAndPlayDailyInstructions()

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
        Log.d("VIA_System", "onStop triggered. App sent to background.")
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
            Log.d("VIA_Auth", "Access token successfully refreshed via Dropbox API")
            "Bearer $cachedToken"
        } catch (e: Exception) {
            Log.e("VIA_Auth", "CRITICAL: Token refresh failed: ${e.message}")
            ""
        }
    }
}