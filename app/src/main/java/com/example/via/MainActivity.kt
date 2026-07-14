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

// Media3 Remote Control Imports
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.content.ComponentName
import com.google.common.util.concurrent.ListenableFuture
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackParameters // Exoplayer voice speed

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
    private var progressJob: kotlinx.coroutines.Job? =
        null // Tracks playback progress to auto-mark files
    private var isVoiceBusy: Boolean = false

    // Tracks which track is currently physically loaded into the engine
    private var loadedAudioIndex: Int = -1

    // Media controller & Shared preferences memory
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? =
        null // Tracks the connection to the background service
    private lateinit var prefs: SharedPreferences
    private lateinit var apiService: ApiService

    // The current queue
    private var audioQueue = mutableListOf<AudioFile>()
    private var currentAudioIndex = 0

    // An empty mutable map for channel names
    private val channelsMap = mutableMapOf<Int, String>()

    // PowerManager instance
    private var wakeLock: PowerManager.WakeLock? = null

    // Memory for the double-tap exit logic
    private var pressedTime: Long = 0

    // Tracks if the music should start after the current TTS ends
    private var shouldAutoPlayNext = false

    // Define a "dot" and "comma" breaks for the TTS so it can sound more natural.
    private val dot = "<break time='1200ms'/>"
    private val comma = "<break time='600ms'/>"

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
                fallbackTts?.setOnUtteranceProgressListener(object :
                    android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        keepScreenAwake(true)
                    }

                    override fun onDone(utteranceId: String?) {
                        isVoiceBusy = false // Sets the isVoiceBusy flag
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
                    Toast.makeText(
                        this@MainActivity,
                        "Press back again to exit",
                        Toast.LENGTH_SHORT
                    ).show()
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
        val forwardBtn =
            findViewById<Button>(R.id.button3) // Next channel / Refresh list (with Yellow).
        val rewindBtn =
            findViewById<Button>(R.id.button2) // Previous channel / Goto start / Refresh list (with Blue).
        val nextBtn =
            findViewById<Button>(R.id.button5) // Next file / Next unheard / Exit app (with White).
        val previousBtn =
            findViewById<Button>(R.id.button4) // Previous file / Start of list / Exit app (with Pink).

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
        apiService = retrofit.create(ApiService::class.java)

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
                speak("רשימת הקבצים עדיין בטעינה, אנא המתן")
                Log.w("VIA_Audio", "Playback blocked: audioQueue is empty")
                return@setOnClickListener
            }

            // Gets the current media player instance
            val currentPlayer = mediaController

            // Pauses the player and saves position if already playing
            if (currentPlayer != null && currentPlayer.isPlaying) {
                pauseAudio()
                Log.d("VIA_Audio", "Audio paused manually")
            }

            // Checks if a song is loaded and verifies it matches the current UI index
            else if (currentPlayer != null && currentPlayer.currentMediaItem != null && loadedAudioIndex == currentAudioIndex) {
                currentPlayer.play()

                // Starts wakeLock with a 4-hour timeout (14400000ms) to save battery if forgotten
                if (wakeLock?.isHeld == false)
                    wakeLock?.acquire(14400000L)
                Log.d("VIA_Audio", "Audio resumed manually")
            }

            // Creates and plays audio if no player exists at all
            else {
                startPlaybackWorkflow()
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
                } else {
                    Log.i("VIA_System", "File manually marked as UNHEARD: $currentPath")
                    speak("הסימון הוסר")
                    unsyncHeardStatusToDropbox(currentPath) // Call unsync function
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

            speak("אתה משתמש באפליקציה בשם וי אה$dot כפתור ירוק: לחיצה תתחיל ותפסיק את השמע$comma ולחיצה ארוכה תסמן כנשמע$dot כפתור אדום: תקריא את הכותרת$comma ולחיצה ארוכה תשמיע את כל הכפתורים$dot כפתור כחול: מעביר לערוץ הבא$dot כפתור צהוב: מעביר לערוץ הקודם$comma ולחיצה ארוכה תעביר לתחילת הקובץ$dot לחיצה על כחול וצהוב יחד ירענן את הרשימה$dot כפתור ורוד: מעביר לקובץ הבא$comma ולחיצה ארוכה יעביר לקובץ הבא שלא הושמע עדיין$dot כפתור לבן: מעביר לקובץ קודם$comma ולחיצה ארוכה תעביר לתחילת הרשימה$dot לחיצה על ורוד ולבן יחד תסגור את האפליקציה$dot עבור הסברים נוספים$comma תפנה לירדן$dot")
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
            } else {
                true
            } // Does nothing because long pressing also does nothing
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
            } else {
                // Pauses the audio so it doesn't overlap with the TTS
                pauseAudio()

                mediaController?.let { player ->
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
            // Uses Regex to find the first continuous string of digits in the title, defaulting to 0 if none exist
            val num = Regex("\\d+").find(file.title)?.value?.toLong() ?: 0L
            (num / 100).toInt() // E.g. (1019 / 100) -> 10, AKA Channel 10.
            // E.g. (2510 / 100) -> 25, AKA Channel 25

        }.filter { it in 10..99 }.toSortedSet().toList()

        Log.d("VIA_System", "HopChannel: Detected active channels in library: $availableChannels")

        if (availableChannels.isEmpty()) {
            speak("אין ערוצים זמינים")
            return
        }

        val currentFile = audioQueue[currentAudioIndex]
        val currentNum = Regex("\\d+").find(currentFile.title)?.value?.toLong() ?: 0L
        val currentChannel = (currentNum / 100).toInt()

        var targetChannel = -1

        if (direction == 1) {
            // Finds the next channel strictly greater than the current one
            val nextChannel = availableChannels.firstOrNull { it > currentChannel }
            if (nextChannel != null) {
                targetChannel = nextChannel
            } else {
                Log.d(
                    "VIA_System",
                    "HopChannel: User at max channel ($currentChannel), cannot go up."
                )
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
                Log.d(
                    "VIA_System",
                    "HopChannel: User at min channel ($currentChannel), cannot go down."
                )
                vibrate()
                speak("ערוץ מספר $currentChannel הוא הערוץ הנמוך ביותר כרגע")
                return
            }
        }

        var channelStartIndex = -1
        var channelEndIndex = -1

        // Scans the queue to find the absolute boundaries (start and end index) of the target channel
        for (i in 0 until audioQueue.size) {
            val fileNum = Regex("\\d+").find(audioQueue[i].title)?.value?.toLong() ?: 0L
            val channel = (fileNum / 100).toInt()

            if (channel == targetChannel) {
                if (channelStartIndex == -1) channelStartIndex = i
                channelEndIndex = i
            }
        }

        if (channelStartIndex != -1) {
            var targetIndex = channelStartIndex

            // Scans strictly within the target channel's bounds to find the first unheard track
            for (i in channelStartIndex..channelEndIndex) {
                if (!prefs.getBoolean("heard_${audioQueue[i].path}", false)) {
                    targetIndex = i
                    Log.d(
                        "VIA_System",
                        "HopChannel: Found unheard track at index $targetIndex within Channel $targetChannel"
                    )
                    break
                }
            }

            Log.d(
                "VIA_System",
                "HopChannel: Jumping to index $targetIndex (Channel $targetChannel)"
            )
            vibrate()
            pauseAudio()

            // Shifts the index to the target file
            currentAudioIndex = targetIndex
            updateSlidingWindow()

            // Announces the new channel alongside it's name
            if (channelsMap.containsKey(targetChannel)) {
                speak("ערוץ $targetChannel, ${channelsMap[targetChannel]}")
            } else {
                // A fallback in case the channel isn't in index.txt
                speak("ערוץ $targetChannel")
            }
        }
    }


    // Function that strips the file extension and leading numbers
    private fun getCleanTitle(rawTitle: String): String {
        return rawTitle
            .substringBeforeLast(".") // Removes .mp3
            .replace(
                Regex("_"),
                " ."
            ) // Replaces underscore with a period and space for a natural TTS pause
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
            speak("כבר האזנת לקובץ זה. שם הקובץ הינו $cleanTitle.")
        } else {
            speak("שם הקובץ הינו $cleanTitle")
        }
    }

    // Function that manages the local cache window
    private fun updateSlidingWindow() {
        // Prevents crashing if the queue hasn't loaded yet
        if (audioQueue.isEmpty()) return

        // Defines a 31-track window (15 behind, current, 15 ahead) to keep in cache.
        // maxOf/minOf prevents the window from extending past the very start or end of the playlist.
        val startIndex = maxOf(0, currentAudioIndex - 15)
        val endIndex = minOf(audioQueue.size - 1, currentAudioIndex + 15)

        // Loops through the window to prefetch their specific TTS titles
        for (i in startIndex..endIndex) {
            val cleanTitle = getCleanTitle(audioQueue[i].title)
            val textNormal = "שם הקובץ הינו $cleanTitle"
            val textHeard = "שם הקובץ הינו $cleanTitle. כבר האזנת לקובץ זה."

            // Silently downloads the audio file if it doesn't already exist
            prefetchTTS(textNormal)
            prefetchTTS(textHeard)
        }

        // Dynamically extract active channels
        val availableChannels = audioQueue.map { file ->
            val num = Regex("\\d+").find(file.title)?.value?.toLong() ?: 0L
            (num / 100).toInt()
        }.filter { it in 10..99 }.toSortedSet().toList()

        // Pre-fetches the static channel strings
        availableChannels.forEach { channelNum ->
            prefetchTTS("ערוץ $channelNum")
            prefetchTTS("ערוץ מספר $channelNum הוא הערוץ הנמוך ביותר כרגע")
            prefetchTTS("ערוץ מספר $channelNum הוא הערוץ הגבוה ביותר כרגע")

            // Checks the channelMap and pre-fetches channel names
            if (channelsMap.containsKey(channelNum)) {
                prefetchTTS("ערוץ $channelNum, ${channelsMap[channelNum]}")
            }
        }

        // Pre-fetches all core UI commands
        val staticStrings = listOf(
            "רשימת הקבצים ריקה",
            "אין ערוצים זמינים",
            "ערוץ לא קיים",
            "הגעת לסוף הרשימה",
            "חוזר לתחילת הרשימה",
            "סומן כהושלם",
            "הסימון הוסר",
            "שגיאה בהפעלת הקובץ",
            "חזרתא לתחילת הקובץ.",
            "רשימת הקבצים עדיין בטעינה, אנא המתן",
            "כל הקבצים סומנו כהושלמו",
            "האפליקציה בודקת אם יש עדכון ברשימת הקבצים",
            "נוסף קובץ אחד חדש"
        )
        staticStrings.forEach { prefetchTTS(it) }

        Log.d(
            "VIA_TTS",
            "Sliding window prefetch complete. Cache deletion disabled to preserve Azure tokens."
        )
    }

    // TTS function
    private fun speak(text: String) {
        // Sets the isVoiceBusy flag
        isVoiceBusy = true

        // Cancels active TTS requests
        ttsJob?.cancel()

        // Stops the TTS if it's running and instantly lets the screen sleep
        voicePlayer?.release()
        voicePlayer = null
        fallbackTts?.stop()

        // Lets the screen sleep if a voice was interrupted
        keepScreenAwake(false)

        // Pauses the main music player
        if (mediaController?.isPlaying == true) pauseAudio()

        // Creates the local cache folder if it doesn't already exist
        val ttsCacheDir = File(cacheDir, "tts_cache").apply { mkdirs() }
        val cachedVoiceFile = File(ttsCacheDir, "${text.hashCode()}.wav")

        // Plays instantly if the file is already downloaded
        if (cachedVoiceFile.exists()) {
            Log.i("VIA_TTS", "Local Cache HIT for text hash: ${text.hashCode()}")
            playVoiceFile(cachedVoiceFile)
            return
        }

        // TTS Token tracker
        // Formats today's date to check if a new billing month has started (e.g. "2026-04")
        val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val currentMonth = sdf.format(Date())
        val savedMonth = prefs.getString("tts_month", "")

        var currentCount = prefs.getInt("tts_char_count", 0)

        // Resets the count to 0 if it is a new month
        if (currentMonth != savedMonth) {
            currentCount = 0
            prefs.edit { putString("tts_month", currentMonth) }
        }

        // Adds the character length of the current sentence to the monthly odometer
        val newCount = currentCount + text.length
        prefs.edit { putInt("tts_char_count", newCount) }

        Log.i("VIA_TTS", "Monthly Azure Characters Used: $newCount / 500000")

        // Creates a separate Retrofit instance for the Azure endpoint
        val azureRetrofit = Retrofit.Builder()
            .baseUrl("https://${BuildConfig.AZURE_TTS_REGION}.tts.speech.microsoft.com/cognitiveservices/")
            .build()
            .create(ApiService::class.java)

        // Formats the SSML request
        val ssmlText = """
            <speak version='1.0' xml:lang='he-IL'>
                <voice xml:lang='he-IL' xml:gender='Male' name='he-IL-AvriNeural'>
                    <prosody rate="+10%">
                        $text
                    </prosody>
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
                isVoiceBusy = false // Sets the isVoiceBusy flag
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

        // TTS Token tracker
        val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val currentMonth = sdf.format(Date())
        val savedMonth = prefs.getString("tts_month", "")

        var currentCount = prefs.getInt("tts_char_count", 0)

        // Resets the count to 0 if it is a new month
        if (currentMonth != savedMonth) {
            currentCount = 0
            prefs.edit { putString("tts_month", currentMonth) }
        }

        // Adds the character length of the current sentence to the monthly odometer
        val newCount = currentCount + text.length
        prefs.edit { putInt("tts_char_count", newCount) }

        Log.i("VIA_TTS", "Monthly Azure Characters Used: $newCount / 500000 (Prefetch)")

        val azureRetrofit = Retrofit.Builder()
            .baseUrl("https://${BuildConfig.AZURE_TTS_REGION}.tts.speech.microsoft.com/cognitiveservices/")
            .build()
            .create(ApiService::class.java)

        val ssmlText = """
            <speak version='1.0' xml:lang='he-IL'>
                <voice xml:lang='he-IL' xml:gender='Male' name='he-IL-AvriNeural'>
                    <prosody rate="+10%">
                        $text
                    </prosody>
                </voice>
            </speak>
        """.trimIndent()

        val requestBody = ssmlText.toRequestBody("application/ssml+xml".toMediaType())

        lifecycleScope.launch {
            try {
                val response =
                    azureRetrofit.getAzureTTS(BuildConfig.AZURE_TTS_KEY, ssml = requestBody)
                if (response.isSuccessful) {
                    response.body()?.bytes()?.let { audioBytes ->
                        FileOutputStream(cachedVoiceFile).use { it.write(audioBytes) }
                        Log.d(
                            "VIA_TTS",
                            "Silently pre-fetched TTS to local storage for hash: ${text.hashCode()}"
                        )
                    }
                }
            } catch (_: Exception) {
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
        mediaController?.release()
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

    // Function that handles playing audio via the background service.
    private fun playAudio(url: String) {
        val mediaItem = MediaItem.fromUri(url)

        mediaController?.let { controller ->
            // Marks this specific index as the currently loaded track
            loadedAudioIndex = currentAudioIndex

            // Hands the track to the background engine
            controller.setMediaItem(mediaItem)

            // Restores position
            val savedPosition =
                prefs.getInt("last_pos_${audioQueue[currentAudioIndex].path}", 0).toLong()
            Log.d("VIA_Audio", "Restoring track position from prefs: $savedPosition ms")
            controller.seekTo(savedPosition)

            // Tells the Service Engine to start streaming!
            controller.prepare()
            controller.play()
        }
    }

    // Function that handles pausing audio.
    private fun pauseAudio() {
        mediaController?.let { player ->
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

                Log.d(
                    "VIA_Audio",
                    "Track PAUSED at $rawPosition ms, saved overlapping position as $adjustedPosition ms"
                )
            }
        }
    }

    // Function that handles fetching the direct streaming link and starting playback
    private fun startPlaybackWorkflow() {
        lifecycleScope.launch {
            try {
                // Validates the token before fetching the link
                val token = DropboxAuth.getValidToken(apiService)
                if (token.isEmpty()) return@launch

                val currentFile = audioQueue[currentAudioIndex]
                Log.d("VIA_Dropbox", "Requesting temporary streaming link for: ${currentFile.path}")

                // Asks Dropbox for a direct streaming link
                val linkResponse =
                    apiService.getTemporaryLink(token, TempLinkRequest(currentFile.path))

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

                // Create a special Retrofit instance just for downloading content
                val contentApiService = Retrofit.Builder()
                    .baseUrl("https://content.dropboxapi.com/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(ApiService::class.java)

                // Pass a min JSON object to DropBox then receive the text into "indexResponse".
                val indexArgs = """{"path": "/via_audio/index.txt"}"""
                val indexResponse = contentApiService.downloadIndex(token, indexArgs)

                // Define a regex for the titles
                val cleanupRegex = Regex("^\\d{4}\\s*-\\s*|\\s*-\\s*\\d{4}$")

                // Parsing logic
                if (indexResponse.isSuccessful) {
                    val indexText = indexResponse.body()
                        ?.string() // This is a (potentially) long ass String that will be parsed.

                    // Splits the massive string into a List of individual lines
                    val lines = indexText?.split("\n") ?: emptyList()

                    // Parse only info we care about (title names and channel nums)
                    for (s in lines) {
                        // Check if the line even has a 4-digit number
                        val numMatch = Regex("\\d{4}").find(s)

                        if (numMatch != null) {
                            // Extract and divide by 100 to get the channel num
                            val channelNum = (numMatch.value.toLong() / 100).toInt()

                            // Wipe the number and the dash from the string, resulting in the channel name
                            val channelName = s.replace(cleanupRegex, "").trim()

                            // Save it to the map
                            channelsMap[channelNum] = channelName
                        }
                    }
                    Log.d("VIA_Index", "Successfully parsed index.txt!")
                } else {
                    Log.e(
                        "VIA_Index",
                        "Failed to download index.txt: HTTP ${indexResponse.code()} - ${
                            indexResponse.errorBody()?.string()
                        }"
                    )
                }

                // Log the channelMap
                channelsMap.forEach { (key, value) ->
                    Log.i("VIA_Index", "Key: $key, Value: $value")
                }

                // Scan the main folder for the MP3s
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
                        nameLower.endsWith(".aac") || nameLower.endsWith(".m4a")
                    ) {
                        audioQueue.add(AudioFile(title = entry.name, path = entry.pathDisplay))
                    }
                }

                // Sorts the audio queue numerically based on the first number found in the title
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

                // Dynamically extract only the channels that actually exist to save Azure API calls
                val availableChannels = audioQueue.map { file ->
                    val num = Regex("\\d+").find(file.title)?.value?.toLong() ?: 0L
                    (num / 100).toInt()
                }.filter { it in 10..99 }.toSortedSet().toList()

                // Pre-fetches the standard static channel names only for active channels
                availableChannels.forEach { channelNum ->
                    prefetchTTS("ערוץ $channelNum")
                    prefetchTTS("ערוץ מספר $channelNum הוא הערוץ הנמוך ביותר כרגע")
                    prefetchTTS("ערוץ מספר $channelNum הוא הערוץ הגבוה ביותר כרגע")
                }

                prefetchTTS("ערוץ לא קיים")
                prefetchTTS("אין ערוצים זמינים")

                updateSlidingWindow()

                // Calculates the difference between what we have in memory and what we currently have
                val newFilesCount = audioQueue.size - lastKnownCount
                Log.i(
                    "VIA_System",
                    "Library synced. Total files: ${audioQueue.size}. Delta since last check: $newFilesCount"
                )

                // // Checks if the folder size is bigger than what we have stored in "prefs"
                if (newFilesCount > 0) {

                    // Kinda of a shitty solution for preventing the daily tips from overriding the "new files" tts, but eh, idc...
                    while (isVoiceBusy) {
                        Log.d(
                            "VIA_TTS",
                            "Waiting for Daily Tip to finish before announcing new files..."
                        )
                        kotlinx.coroutines.delay(1500) // Sleeps for a second and a half, then checks again
                    }

                    // Once the loop breaks (isVoiceBusy becomes false), announce the files
                    if (newFilesCount == 1) {
                        speak("נוסף קובץ אחד חדש")
                    } else {
                        speak("נוספו $newFilesCount קבצים חדשים")
                    }
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

    // Creates a .h file
    private fun syncHeardStatusToDropbox(audioPath: String) {
        lifecycleScope.launch {
            try {
                Log.d("VIA_Sync", "Attempting to create marker file on Dropbox for: $audioPath")
                // Dropbox uses the 'content' subdomain for uploads
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
                    Log.e(
                        "VIA_Sync",
                        "Dropbox Rejected Marker Upload: HTTP ${response.code()} - $errorBody"
                    )
                }
            } catch (e: Exception) {
                Log.e("VIA_Sync", "Sync Connection Error while uploading marker: ${e.message}")
            }
        }
    }

    // Deletes a .h file
    private fun unsyncHeardStatusToDropbox(audioPath: String) {
        lifecycleScope.launch {
            try {
                Log.d("VIA_Sync", "Attempting to delete marker file on Dropbox for: $audioPath")
                // Dropbox uses the standard 'api' subdomain for metadata and file management operations
                val apiService = Retrofit.Builder()
                    .baseUrl("https://api.dropboxapi.com/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(ApiService::class.java)

                val token = DropboxAuth.getValidToken(apiService)
                if (token.isEmpty()) return@launch

                // Chops off the extension (e.g., .mp3) and replaces it with .h
                val markerPath = audioPath.substringBeforeLast(".") + ".h"

                // A route to be sent so that Retrofit can understand
                val deleteMap = mapOf(
                    "path" to markerPath
                )

                // Call the Api function, passing the token and the entire map
                val response = apiService.deleteFile(token = token, body = deleteMap)

                if (response.isSuccessful) {
                    Log.i("VIA_Sync", "SUCCESS: Marker file successfully removed at $markerPath")
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(
                        "VIA_Sync",
                        "Dropbox Rejected Marker Delete: HTTP ${response.code()} - $errorBody"
                    )
                }
            } catch (e: Exception) {
                Log.e("VIA_Sync", "Sync Connection Error while deleting marker: ${e.message}")
            }
        }
    }


    private fun checkAndPlayDailyInstructions() {
        // We leave the 4-hour shift in place, but it won't affect our minute-test
        val shiftedTimeMillis = System.currentTimeMillis() - (4 * 60 * 60 * 1000)

        // Creates a logical day string (e.g., "20260429") based on the 4-hour shifted time
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val logicalToday = sdf.format(Date(shiftedTimeMillis))

        val lastPlayedDate = prefs.getString("last_instruction_date", "")

        if (logicalToday != lastPlayedDate) {
            Log.i(
                "VIA_Instructions",
                "New logical day detected ($logicalToday). Triggering daily instruction tip."
            )

            // List of all possible "tips"
            val allInstructions = listOf(
                "אתה משתמש באפליקציה בשם וי אה",
                "כפתור ירוק: לחיצה תתחיל ותפסיק את השמע$comma ולחיצה ארוכה תסמן כנשמע",
                "כפתור אדום: תקריא את הכותרת$comma ולחיצה ארוכה תשמיע את כל הכפתורים",
                "כפתור כחול: מעביר לערוץ הבא",
                "כפתור צהוב: מעביר לערוץ הקודם$comma ולחיצה ארוכה תעביר לתחילת הקובץ",
                "לחיצה על כחול וצהוב יחד ירענן את הרשימה",
                "כפתור ורוד: מעביר לקובץ הבא$comma ולחיצה ארוכה יעביר לקובץ הבא שלא הושמע עדיין",
                "כפתור לבן: מעביר לקובץ קודם$comma ולחיצה ארוכה תעביר לתחילת הרשימה",
                "לחיצה על ורוד ולבן יחד תסגור את האפליקציה"
            )

            // Pick 1 random tip from the list and play it
            val randomInstruction = allInstructions.random()
            speak(randomInstruction)

            // Save the logical date so it won't repeat itself until the next 04:00 AM
            prefs.edit {
                putString("last_instruction_date", logicalToday)
            }
        } else {
            Log.d(
                "VIA_Instructions",
                "Daily tip skipped. Already played for logical day: $logicalToday"
            )
        }
    }

    // Saves progress and kills the app completely (removes from tray)
    private fun exitAppWorkflow() {
        vibrate()
        pauseAudio() // This saves the current index and the 3-second-rewound position

        finishAndRemoveTask()

        // Ensures the process is actually killed
        kotlin.system.exitProcess(0)
    }

    override fun onStart() {
        super.onStart()

        // Points to your PlaybackService
        val sessionToken = SessionToken(
            this,
            ComponentName(this, PlaybackService::class.java)
        )

        // Asks the OS for the Remote Control
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

        // Waits for the connection to finish
        controllerFuture?.addListener({
            try {
                // Once connected, assign the finished controller to our global variable!
                mediaController = controllerFuture?.get()
                Log.d("VIA_System", "MediaController successfully bound to PlaybackService.")

                // !!! (AS OF 05/7/2025 THIS IS NOT USED) !!!
                // Sets the speed and pitch parameters for the ExoPlayer media controller
                val playbackParameters = PlaybackParameters(1.0f, 1.0f) // Speed and pitch
                mediaController?.playbackParameters = playbackParameters

                // Attaches the listener to the controller
                mediaController?.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(
                            "VIA_Audio",
                            "ExoPlayer error: ${error.errorCodeName} - ${error.message}"
                        )

                        // Catches expired Dropbox links, dropped Wi-Fi,
                        // and Android Doze mode silently severing the background network connection (Timeout/Unspecified).
                        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                        ) {

                            Log.w("VIA_Audio", "Stream starved. Auto-recovering...")

                            // Pauses to safely write the exact crash timestamp to SharedPreferences
                            pauseAudio()

                            // Re-triggers the auth flow to get a fresh link and resume
                            startPlaybackWorkflow()
                        } else {
                            speak("שגיאה בהפעלת הקובץ")
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            Log.d("VIA_Audio", "Playback READY and streaming successfully.")
                            progressJob?.cancel()
                            progressJob = lifecycleScope.launch {
                                Log.d("VIA_System", "Background progress tracker started.")
                                // Loops while the controller exists and is playing
                                while (mediaController != null) {
                                    val player = mediaController ?: break
                                    if (player.isPlaying && player.duration > 0) {
                                        val progress =
                                            player.currentPosition.toFloat() / player.duration
                                        val currentPath = audioQueue[currentAudioIndex].path

                                        // If the audio is under 30 minutes:
                                        if (player.duration <= 1800000) {
                                            if (progress >= 0.92f && !prefs.getBoolean(
                                                    "heard_$currentPath",
                                                    false
                                                )
                                            ) {
                                                prefs.edit {
                                                    putBoolean(
                                                        "heard_$currentPath",
                                                        true
                                                    )
                                                }
                                                syncHeardStatusToDropbox(currentPath)
                                                Log.i(
                                                    "VIA_Audio",
                                                    "Auto-marked track as HEARD at 92% completion."
                                                )
                                            }
                                        }

                                        // If the audio is greater than 30 but less than an hour
                                        else if (player.duration in 1800001..3600000) {
                                            if (progress >= 0.95f && !prefs.getBoolean(
                                                    "heard_$currentPath",
                                                    false
                                                )
                                            ) {
                                                prefs.edit {
                                                    putBoolean(
                                                        "heard_$currentPath",
                                                        true
                                                    )
                                                }
                                                syncHeardStatusToDropbox(currentPath)
                                                Log.i(
                                                    "VIA_Audio",
                                                    "Auto-marked track as HEARD at 95% completion."
                                                )
                                            }
                                        }

                                        // If the audio is greater than an hour
                                        else if (player.duration > 3600000) {
                                            if (progress >= 0.98f && !prefs.getBoolean(
                                                    "heard_$currentPath",
                                                    false
                                                )
                                            ) {
                                                prefs.edit {
                                                    putBoolean(
                                                        "heard_$currentPath",
                                                        true
                                                    )
                                                }
                                                syncHeardStatusToDropbox(currentPath)
                                                Log.i(
                                                    "VIA_Audio",
                                                    "Auto-marked track as HEARD at 98% completion."
                                                )
                                            }
                                        }
                                    }
                                    kotlinx.coroutines.delay(500)
                                }
                            }
                        } else if (playbackState == Player.STATE_ENDED) {
                            val currentPath = audioQueue[currentAudioIndex].path
                            if (!prefs.getBoolean("heard_$currentPath", false)) {
                                prefs.edit { putBoolean("heard_$currentPath", true) }
                                syncHeardStatusToDropbox(currentPath)
                            }

                            keepScreenAwake(false)
                            var targetIndex = -1

                            for (i in currentAudioIndex + 1 until audioQueue.size) {
                                if (!prefs.getBoolean("heard_${audioQueue[i].path}", false)) {
                                    targetIndex = i
                                    break
                                }
                            }

                            if (targetIndex != -1) {
                                currentAudioIndex = targetIndex
                                updateSlidingWindow()

                                // We no longer release the controller here, we just prep the next track!
                                val cleanTitle = getCleanTitle(audioQueue[currentAudioIndex].title)
                                shouldAutoPlayNext = true
                                speak("הקובץ הסתיים, עובר לקובץ הבא. שם הקובץ הינו $cleanTitle")
                            }
                        }
                    }

                    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                        super.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
                        if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                            mediaController?.pause()
                            val rawPosition = mediaController?.currentPosition ?: 0L
                            val adjustedPosition = if (rawPosition > 3000) rawPosition - 3000 else 0
                            mediaController?.seekTo(adjustedPosition)

                            val audioPath = audioQueue[currentAudioIndex].path
                            prefs.edit {
                                putInt("last_pos_$audioPath", adjustedPosition.toInt())
                                putInt("last_active_index", currentAudioIndex)
                            }
                            keepScreenAwake(false)
                            if (wakeLock?.isHeld == true) wakeLock?.release()
                        }
                    }
                })

            } catch (e: Exception) {
                Log.e("VIA_System", "Failed to bind MediaController: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this)) // Forces the result onto the Main UI Thread
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

        // Call refreshLibrary (and notify user if new content exists, if need be)
        refreshLibrary(apiService)

    }

    // Function that gets triggered automatically by Android the
// exact moment the app is completely hidden from the user's screen.
    override fun onStop() {
        Log.d("VIA_System", "onStop triggered. App sent to background.")

        // Let's go of the remote control so the Service can keep running in the background
        controllerFuture?.let { future ->
            MediaController.releaseFuture(future)
            mediaController = null
        }

        super.onStop()
    }
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