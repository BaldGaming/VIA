package com.example.via

// --- CORE ANDROID & UI ---
import android.os.Bundle                        // Passes the saved state when the app screen is created.
import android.view.LayoutInflater              // Converts the XML layout file into actual UI objects.
import android.view.View                        // Represents standard UI elements (used for visibility toggles).
import android.view.ViewGroup                   // A special view that can contain other views (like layouts).
import android.widget.Button                    // Hooks up the standard UI buttons (Logs, Audio, etc.).
import android.widget.ImageButton               // Hooks up buttons that use icons instead of text (Stay button).
import android.widget.ScrollView                // Allows the text log to scroll vertically.
import android.widget.TextView                  // Displays the text inside the log screen.
import androidx.activity.OnBackPressedCallback  // Handles modern system back-button gestures securely.
import androidx.fragment.app.Fragment           // The base class for making modular screens.
import android.util.Log                         // Prints debugging messages to the Logcat console.


// --- ASYNC & COROUTINES (BACKGROUND WORKERS) ---
import androidx.lifecycle.lifecycleScope        // Runs background timers safely without crashing the UI.
import kotlinx.coroutines.Job                   // Represents a background task that can be canceled (the countdown).
import kotlinx.coroutines.delay                 // Pauses a background task for a specific amount of time.
import kotlinx.coroutines.launch                // The specific command that starts the background coroutine.

// --- UTILS (DATES & TIMESTAMPS) ---
import java.text.SimpleDateFormat               // Formats timestamps for the log output.
import java.util.Date                           // Gets the exact current time for the log.
import java.util.Locale                         // Sets the regional formatting for the time.




class DevFragment : Fragment() {

    // Declare the button in such a way it is visible for the whole class
    private lateinit var stayBtn: ImageButton

    // Used for flagging if the user hit the stay button in time
    var stay: Boolean = false

    // The master switch to instantly kill the background timer
    private var countdownJob: Job? = null

    // Declare the views
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    // Defines the layout containers for the dev screen
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    private lateinit var paginationLayout: View
    private lateinit var buttonGrid: View

    // The bucket that holds the secondary admin screens
    private lateinit var subFragmentContainer: androidx.fragment.app.FragmentContainerView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dev, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Listens for any time a sub-fragment is swiped away or popped
        childFragmentManager.addOnBackStackChangedListener {
            if (childFragmentManager.backStackEntryCount == 0) {
                Log.d("VIA_Admin", "Sub-fragment closed. Returning to dashboard.")
                subFragmentContainer.visibility = View.GONE
            }
        }

        // Handles the system back swipe while the Admin screen is active
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val devContainer = activity?.findViewById<View>(R.id.fragment_dev)

                if (devContainer?.visibility == View.VISIBLE) {
                    if (childFragmentManager.backStackEntryCount > 0) {
                        Log.d("VIA_Admin", "Back swipe intercepted: Popping sub-fragment.")
                        childFragmentManager.popBackStack()
                    } else {
                        Log.d("VIA_Admin", "Back swipe intercepted: Closing Admin dashboard.")
                        devContainer.visibility = View.GONE
                        countdownJob?.cancel()

                        val mainActivity = activity as? MainActivity
                        if (mainActivity?.isVoiceBusy == true && toolbar.visibility != View.VISIBLE) {
                            Log.d("VIA_TTS", "User exited during warning TTS. Triggering gag.")
                            mainActivity.speak("לא יפה, תיתן לי לסיים לדבר.")
                        }
                    }
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        // Find the views by their IDs
        logTextView = view.findViewById(R.id.logTextView)
        logScrollView = view.findViewById(R.id.logScrollView)
        toolbar = view.findViewById(R.id.admin_toolbar)
        paginationLayout = view.findViewById(R.id.page_layout)
        buttonGrid = view.findViewById(R.id.button_grid)
        subFragmentContainer = view.findViewById(R.id.dev_sub_fragment_container)
        stayBtn = view.findViewById(R.id.button6)

        val logBtn = view.findViewById<Button>(R.id.btn_1)
        val audioBtn = view.findViewById<Button>(R.id.btn_2)
        val fileManagementBtn = view.findViewById<Button>(R.id.btn_3)
        val fileMarkingBtn = view.findViewById<Button>(R.id.btn_4)
        val offlineBtn = view.findViewById<Button>(R.id.btn_5)
        val statisticsBtn = view.findViewById<Button>(R.id.btn_6)

        // Hides the UI elements immediately so the screen is pure black
        randomizeStayCords()

        /**
         * Close logic
         */
        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        /**
         * Stay logic
         */
        stayBtn.setOnClickListener {
            Log.d("VIA_Button", "Admin stay button tapped")
            stay = true
            stayBtn.visibility = View.INVISIBLE
        }

        /**
         * Log button
         */
        logBtn.setOnClickListener {
            Log.d("VIA_Button", "Admin module opened: Logs")
            childFragmentManager.beginTransaction()
                .replace(R.id.dev_sub_fragment_container, LogsFragment())
                .setReorderingAllowed(true)
                .addToBackStack(null)
                .commit()

            subFragmentContainer.visibility = View.VISIBLE
        }

        /**
         * Audio button
         */
        audioBtn.setOnClickListener {
            Log.d("VIA_Button", "Admin module opened: Audio")
            childFragmentManager.beginTransaction()
                .replace(R.id.dev_sub_fragment_container, AudioFragment())
                .setReorderingAllowed(true)
                .addToBackStack(null)
                .commit()

            subFragmentContainer.visibility = View.VISIBLE
        }

        /**
         * File Management button
         */
        fileManagementBtn.setOnClickListener {
            Log.d("VIA_Button", "Admin module opened: File Management")
            childFragmentManager.beginTransaction()
                .replace(R.id.dev_sub_fragment_container, FileManagementFragment())
                .setReorderingAllowed(true)
                .addToBackStack(null)
                .commit()

            subFragmentContainer.visibility = View.VISIBLE
        }

        /**
         * File Marking button
         */
        fileMarkingBtn.setOnClickListener {
            Log.d("VIA_Button", "Admin module opened: File Marking")
            childFragmentManager.beginTransaction()
                .replace(R.id.dev_sub_fragment_container, FileMarkingFragment())
                .setReorderingAllowed(true)
                .addToBackStack(null)
                .commit()

            subFragmentContainer.visibility = View.VISIBLE
        }

        /**
         * Offline button
         */
        offlineBtn.setOnClickListener {
            Log.d("VIA_Button", "Admin module opened: Offline Mode")
            childFragmentManager.beginTransaction()
                .replace(R.id.dev_sub_fragment_container, OfflineFragment())
                .setReorderingAllowed(true)
                .addToBackStack(null)
                .commit()

            subFragmentContainer.visibility = View.VISIBLE
        }

        /**
         * Statistics button
         */
        statisticsBtn.setOnClickListener {
            Log.d("VIA_Button", "Admin module opened: Statistics")
            childFragmentManager.beginTransaction()
                .replace(R.id.dev_sub_fragment_container, StatisticsFragment())
                .setReorderingAllowed(true)
                .addToBackStack(null)
                .commit()

            subFragmentContainer.visibility = View.VISIBLE
        }
    }

    fun startAdminTimeoutSequence() {
        val mainActivity = activity as? MainActivity ?: return

        stay = false
        randomizeStayCords()
        countdownJob?.cancel()

        Log.d("VIA_Admin", "Timeout sequence started. Awaiting user verification.")

        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            while (mainActivity.isVoiceBusy) {
                if (stay) break
                delay(200)
            }

            if (!isAdded) return@launch

            for (i in 0..4) {
                if (stay) {
                    break
                } else {
                    mainActivity.soundPool?.play(mainActivity.beepId, 1f, 1f, 0, 0, 1f)
                    if (!isAdded) return@launch
                    delay(1000)
                }
            }

            if (!isAdded) return@launch

            if (stay) {
                Log.d("VIA_Admin", "User successfully verified. Revealing dashboard.")
                stayBtn.visibility = View.INVISIBLE
                toolbar.visibility = View.VISIBLE
                paginationLayout.visibility = View.VISIBLE
                buttonGrid.visibility = View.VISIBLE
                mainActivity.speak(mainActivity.stayMessage)
            } else {
                Log.d("VIA_Admin", "User failed to verify in time. Booting back to main screen.")
                stayBtn.visibility = View.INVISIBLE
                mainActivity.speak(mainActivity.backMessage)
                activity?.findViewById<View>(R.id.fragment_dev)?.visibility = View.GONE
            }
        }
    }

    private fun appendLog(message: String) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timestamp = timeFormat.format(Date())
        val newLog = "[$timestamp] $message\n"

        logTextView.append(newLog)
        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun randomizeStayCords() {
        toolbar.visibility = View.INVISIBLE
        paginationLayout.visibility = View.INVISIBLE
        buttonGrid.visibility = View.INVISIBLE

        stayBtn.post {
            val parentView = view ?: return@post
            val margin = 120

            val maxX = parentView.width - stayBtn.width - margin
            val maxY = parentView.height - stayBtn.height - margin

            val safeMaxX = if (maxX > margin) maxX else margin
            val safeMaxY = if (maxY > margin) maxY else margin

            val randomX = (margin..safeMaxX).random()
            val randomY = (margin..safeMaxY).random()

            stayBtn.x = randomX.toFloat()
            stayBtn.y = randomY.toFloat()

            stayBtn.visibility = View.VISIBLE
            Log.d("VIA_Admin", "Randomized verification button coordinates.")
        }
    }
}