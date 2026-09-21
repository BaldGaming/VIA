package com.example.via

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Shit for the log
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FragmentDev : Fragment() {

    // Declare the button in such a way it is visible for the whole class
    private lateinit var verifyBtn: ImageButton // "lateinit" declares a non-nullable property without initializing it immediately when the project is created

    // Used for flagging if the user hit the verify button in time
    var stay: Boolean = false

    // The master switch to instantly kill the background timer
    private var countdownJob: Job? = null

    // Declare the views
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    // Defines the layout containers for the dev screen
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar // Declared specifically as Toolbar so we can detect native back arrow clicks
    private lateinit var paginationLayout: View
    private lateinit var buttonGrid: View

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_dev, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Find the views by their IDs
        logTextView = view.findViewById(R.id.logTextView)
        logScrollView = view.findViewById(R.id.logScrollView)

        // We find the toolbar and the UI containers
        toolbar = view.findViewById(R.id.admin_toolbar)
        paginationLayout = view.findViewById(R.id.pagination_layout)
        buttonGrid = view.findViewById(R.id.button_grid)

        // Defines the buttons
        verifyBtn = view.findViewById(R.id.button6)
        val logButton = view.findViewById<Button>(R.id.logButton)

        // Hides the UI elements immediately so the screen is pure black
        toolbar.visibility = View.INVISIBLE
        paginationLayout.visibility = View.INVISIBLE
        buttonGrid.visibility = View.INVISIBLE
        verifyBtn.visibility = View.INVISIBLE

        /**
         * Close logic
         */
        // Allows the user to exit the dev screen safely after the dashboard opens
        toolbar.setNavigationOnClickListener {
            activity?.findViewById<View>(R.id.fragment_dev)?.visibility = View.GONE
        }

        verifyBtn.setOnClickListener { // tap
            stay = true
            verifyBtn.visibility = View.INVISIBLE
        }

        logButton.setOnClickListener {
            appendLog("pressed \"log\"")
        }
    }

    // Function called only when the user hits the 7-tap trigger that boots the user out
    fun startAdminTimeoutSequence() {
        val mainActivity = activity as? MainActivity ?: return // Grab the Main Activity "bridge"

        stay = false

        // Resets the screen to pure black on a revisit
        toolbar.visibility = View.INVISIBLE
        paginationLayout.visibility = View.INVISIBLE
        buttonGrid.visibility = View.INVISIBLE
        verifyBtn.visibility = View.INVISIBLE

        // Cancel any old running jobs just to be safe
        countdownJob?.cancel()

        // Assign the launch to our Job variable so we can kill it later
        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            // Wait for the admin entry warning TTS to finish talking
            while (mainActivity.isVoiceBusy) {
                delay(200)
            }

            if (!isAdded) return@launch

            // Enable ONLY the verify button now that talking is done
            verifyBtn.visibility = View.VISIBLE

            // Start the 5-second countdown beeps
            for (i in 0..4) {
                // We detect if the user hit the verify button in time
                if (stay) {
                    break
                } else {
                    mainActivity.soundPool?.play(mainActivity.beepId, 1f, 1f, 0, 0, 1f)
                    if (!isAdded) return@launch
                    delay(1000)
                }
            }

            if (!isAdded) return@launch

            // The user confirmed he wants to stay
            if (stay) {
                verifyBtn.visibility = View.INVISIBLE

                // Reveals the entire dev screen
                toolbar.visibility = View.VISIBLE
                paginationLayout.visibility = View.VISIBLE
                buttonGrid.visibility = View.VISIBLE

                mainActivity.updateSlidingWindow()
                mainActivity.speak(mainActivity.stayMessage)
            } else {
                // Time's up => kick user back to main screen
                verifyBtn.visibility = View.INVISIBLE
                mainActivity.updateSlidingWindow()
                mainActivity.speak(mainActivity.backMessage)
                activity?.findViewById<View>(R.id.fragment_dev)?.visibility = View.GONE
            }
        }
    }

    private fun appendLog(message: String) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timestamp = timeFormat.format(Date())
        val newLog = "[$timestamp] $message\n"

        // Append text to the TextView
        logTextView.append(newLog)

        // Auto-scroll to the bottom of the ScrollView
        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}