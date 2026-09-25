package com.example.via

// --- CORE ANDROID & UI ---
import android.os.Bundle
import android.text.SpannableString
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope

// --- ASYNC & COROUTINES (BACKGROUND WORKERS) ---
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

        // Menu buttons
        val logBtn = view.findViewById<Button>(R.id.btn_1)
        val audioBtn = view.findViewById<Button>(R.id.btn_2)
        val fileManagementBtn = view.findViewById<Button>(R.id.btn_3)
        val fileMarkingBtn = view.findViewById<Button>(R.id.btn_4)
        val offlineBtn = view.findViewById<Button>(R.id.btn_5)
        val statisticsBtn = view.findViewById<Button>(R.id.btn_6)

        // Info buttons
        val infoBtn1 = view.findViewById<ImageButton>(R.id.infoBtn_1)
        val infoBtn2 = view.findViewById<ImageButton>(R.id.infoBtn_2)
        val infoBtn3 = view.findViewById<ImageButton>(R.id.infoBtn_3)
        val infoBtn4 = view.findViewById<ImageButton>(R.id.infoBtn_4)
        val infoBtn5 = view.findViewById<ImageButton>(R.id.infoBtn_5)
        val infoBtn6 = view.findViewById<ImageButton>(R.id.infoBtn_6)

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
         * Info 1 logic
         */
        infoBtn1.setOnClickListener {
            Log.d("VIA_Button", "Info 1 button pressed.")

            // Build the unformatted body text
            val bodyText =
                "In here you'll find logs of all types, including:\n\n" +
                    "1. Literally every action\n" +
                    "2. TTS messages\n" +
                    "3. Error messages from logcat"

            // concatenate the formatted title and unformatted body
            showMinimalDialog(android.text.TextUtils.concat("Logs".underlinedTitle(), bodyText))
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
         * Info 2 logic
         */
        infoBtn2.setOnClickListener {
            Log.d("VIA_Button", "Info 2 button pressed.")

            val bodyText =
                "In here you'll be able to control:\n\n" +
                    "1. The Azure TTS reading speed\n" +
                    "2. The ExoPlayer playing speed\n"

            showMinimalDialog(android.text.TextUtils.concat("Audio Settings".underlinedTitle(), bodyText))
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
         * Info 3 logic
         */
        infoBtn3.setOnClickListener {
            Log.d("VIA_Button", "Info 3 button pressed.")
            val bodyText =
                "In here you'll be able to manually mark and unmark files as heard."

            showMinimalDialog(android.text.TextUtils.concat("File Marking".underlinedTitle(), bodyText))
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
         * Info 4 logic
         */
        infoBtn4.setOnClickListener {
            Log.d("VIA_Button", "Info 4 button pressed.")
            val bodyText =
                "In here you'll be able to:\n\n" +
                        "1. Delete files\n" +
                        "2. Reveal\\Hide files\n"

            showMinimalDialog(android.text.TextUtils.concat("File Management".underlinedTitle(), bodyText))
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
         * Info 5 logic
         */
        infoBtn5.setOnClickListener {
            Log.d("VIA_Button", "Info 5 button pressed.")
            val bodyText =
                "עדיין אין לי ממש מושג זה אמור לעשות..\nנגלה בעתיד :^)"

            showMinimalDialog(android.text.TextUtils.concat("Offline Mode".underlinedTitle(), bodyText))
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

        /**
         * Info 6 logic
         */
        infoBtn6.setOnClickListener {
            Log.d("VIA_Button", "Info 6 button pressed.")
            val bodyText =
                "In here you'll be able to view:\n\n" +
                        "1. Stats about \"הפעלת קול\", whatever that means\n" +
                        "2. The Azure TTS token usage\n"

            showMinimalDialog(android.text.TextUtils.concat("Statistics".underlinedTitle(), bodyText))
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

    private fun showMinimalDialog(info: CharSequence) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.CustomAlertDialogTheme)

        builder.setMessage(info)
            .setCancelable(true)
            .setPositiveButton("Close") { dialog, _ ->
                dialog.dismiss()
            }

        val dialog = builder.create()
        dialog.show()

        // Force the button to be greenTheme
        val positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
        positiveButton.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.greenTheme))
    }

    // Function for underlining and centering the title
    private fun String.underlinedTitle(): SpannableString {
        // Bake the colon and newlines into the title so the paragraph is isolated
        val fullTitle = "$this\n\n"
        val spannable = SpannableString(fullTitle)

        // Underline the title
        spannable.setSpan(UnderlineSpan(), 0, this.length, 0)

        // Apply a center alignment span
        spannable.setSpan(
            android.text.style.AlignmentSpan.Standard(android.text.Layout.Alignment.ALIGN_CENTER),
            0, fullTitle.length, 0
        )

        return spannable
    }
}