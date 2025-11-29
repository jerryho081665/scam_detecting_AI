package com.example.testing

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

data class Transcription(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val riskScore: Int? = null,
    val advice: String? = null,
    val isAdviceLoading: Boolean = false
)

class SpeechRecognizerUtil(private val context: Context) {
    // --- GOOGLE NATIVE COMPONENTS ---
    private var speechRecognizer: SpeechRecognizer? = SpeechRecognizer.createSpeechRecognizer(context)

    // --- YATING / WEBSOCKET COMPONENTS ---
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    // Audio Configuration for Yating (16kHz, Mono, PCM 16bit)
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 2

    // --- SETTINGS ---
    val currentLanguage = mutableStateOf("zh-TW")

    // --- UI STATES ---
    val isRecording = mutableStateOf(false)
    private val isListeningInternal = mutableStateOf(false)

    val soundLevel = mutableFloatStateOf(0f)
    val recognizedText = mutableStateOf("")
    val partialText = mutableStateOf("")
    val errorState = mutableStateOf<String?>(null)
    val transcriptionHistory = mutableStateOf<List<Transcription>>(emptyList())

    // =========================================================================
    // 1. GOOGLE RECOGNIZER IMPLEMENTATION
    // =========================================================================
    private val googleRecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListeningInternal.value = true
            errorState.value = null
        }
        override fun onBeginningOfSpeech() { partialText.value = "" }
        override fun onRmsChanged(rmsdB: Float) {
            val minDb = -2f
            val maxDb = 10f
            val normalized = ((rmsdB - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
            soundLevel.floatValue = normalized
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            if (ServerConfigAsr.currentProvider.id == "google") {
                isListeningInternal.value = false
                soundLevel.floatValue = 0f
            }
        }
        override fun onError(error: Int) {
            if (ServerConfigAsr.currentProvider.id != "google") return

            isListeningInternal.value = false
            soundLevel.floatValue = 0f

            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout"
                else -> "Error: $error"
            }
            Log.e("SpeechRecognizer", "Google Error: $message")

            if (isRecording.value) {
                when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_NO_MATCH -> { restartListening() }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        CoroutineScope(Dispatchers.Main).launch { delay(500); restartListening() }
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
                if (it.isNotEmpty()) handleFinalResult(it[0])
            }
            if (isRecording.value) {
                CoroutineScope(Dispatchers.Main).launch { delay(300); restartListening() }
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let {
                if (it.isNotEmpty()) partialText.value = it[0]
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    init {
        speechRecognizer?.setRecognitionListener(googleRecognitionListener)
    }

    // =========================================================================
    // 2. YATING IMPLEMENTATION (WebSocket + AudioRecord)
    // =========================================================================

    private fun startYatingListening() {
        errorState.value = null
        val apiKey = ServerConfigAsr.yatingApiKey.trim()

        if (apiKey.isBlank()) {
            errorState.value = "請輸入 Yating API Key"
            isRecording.value = false
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("Yating", "Fetching Auth Token...")
                val token = fetchYatingToken(apiKey)

                if (token == null) {
                    CoroutineScope(Dispatchers.Main).launch {
                        errorState.value = "Yating 驗證失敗: 無法取得 Token"
                        stopRecording()
                    }
                    return@launch
                }

                connectYatingWebSocket(token)

            } catch (e: Exception) {
                Log.e("Yating", "Start Error: ${e.message}")
                CoroutineScope(Dispatchers.Main).launch {
                    errorState.value = "啟動失敗: ${e.message}"
                    stopRecording()
                }
            }
        }
    }

    private fun fetchYatingToken(apiKey: String): String? {
        try {
            val pipeline = when (currentLanguage.value) {
                "en-US" -> "asr-en-std"
                else -> "asr-zh-en-std"
            }

            val jsonBody = JSONObject().apply {
                put("pipeline", pipeline)
            }

            val mediaType = MediaType.parse("application/json; charset=utf-8")
            val body = RequestBody.create(mediaType, jsonBody.toString())

            val request = Request.Builder()
                .url("https://asr.api.yating.tw/v1/token")
                .addHeader("key", apiKey)
                .post(body)
                .build()

            val response = client.newCall(request).execute()

            // --- FIXED: Use method calls instead of property access ---
            if (!response.isSuccessful) {
                Log.e("Yating", "Token Fail: ${response.code()} ${response.body()?.string()}")
                return null
            }

            val responseBody = response.body()?.string() ?: return null
            val json = JSONObject(responseBody)
            return json.optString("auth_token", null)

        } catch (e: Exception) {
            Log.e("Yating", "Token Exception: ${e.message}")
            return null
        }
    }

    private fun connectYatingWebSocket(token: String) {
        try {
            Log.d("Yating", "Connecting to WebSocket...")
            val request = Request.Builder()
                .url("wss://asr.api.yating.tw/ws/v1/?token=$token")
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("Yating", "WebSocket Opened")
                    startStreamingAudio(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        val pipe = json.optJSONObject("pipe")

                        if (pipe != null) {
                            val transcript = pipe.optString("asr_sentence")
                            val isFinal = pipe.optBoolean("asr_final", false)

                            CoroutineScope(Dispatchers.Main).launch {
                                if (isFinal) {
                                    if (transcript.isNotBlank()) {
                                        handleFinalResult(transcript)
                                    }
                                } else {
                                    partialText.value = transcript
                                }
                            }
                        } else {
                            val status = json.optString("status")
                            if (status == "error") {
                                val detail = json.optString("detail")
                                Log.e("Yating", "API Error: $detail")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Yating", "Json Parse Error: ${e.message}")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("Yating", "Closing: $code / $reason")
                    CoroutineScope(Dispatchers.Main).launch {
                        if (code != 1000) {
                            errorState.value = "連線關閉: $reason"
                        }
                        stopRecording()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("Yating", "Failure: ${t.message}")
                    CoroutineScope(Dispatchers.Main).launch {
                        errorState.value = "連線失敗: ${t.localizedMessage}"
                        stopRecording()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("Yating", "WS Init Error: ${e.message}")
            CoroutineScope(Dispatchers.Main).launch {
                errorState.value = "WS Error: ${e.message}"
                isRecording.value = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startStreamingAudio(ws: WebSocket) {
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("Yating", "Starting AudioRecord...")
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    BUFFER_SIZE
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("Yating", "AudioRecord init failed")
                    CoroutineScope(Dispatchers.Main).launch {
                        errorState.value = "麥克風初始化失敗"
                        stopRecording()
                    }
                    return@launch
                }

                audioRecord?.startRecording()
                Log.d("Yating", "AudioRecord started successfully")
                isListeningInternal.value = true

                val buffer = ByteArray(BUFFER_SIZE)

                while (isActive && isRecording.value) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val byteString = ByteString.of(buffer, 0, read)
                        ws.send(byteString)

                        // Calculate Volume for UI
                        var sum = 0.0
                        for (i in 0 until read step 2) {
                            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i+1].toInt() shl 8)
                            val shortSample = sample.toShort()
                            sum += shortSample * shortSample
                        }
                        val rms = sqrt(sum / (read / 2))
                        val normalized = (rms / 2000.0).coerceIn(0.0, 1.0).toFloat()
                        soundLevel.floatValue = normalized
                    }
                }
            } catch (e: Exception) {
                Log.e("Yating", "Streaming Error: ${e.message}")
                CoroutineScope(Dispatchers.Main).launch {
                    errorState.value = "錄音錯誤: ${e.message}"
                    stopRecording()
                }
            } finally {
                stopYatingInternal()
            }
        }
    }

    private fun stopYatingListening() {
        recordingJob?.cancel()
        stopYatingInternal()
    }

    private fun stopYatingInternal() {
        try {
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.stop()
            }
            audioRecord?.release()
            audioRecord = null

            webSocket?.close(1000, "User stopped")
            webSocket = null
        } catch (e: Exception) {
            Log.e("Yating", "Stop Error: ${e.message}")
        }
        isListeningInternal.value = false
    }

    // =========================================================================
    // 3. SHARED LOGIC & CONTROLS
    // =========================================================================

    private fun handleFinalResult(text: String) {
        if (text.isNotBlank()) {
            val newHistory = transcriptionHistory.value.toMutableList()
            newHistory.add(0, Transcription(text = text))
            transcriptionHistory.value = newHistory
            recognizedText.value = ""
            partialText.value = ""
        }
    }

    fun startRecording() {
        if (isRecording.value) return
        isRecording.value = true
        errorState.value = null
        startListening()
    }

    private fun startListening() {
        val provider = ServerConfigAsr.currentProvider
        Log.d("SpeechRecognizer", "Starting with provider: ${provider.name}")

        if (provider.id == "yating") {
            startYatingListening()
        } else {
            startGoogleListening()
        }
    }

    private fun restartListening() {
        if (!isRecording.value) return
        startListening()
    }

    fun stopRecording() {
        isRecording.value = false
        soundLevel.floatValue = 0f
        partialText.value = ""

        speechRecognizer?.stopListening()
        stopYatingListening()
    }

    private fun startGoogleListening() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                speechRecognizer?.startListening(createIntent())
            } catch (e: Exception) {
                restartRecognizer()
            }
        }
    }

    private fun createIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage.value)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                putExtra("android.speech.extra.AUDIO_SOURCE", 9) // UNPROCESSED
            } else {
                putExtra("android.speech.extra.AUDIO_SOURCE", 6)
            }
        }
    }

    private fun restartRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(googleRecognitionListener)
        if (isRecording.value && ServerConfigAsr.currentProvider.id == "google") {
            startGoogleListening()
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

    // --- History Helper Functions ---
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

    fun setAdviceLoading(id: String, isLoading: Boolean) {
        transcriptionHistory.value = transcriptionHistory.value.map {
            if (it.id == id) it.copy(isAdviceLoading = isLoading) else it
        }
    }

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
        stopYatingListening()
    }

    fun addCombinedTranscription(text: String): String {
        val newId = UUID.randomUUID().toString()
        val newHistory = transcriptionHistory.value.toMutableList()
        newHistory.add(0, Transcription(id = newId, text = text))
        transcriptionHistory.value = newHistory
        return newId
    }
}