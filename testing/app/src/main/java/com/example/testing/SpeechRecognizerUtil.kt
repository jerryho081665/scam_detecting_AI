package com.example.testing

import android.content.Context
import android.content.Intent // Added import
import android.os.Bundle // Added import
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.util.Date
import java.text.SimpleDateFormat

// Data class to store transcription results
data class Transcription(
    val text: String,
    val timestamp: String = SimpleDateFormat("HH:mm:ss").format(Date())
)

class SpeechRecognizerUtil(context: Context) {
    private val speechRecognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

    // States
    val isRecording = mutableStateOf(false)
    val recognizedText = mutableStateOf("")
    val partialText = mutableStateOf("")
    val errorState = mutableStateOf<String?>(null)

    // History of transcriptions
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
            startListening()
        }

        override fun onError(error: Int) {
            if(isRecording.value == true){
                startListening()
            }
        }

        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let {
                if (it.isNotEmpty()) {
                    val finalText = it[0]
                    recognizedText.value = finalText
                    partialText.value = ""

                    // Add to history
                    if (finalText.isNotBlank()) {
                        val newHistory = transcriptionHistory.value.toMutableList()
                        newHistory.add(0, Transcription(finalText)) // Add at top
                        transcriptionHistory.value = newHistory
                    }
                }
            }
            isRecording.value = false
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
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)


        }
        speechRecognizer.startListening(intent)
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