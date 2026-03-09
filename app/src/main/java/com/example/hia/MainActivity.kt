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

class MainActivity : AppCompatActivity() {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initializes the TTS engine
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Sets language to Hebrew
                val result = tts?.setLanguage(Locale("he"))

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "TTS Error? *PLACEHOLDER*")
                }
            }
        }

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
            // TODO: add tap functionality
            Log.d("Button", "Green button tapped")
        }
        play_btn.setOnLongClickListener { // long press (about 500ms)
            Log.d("Button", "Green button held")
            // TODO: add long press functionality
            true
        }

        
        /**
         * Title logic
         */
        title_btn.setOnClickListener { // tap
            // TODO: add tap functionality
            Log.d("Button", "Red button tapped")
        }
        title_btn.setOnLongClickListener { // long press (about 500ms)
            Log.d("Button", "Red button held")
            speak("זאת בדיקה של השמעת הכפתורים")
            true
        }


        /**
         * Forward logic
         */
        forward_btn.setOnClickListener {
            // TODO: add tap functionality
            Log.d("Button", "Blue button tapped")
        }


        /**
         * Rewind logic
         */
        rewind_btn.setOnClickListener {
            // TODO: add tap functionality
            Log.d("Button", "Yellow button tapped")
        }


        /**
         * Next logic
         */
        next_btn.setOnClickListener {
            // TODO: add tap functionality
            Log.d("Button", "Purple button tapped")
        }


        /**
         * Previous logic
         */
        previous_btn.setOnClickListener {
            // TODO: add tap functionality
            Log.d("Button", "White button tapped")
        }
    }

    // TTS function
    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
    }

    // TTS cleanup when app stops
    override fun onDestroy() {
        if (tts != null) {
            tts?.stop()
            tts?.shutdown()
        }
        super.onDestroy()
    }
}