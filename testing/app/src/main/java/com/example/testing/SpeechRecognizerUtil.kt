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
    val advice: String? = null,
    // NEW: Loading state for UI
    val isAdviceLoading: Boolean = false
)

class SpeechRecognizerUtil(private val context: Context) {
    private var speechRecognizer: SpeechRecognizer? = SpeechRecognizer.createSpeechRecognizer(context)

    // SETTINGS
    val currentLanguage = mutableStateOf("zh-TW")

    // STATES
    val isRecording = mutableStateOf(false)
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
            partialText.value = ""
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

            if (isRecording.value) {
                when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        restartListening()
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(500)
                            restartListening()
                        }
                    }
                    else -> {
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
                    if (finalText.isNotBlank() && finalText.length > 1) {
                        val newHistory = transcriptionHistory.value.toMutableList()
                        newHistory.add(0, Transcription(text = finalText))
                        transcriptionHistory.value = newHistory

                        recognizedText.value = ""
                        partialText.value = ""
                    }
                }
            }

            if (isRecording.value) {
                CoroutineScope(Dispatchers.Main).launch {
                    delay(300)
                    restartListening()
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let {
                if (it.isNotEmpty()) {
                    val partial = it[0]
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        }
    }

    fun startRecording() {
        if (isRecording.value) return
        isRecording.value = true
        errorState.value = null
        startListening()
    }

    private fun startListening() {
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

    // NEW: Set loading state
    fun setAdviceLoading(id: String, isLoading: Boolean) {
        transcriptionHistory.value = transcriptionHistory.value.map {
            if (it.id == id) it.copy(isAdviceLoading = isLoading) else it
        }
    }

    // UPDATE: Clear loading state when advice is set
    fun updateAdvice(id: String, advice: String) {
        transcriptionHistory.value = transcriptionHistory.value.map {
            if (it.id == id) it.copy(advice = advice, isAdviceLoading = false) else it
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