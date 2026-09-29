package com.example.via

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.shawnlin.numberpicker.NumberPicker

class AudioFragment : Fragment() {

    private val TAG = "AudioFragment"

    // Opens the app's private save file ("AudioPrefs") to remember speed settings
    private val prefs by lazy {
        requireContext().getSharedPreferences("AudioPrefs", Context.MODE_PRIVATE)
    }

    // Generate an array of decimal strings: ["0.05", "0.10", "0.15", ... , "20.00"]
    val speedOptions = (1..400).map { (it / 20f).toString() }.toTypedArray()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_audio, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get a reference to the MainActivity
        val mainActivity = activity as? MainActivity

        // Make the back arrow actually pop the fragment
        val toolbar = view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.audio_toolbar)
        toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // UI Elements & Buttons
        val saveBtn = view.findViewById<Button>(R.id.saveBtn)
        val exoPlayerNumberPicker: NumberPicker = view.findViewById(R.id.exoplayer_number_picker)

        // TODO: val ttsNumberPicker = view.findViewById<NumberPicker>(R.id.tts_number_picker)

        // Set number picker colors
        exoPlayerNumberPicker.setDividerColor(ContextCompat.getColor(requireContext(), R.color.greenTheme))
        exoPlayerNumberPicker.setSelectedTextColor(ContextCompat.getColor(requireContext(), R.color.greenTheme))
        exoPlayerNumberPicker.setTextColor(ContextCompat.getColor(requireContext(), R.color.text))

        // Set the min and max limits to match the size of the array
        exoPlayerNumberPicker.minValue = 0
        exoPlayerNumberPicker.maxValue = speedOptions.size - 1

        // Apply the custom decimal strings to the picker
        exoPlayerNumberPicker.displayedValues = speedOptions

        // Load the saved index. If it doesn't exist yet, default to 19 (1.0x speed)
        val savedIndex = prefs.getInt("saved_exo_index", 19)

        // Snap the picker to the saved index
        exoPlayerNumberPicker.value = savedIndex

        // Define the speed variable based on that loaded index
        var actualExoPlayerSpeed = speedOptions[savedIndex].toFloat()

        /**
         * Click logic
         */
        exoPlayerNumberPicker.setOnClickListener {
            val selectedIndex = exoPlayerNumberPicker.value
            actualExoPlayerSpeed = speedOptions[selectedIndex].toFloat()
            Log.d(TAG, String.format(java.util.Locale.US, "User tapped on speed: %.2f", actualExoPlayerSpeed))
        }

        /**
         * Value change logic
         */
        exoPlayerNumberPicker.setOnValueChangedListener(object : NumberPicker.OnValueChangeListener {
            override fun onValueChange(picker: NumberPicker?, oldVal: Int, newVal: Int) {
                actualExoPlayerSpeed = speedOptions[newVal].toFloat()
                Log.d(TAG, String.format(java.util.Locale.US, "Speed changed to: %.2f", actualExoPlayerSpeed))
            }
        })

        /**
         * Scroll logic
         */
        exoPlayerNumberPicker.setOnScrollListener(object : NumberPicker.OnScrollListener {
            override fun onScrollStateChange(picker: NumberPicker, scrollState: Int) {
                if (scrollState == NumberPicker.OnScrollListener.SCROLL_STATE_IDLE) {
                    actualExoPlayerSpeed = speedOptions[picker.value].toFloat()
                    Log.d(TAG, String.format(java.util.Locale.US, "Scroll idle at speed: %.2f", actualExoPlayerSpeed))
                }
            }
        })

        /**
         * Save logic
         */
        saveBtn.setOnClickListener {
            val newParameters = androidx.media3.common.PlaybackParameters(actualExoPlayerSpeed, 1.0f)
            mainActivity?.mediaController?.playbackParameters = newParameters

            // Chain the puts together and end with .commit() which returns a Boolean
            val isSaved = prefs.edit()
                .putInt("saved_exo_index", exoPlayerNumberPicker.value)
                .putFloat("saved_exo_speed", actualExoPlayerSpeed)
                .commit()

            // Check if it saved correctly
            if (isSaved) {
                Toast.makeText(context, "Saved!", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Speed applied and saved to SharedPreferences")
            } else {
                Toast.makeText(context, "Failed to save!", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "ERROR: Failed to save speed to SharedPreferences.")
            }
        }
    }
}