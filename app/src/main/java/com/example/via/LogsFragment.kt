package com.example.via

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsFragment : Fragment() {

    // Declare the views
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var logsToolbar: androidx.appcompat.widget.Toolbar

    private val PREFS_NAME = "AppLogPrefs"
    private val KEY_LOGS = "saved_logs"
    private val KEY_LAST_DATE = "last_log_date"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_logs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        logTextView = view.findViewById(R.id.logTextView)
        logScrollView = view.findViewById(R.id.logScrollView)
        logsToolbar = view.findViewById(R.id.logs_toolbar)

        // Make the back arrow actually pop the fragment
        logsToolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Load existing logs when the app opens
        loadLogs()

        // Button
        val logButton = view.findViewById<Button>(R.id.logButton)
        logButton.setOnClickListener {
            // TODO: REPLACE THIS WITH AN ACTUAL LOG REPORT !!!
            appendLog("test")
        }
    }

    private fun loadLogs() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load the string and immediately trim any trailing invisible newlines
        val savedText = prefs.getString(KEY_LOGS, "")?.trim() ?: ""
        logTextView.text = savedText

        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    fun appendLog(message: String) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timestamp = timeFormat.format(Date())
        // Put an invisible Left-To-Right mark (\u200E) back so the brackets don't shift
        val newLog = "\u200E[$timestamp]: $message"

        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val datestamp = dateFormat.format(Date())
        val newDate = "---$datestamp---"

        // Grab the current text on screen first before changing anything
        val currentText = logTextView.text.toString().trim()

        // Define prefs
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Get the old date (default to an empty string)
        val oldDate = prefs.getString(KEY_LAST_DATE, "")

        // Simple String comparison to see if it's a new day
        val isNewDay = (datestamp != oldDate)

        // Build the new string manually
        val updatedText = if (currentText.isEmpty()) {
            if (isNewDay) {
                "$newDate\n$newLog"
            } else {
                newLog
            }
        } else if (isNewDay) {
            // Old logs first, gap, new date, new log
            "$currentText\n\n$newDate\n$newLog"
        } else {
            "$currentText\n$newLog"
        }

        // Overwrite the TextView entirely
        logTextView.text = updatedText

        // Save the trimmed string and the new date to storage
        prefs.edit {
            putString(KEY_LOGS, updatedText)

            // Only update the saved date if a new day actually started
            if (isNewDay) {
                putString(KEY_LAST_DATE, datestamp)
            }
        }

        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}