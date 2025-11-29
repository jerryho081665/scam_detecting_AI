package com.example.testing

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

// Note: Transcription data class is imported from Database.kt

class SpeechRecognizerUtil(private val context: Context, private val dao: TranscriptionDao) {

    // --- GOOGLE NATIVE COMPONENTS ---
    private var speechRecognizer: SpeechRecognizer? = SpeechRecognizer.createSpeechRecognizer(context)

    // --- YATING / WEBSOCKET COMPONENTS ---
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    // Audio Configuration
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 2

    // --- REMOVED: currentLanguage state ---

    // --- UI STATES ---
    val isRecording = mutableStateOf(false)
    private val isListeningInternal = mutableStateOf(false)

    val soundLevel = mutableFloatStateOf(0f)
    val recognizedText = mutableStateOf("")
    val partialText = mutableStateOf("")
    val errorState = mutableStateOf<String?>(null)

    // DB History State
    val transcriptionHistory = mutableStateOf<List<Transcription>>(emptyList())

    // Scope for DB operations
    private val scope = CoroutineScope(Dispatchers.Main)

    // =========================================================================
    // 1. GOOGLE RECOGNIZER LISTENER
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

    // =========================================================================
    // 2. INIT BLOCK
    // =========================================================================
    init {
        speechRecognizer?.setRecognitionListener(googleRecognitionListener)

        // Observe Database Changes
        dao.getAllHistory().onEach { list ->
            transcriptionHistory.value = list
        }.launchIn(scope)
    }

    // =========================================================================
    // 3. YATING IMPLEMENTATION
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
            // --- UPDATED: Hardcoded to zh-en mixed pipeline (No English Mode) ---
            val pipeline = "asr-zh-en-std"

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

            if (!response.isSuccessful) {
                Log.e("Yating", "Token Fail: ${response.code()} ${response.body()?.string()}")
                return null
            }

            val responseBody = response.body()?.string() ?: return null
            val json = JSONObject(responseBody)
            return json.optString("auth_token", "")

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
                    CoroutineScope(Dispatchers.Main).launch {
                        if (code != 1000) errorState.value = "連線關閉: $reason"
                        stopRecording()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    CoroutineScope(Dispatchers.Main).launch {
                        errorState.value = "連線失敗: ${t.localizedMessage}"
                        stopRecording()
                    }
                }
            })
        } catch (e: Exception) {
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
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    BUFFER_SIZE
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    CoroutineScope(Dispatchers.Main).launch {
                        errorState.value = "麥克風初始化失敗"
                        stopRecording()
                    }
                    return@launch
                }

                audioRecord?.startRecording()
                isListeningInternal.value = true
                val buffer = ByteArray(BUFFER_SIZE)

                while (isActive && isRecording.value) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val byteString = ByteString.of(buffer, 0, read)
                        ws.send(byteString)
                        // Simple volume calc
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
        } catch (e: Exception) {}
        isListeningInternal.value = false
    }

    // =========================================================================
    // 4. SHARED LOGIC & DB INTEGRATION
    // =========================================================================

    private fun handleFinalResult(text: String) {
        if (text.isNotBlank()) {
            // NEW: Insert into DB instead of local list
            scope.launch(Dispatchers.IO) {
                dao.insert(Transcription(text = text))
            }
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
        if (provider.id == "yating") startYatingListening() else startGoogleListening()
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
            // --- UPDATED: Hardcoded to Traditional Chinese ---
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra("android.speech.extra.AUDIO_SOURCE", 9)
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

    // --- REMOVED: toggleLanguage function ---

    // --- History Helper Functions (Updated for DB) ---
    fun addManualTranscription(text: String) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) {
            dao.insert(Transcription(text = text))
        }
    }

    fun updateTranscription(id: String, newText: String) {
        scope.launch(Dispatchers.IO) {
            dao.updateText(id, newText)
        }
    }

    fun updateRisk(id: String, score: Int, advice: String?) {
        scope.launch(Dispatchers.IO) {
            dao.updateRisk(id, score, advice)
        }
    }

    fun setAdviceLoading(id: String, isLoading: Boolean) {
        scope.launch(Dispatchers.IO) {
            dao.updateLoading(id, isLoading)
        }
    }

    fun updateAdvice(id: String, advice: String) {
        scope.launch(Dispatchers.IO) {
            dao.updateAdvice(id, advice)
        }
    }

    fun deleteTranscription(id: String) {
        scope.launch(Dispatchers.IO) {
            dao.delete(id)
        }
    }

    fun clearHistory() {
        scope.launch(Dispatchers.IO) {
            dao.deleteAll()
        }
    }

    fun addCombinedTranscription(text: String): String {
        val newId = UUID.randomUUID().toString()
        scope.launch(Dispatchers.IO) {
            dao.insert(Transcription(id = newId, text = text))
        }
        return newId
    }

    fun destroy() {
        speechRecognizer?.destroy()
        stopYatingListening()
    }
}