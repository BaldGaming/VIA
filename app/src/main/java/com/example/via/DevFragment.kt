package com.example.via

import android.os.Bundle
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

class DevFragment : Fragment() {

    // Declare the button in such a way it is visible for the whole class
    private lateinit var stayBtn: ImageButton // "lateinit" declares a non-nullable property without initializing it immediately when the project is created

    // Used for flagging if the user hit the stay button in time
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
        paginationLayout = view.findViewById(R.id.page_layout)
        buttonGrid = view.findViewById(R.id.button_grid)

        // Defines the buttons
        stayBtn = view.findViewById(R.id.button6)
        val logBtn = view.findViewById<Button>(R.id.btn_1)
        val audioBtn = view.findViewById<Button>(R.id.btn_2)
        val fileManagementBtn = view.findViewById<Button>(R.id.btn_3)
        val fileMarkingBtn = view.findViewById<Button>(R.id.btn_4)
        val offlineBtn = view.findViewById<Button>(R.id.btn_5)
        val statisticsBtn = view.findViewById<Button>(R.id.btn_6)

        // Instead of having 7 different screens, we create an empty bucket to put our fragment in
        val subFragmentContainer = view.findViewById<View>(R.id.dev_sub_fragment_container)

        // Hides the UI elements immediately so the screen is pure black
        randomizeStayCords()

        /**
         * Close logic
         */
        // Allows the user to exit the dev screen safely after the dashboard opens
        toolbar.setNavigationOnClickListener {
            activity?.findViewById<View>(R.id.fragment_dev)?.visibility = View.GONE
        }

        /**
         * Stay logic
         */
        stayBtn.setOnClickListener { // tap
            stay = true
            stayBtn.visibility = View.INVISIBLE
        }

        /**
         * Log button
         */
        logBtn.setOnClickListener {
            // Put the LogsFragment inside the bucket
            childFragmentManager.beginTransaction()
                .replace(R.id.dev_sub_fragment_container, LogsFragment())
                .commit()

            // Make the bucket visible
            subFragmentContainer.visibility = View.VISIBLE

            // TODO: MOVE THIS! -> appendLog("pressed \"log\"")
        }

        /**
         * Audio button
         */
        audioBtn.setOnClickListener {
            childFragmentManager.beginTransaction()
                .replace(R.id.dev_sub_fragment_container, AudioFragment())
                .commit()

            subFragmentContainer.visibility = View.VISIBLE
        }

        /**
         * File Management button
         */
        fileManagementBtn.setOnClickListener {
            childFragmentManager.beginTransaction()
                .replace(R.id.dev_sub_fragment_container, FileMarkingFragment())
                .commit()

            subFragmentContainer.visibility = View.VISIBLE
        }

        /**
         * File Marking button
         */
        fileMarkingBtn.setOnClickListener {
            childFragmentManager.beginTransaction()
                .replace(R.id.dev_sub_fragment_container, FileManagementFragment())
                .commit()

            subFragmentContainer.visibility = View.VISIBLE
        }

        /**
         * Offline button
         */
        offlineBtn.setOnClickListener {
            childFragmentManager.beginTransaction()
                .replace(R.id.dev_sub_fragment_container, OfflineFragment())
                .commit()

            subFragmentContainer.visibility = View.VISIBLE
        }

        /**
         * Statistics button
         */
        statisticsBtn.setOnClickListener {
            childFragmentManager.beginTransaction()
                .replace(R.id.dev_sub_fragment_container, StatisticsFragment())
                .commit()

            subFragmentContainer.visibility = View.VISIBLE
        }
    }

    // Function called only when the user hits the 7-tap trigger that boots the user out
    fun startAdminTimeoutSequence() {
        val mainActivity = activity as? MainActivity ?: return // Grab the Main Activity "bridge"

        stay = false

        // Resets the screen to pure black on a revisit
        randomizeStayCords()

        // Cancel any old running jobs just to be safe
        countdownJob?.cancel()

        // Assign the launch to our Job variable so we can kill it later
        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            // Wait for the admin entry warning TTS to finish talking
            while (mainActivity.isVoiceBusy) {
                if (stay) {
                    break
                }
                delay(200)
            }

            if (!isAdded) return@launch

            // Start the 5-second countdown beeps
            for (i in 0..4) {
                // We detect if the user hit the stay button in time
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
                stayBtn.visibility = View.INVISIBLE

                // Reveals the entire dev screen
                toolbar.visibility = View.VISIBLE
                paginationLayout.visibility = View.VISIBLE
                buttonGrid.visibility = View.VISIBLE
                
                mainActivity.speak(mainActivity.stayMessage)
            } else {
                // Time's up => kick user back to main screen
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

        // Append text to the TextView
        logTextView.append(newLog)

        // Auto-scroll to the bottom of the ScrollView
        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    // Function that enables the stayBtn and randomizes it's location
    private fun randomizeStayCords() {

        // Hides the standard UI elements
        toolbar.visibility = View.INVISIBLE
        paginationLayout.visibility = View.INVISIBLE
        buttonGrid.visibility = View.INVISIBLE

        // Sets a random location for the button
        stayBtn.post {

            // We grab the actual usable drawing space of the Fragment, avoiding system bars
            val parentView = view ?: return@post

            // Define a safety margin (in pixels) to keep it far from the absolute edges
            val margin = 120

            // We calculate max bounds using the parent view, subtracting the button size and our margin
            val maxX = parentView.width - stayBtn.width - margin
            val maxY = parentView.height - stayBtn.height - margin

            // Prevent a crash if the calculated bounds are weirdly small
            val safeMaxX = if (maxX > margin) maxX else margin
            val safeMaxY = if (maxY > margin) maxY else margin

            // Randomize the coordinates within the safe bounds
            val randomX = (margin..safeMaxX).random()
            val randomY = (margin..safeMaxY).random()

            // Assign the new coordinates
            stayBtn.x = randomX.toFloat()
            stayBtn.y = randomY.toFloat()

            // Reveal the button
            stayBtn.visibility = View.VISIBLE
        }
    }
}