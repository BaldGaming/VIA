# VIA — Accessible Audio Player

<img height="600" alt="VIA App Interface" src="https://github.com/user-attachments/assets/61c24932-b291-4a93-a16a-21b957edfc77" />

## About The Project
VIA is a bespoke Android application created specifically for a friend's grandfather who recently lost nearly all of his sight. Because he can now only distinguish colors and light, standard smartphone interfaces and audio players are entirely inaccessible to him. 

This app strips away complex menus, lists, and tiny text, replacing them with a purely color-coded, gesture-driven interface backed by a smart Text-to-Speech (TTS) engine. It is built to give him his independence back, allowing him to easily listen to his audio files, navigate channels, and hear his progress without needing to see the screen.

## Core Features
* **Color & Light-Based UI:** The entire interface consists of massive, brightly colored buttons (Green, Red, Blue, Yellow, Pink, White). 
* **Gesture & Audio Navigation:** Users navigate via taps, long-presses, and dual-holds. Every action triggers haptic feedback and an audio confirmation in Hebrew via Azure's Neural TTS.
* **Smart Cloud Streaming:** Audio files are stored remotely on Dropbox and streamed directly to the device. The app routinely checks for new files and alerts the user when new content is added.
* **Automated Bookmarking:** The app remembers the exact playback position of every file. If the app is closed, interrupted, or a file is skipped, it resumes right where the user left off (with a 3-second rewind for context).
* **"Heard" Tracking:** Files are automatically marked as completed upon reaching 98% playback. This status is synced back to Dropbox (via `.h` marker files) to maintain state across devices or fresh installs.

## How It Works Under the Hood
While the front-end is intentionally simple, the back-end relies on modern Android architecture to ensure seamless, battery-efficient background playback:

* **Audio Engine (ExoPlayer):** Handles robust streaming of remote Dropbox URLs, managing audio focus (pausing during phone calls) and custom WakeLocks to prevent the CPU from sleeping while streaming.
* **API Management (Retrofit & Coroutines):** Retrofit handles all secure communications with the Dropbox API (for fetching temporary streaming links and syncing directory trees) and the Azure Cognitive Services API (for generating the Hebrew TTS). All network requests are handled safely off the main thread using Kotlin Coroutines.
* **Sliding Window Caching:** To minimize Azure API costs and reduce latency, the app calculates a 31-track "sliding window" around the user's current position and silently pre-fetches the Text-to-Speech audio files into local storage.
* **Offline Fallback:** If the device loses internet connection, the app automatically fails over from the high-quality Azure Neural voice to the native offline Android Text-to-Speech engine.

## Tech Stack
* **Language:** Kotlin
* **Framework:** Android SDK (API 21+)
* **Media:** AndroidX Media3 (ExoPlayer)
* **Networking:** Retrofit, OkHttp, Gson
* **Cloud Services:** Dropbox API (Storage/Streaming), Azure Cognitive Services (TTS)
