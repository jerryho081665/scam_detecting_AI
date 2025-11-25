package com.example.testing

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.mutableStateOf
import java.util.UUID

data class Transcription(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val riskScore: Int? = null // null means "not analyzed yet"
)
class SpeechRecognizerUtil(context: Context) {
    private val speechRecognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    val currentLanguage = mutableStateOf("en-US")
    val isRecording = mutableStateOf(false)
    val recognizedText = mutableStateOf("")
    val partialText = mutableStateOf("")
    val errorState = mutableStateOf<String?>(null)
    val transcriptionHistory = mutableStateOf<List<Transcription>>(emptyList())

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isRecording.value = true
            recognizedText.value = ""
            partialText.value = ""
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
        }

        override fun onError(error: Int) {
            errorState.value = when (error) {
                SpeechRecognizer.ERROR_CLIENT -> "error"
                SpeechRecognizer.ERROR_NO_MATCH -> ""
                else -> "Unknown error"
            }
            if(isRecording.value){
                isRecording.value = false
                if (error != SpeechRecognizer.ERROR_CLIENT) {

                    startListening()
                }
            }

        }

        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let {
                if (it.isNotEmpty()) {
                    val finalText = it[0]
                    recognizedText.value = finalText
                    partialText.value = ""

                    if (finalText.isNotBlank()) {
                        val newHistory = transcriptionHistory.value.toMutableList()
                        newHistory.add(0, Transcription(text = finalText))
                        transcriptionHistory.value = newHistory
                    }
                }
            }

            startListening()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let {
                if (it.isNotEmpty()) {
                    partialText.value = it[0]
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    init {
        speechRecognizer.setRecognitionListener(recognitionListener)
    }

    fun startListening() {
        errorState.value = null
        partialText.value = ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage.value)


            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

        }
        speechRecognizer.startListening(intent)
    }

    fun deleteTranscription(id: String) {
        transcriptionHistory.value = transcriptionHistory.value.filter { it.id != id }
    }

    fun toggleLanguage() {
        currentLanguage.value = when (currentLanguage.value) {
            "en-US" -> "zh-TW"
            else -> "en-US"
        }
    }

    fun updateTranscription(id: String, newText: String) {
        transcriptionHistory.value = transcriptionHistory.value.map {
            if (it.id == id) it.copy(text = newText) else it
        }
    }

    fun updateRisk(id: String, score: Int) {
        transcriptionHistory.value = transcriptionHistory.value.map {
            if (it.id == id) it.copy(riskScore = score) else it
        }
    }

    fun stopListening() {
        speechRecognizer.stopListening()
    }

    fun clearHistory() {
        transcriptionHistory.value = emptyList()
    }

    fun destroy() {
        speechRecognizer.destroy()
    }
}