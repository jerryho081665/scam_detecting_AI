package com.example.testing

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

data class Transcription(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val riskScore: Int? = null,
    val advice: String? = null
)

class SpeechRecognizerUtil(private val context: Context) {
    private var speechRecognizer: SpeechRecognizer? = SpeechRecognizer.createSpeechRecognizer(context)

    // SETTINGS
    val currentLanguage = mutableStateOf("zh-TW")

    // STATES
    // "isRecording" now means: Is the App in "Recording Mode"? (User pressed Start)
    val isRecording = mutableStateOf(false)

    // "isListening" means: Is the Microphone actually open right now?
    private val isListeningInternal = mutableStateOf(false)

    val recognizedText = mutableStateOf("")
    val partialText = mutableStateOf("")
    val errorState = mutableStateOf<String?>(null)
    val transcriptionHistory = mutableStateOf<List<Transcription>>(emptyList())

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListeningInternal.value = true
            errorState.value = null
        }

        override fun onBeginningOfSpeech() {
            partialText.value = "聆聽中..."
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            isListeningInternal.value = false
        }

        override fun onError(error: Int) {
            isListeningInternal.value = false
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissions error"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout"
                else -> "Unknown error"
            }
            Log.e("SpeechRecognizer", "Error: $message ($error)")

            // OPTIMIZATION: Only stop for critical errors.
            // For Timeouts or No Match, we just restart immediately.
            if (isRecording.value) {
                when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        restartListening() // Auto-restart on silence
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // Wait a tiny bit then retry
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(500)
                            restartListening()
                        }
                    }
                    else -> {
                        // Critical error, stop recording to prevent infinite loops
                        isRecording.value = false
                        errorState.value = message
                    }
                }
            }
        }

        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let {
                if (it.isNotEmpty()) {
                    val finalText = it[0]

                    // Only update if we got meaningful text (not empty or very short)
                    if (finalText.isNotBlank() && finalText.length > 1) {
                        recognizedText.value = finalText
                        partialText.value = ""

                        if (finalText.isNotBlank()) {
                            val newHistory = transcriptionHistory.value.toMutableList()
                            newHistory.add(0, Transcription(text = finalText))
                            transcriptionHistory.value = newHistory
                        }
                    }
                }
            }

            // OPTIMIZATION: Add a small delay before restarting to prevent flickering
            if (isRecording.value) {
                CoroutineScope(Dispatchers.Main).launch {
                    delay(300) // Small delay to stabilize the UI
                    restartListening()
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let {
                if (it.isNotEmpty()) {
                    val partial = it[0]
                    // Only update if we have meaningful partial text
                    if (partial.isNotBlank() && partial.length > 1) {
                        partialText.value = partial
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    init {
        speechRecognizer?.setRecognitionListener(recognitionListener)
    }

    private fun createIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage.value)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

            // --- OPTIMIZATION START ---
            // Allow 10 seconds of silence before the system cuts off (Default is usually very short)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            // Minimum length of recording to consider valid
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
            // --- OPTIMIZATION END ---
        }
    }

    fun startRecording() {
        if (isRecording.value) return // Already recording

        isRecording.value = true
        errorState.value = null
        startListening()
    }

    private fun startListening() {
        // Run on Main thread to be safe
        CoroutineScope(Dispatchers.Main).launch {
            try {
                speechRecognizer?.startListening(createIntent())
            } catch (e: Exception) {
                Log.e("SpeechRec", "Start failed", e)
                restartRecognizer()
            }
        }
    }

    private fun restartListening() {
        if (!isRecording.value) return
        // Tiny delay to ensure the engine is ready
        CoroutineScope(Dispatchers.Main).launch {
            try {
                speechRecognizer?.startListening(createIntent())
            } catch(e: Exception) {
                restartRecognizer()
            }
        }
    }

    fun stopRecording() {
        isRecording.value = false
        isListeningInternal.value = false
        speechRecognizer?.stopListening()
        partialText.value = ""
    }

    // Sometimes the recognizer freezes, this hard-resets it
    private fun restartRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(recognitionListener)
        if (isRecording.value) {
            startListening()
        }
    }

    fun toggleLanguage() {
        val wasRecording = isRecording.value
        if (wasRecording) stopRecording()

        currentLanguage.value = when (currentLanguage.value) {
            "zh-TW" -> "en-US"
            else -> "zh-TW"
        }

        // Restart automatically if we were recording
        if (wasRecording) {
            startRecording()
        }
    }

    fun addManualTranscription(text: String) {
        if (text.isBlank()) return
        val newHistory = transcriptionHistory.value.toMutableList()
        newHistory.add(0, Transcription(text = text))
        transcriptionHistory.value = newHistory
    }

    fun updateTranscription(id: String, newText: String) {
        transcriptionHistory.value = transcriptionHistory.value.map {
            if (it.id == id) it.copy(text = newText) else it
        }
    }

    fun updateRisk(id: String, score: Int, advice: String?) {
        transcriptionHistory.value = transcriptionHistory.value.map {
            if (it.id == id) it.copy(riskScore = score, advice = advice) else it
        }
    }

    fun deleteTranscription(id: String) {
        transcriptionHistory.value = transcriptionHistory.value.filter { it.id != id }
    }

    fun clearHistory() {
        transcriptionHistory.value = emptyList()
    }

    fun clearCurrentTranscript() {
        recognizedText.value = ""
        partialText.value = ""
    }

    fun destroy() {
        speechRecognizer?.destroy()
    }
}