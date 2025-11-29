package com.example.testing

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.testing.ui.theme.TestingTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.gson.Gson
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.RequestBody
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private val availableVoices = mutableStateListOf<Voice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.TAIWAN)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.CHINESE)
                }
                try {
                    val voices = tts?.voices
                    if (!voices.isNullOrEmpty()) {
                        val taiwanVoices = voices.filter {
                            it.locale.country == "TW" || it.locale.toString().contains("TW")
                        }.sortedBy { it.name }
                        availableVoices.clear()
                        availableVoices.addAll(taiwanVoices)
                    }
                } catch (e: Exception) {
                    Log.e("TTS", "Error fetching voices: ${e.message}")
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            TestingTheme(darkTheme = true) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SpeechToTextScreen(
                        modifier = Modifier.padding(innerPadding),
                        tts = tts,
                        availableVoices = availableVoices
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpeechToTextScreen(
    modifier: Modifier = Modifier,
    tts: TextToSpeech?,
    availableVoices: List<Voice>
) {
    val context = LocalContext.current
    val recordAudioPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    val db = remember { AppDatabase.getDatabase(context) }
    val speechRecognizer = remember { SpeechRecognizerUtil(context, db.transcriptionDao()) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var manualInputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val vibrator = remember {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showManualInput by remember { mutableStateOf(false) }

    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }

    val highestRiskItem by remember {
        derivedStateOf {
            speechRecognizer.transcriptionHistory.value
                .filter { it.riskScore != null }
                .maxByOrNull { it.riskScore!! }
        }
    }

    val maxRiskScore = highestRiskItem?.riskScore ?: 0
    val maxRiskAdvice = highestRiskItem?.advice
    val isRiskLoading = highestRiskItem?.isAdviceLoading ?: false

    LaunchedEffect(speechRecognizer.transcriptionHistory.value.size) {
        if (speechRecognizer.transcriptionHistory.value.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(highestRiskItem) {
        val item = highestRiskItem
        if (item != null && item.riskScore != null && item.riskScore > 70) {
            if (!item.advice.isNullOrBlank() && !item.isAdviceLoading) {
                // UPDATED: Check configuration before vibrating
                if (AlertConfig.isVibrationEnabled) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), -1))
                }

                if (TtsConfig.isEnabled) {
                    if (TtsConfig.currentVoiceName.isNotBlank()) {
                        val voice = availableVoices.find { it.name == TtsConfig.currentVoiceName }
                        if (voice != null) {
                            tts?.voice = voice
                        }
                    }
                    tts?.speak("注意！偵測到高風險詐騙內容。AI建議：${item.advice}", TextToSpeech.QUEUE_FLUSH, null, "ALERT_${item.id}")
                }
            }
        }
    }

    fun checkScamRisk(id: String, textToCheck: String) {
        if (textToCheck.length < 6) {
            Log.d("ScamCheck", "Text ignored (too short): $textToCheck")
            return
        }

        scope.launch {
            try {
                // STEP 1: FAST Request
                val request = ScamCheckRequest(message = textToCheck)
                val response = RetrofitClientFast.instance.checkMessage(request)

                val score = (response.scam_probability * 100).toInt()
                speechRecognizer.updateRisk(id, score, null)

                // STEP 2: SLOW Request (Advice)
                if (score > 50) {
                    speechRecognizer.setAdviceLoading(id, true)

                    try {
                        val provider = ServerConfigAdvice.currentProvider
                        var adviceText = "無法取得建議"

                        // --- RAW JSON MODE LOGIC ---
                        if (provider.isRawJsonMode && provider.rawJsonTemplate.isNotBlank()) {
                            val fullUrl = provider.baseUrl

                            val headers = mutableMapOf("Content-Type" to "application/json")
                            if (provider.useAuthHeader && provider.apiKey.isNotBlank()) {
                                headers["Authorization"] = "Bearer ${provider.apiKey}"
                            }

                            val safeText = textToCheck.replace("\"", "\\\"").replace("\n", "\\n")
                            val jsonBodyString = provider.rawJsonTemplate.replace("{{TEXT}}", safeText)

                            val requestBody = RequestBody.create(
                                MediaType.parse("application/json; charset=utf-8"),
                                jsonBodyString
                            )

                            val rawResponse = RetrofitClientSlow.instance.getRawAdvice(fullUrl, headers, requestBody)
                            val rawResponseString = rawResponse.string()

                            try {
                                val gson = Gson()
                                val parsedObj = gson.fromJson(rawResponseString, AdviceResponse::class.java)
                                val content = parsedObj.choices?.firstOrNull()?.message?.content
                                if (!content.isNullOrBlank()) {
                                    adviceText = content
                                } else {
                                    adviceText = "Raw: " + rawResponseString.take(100) + "..."
                                }
                            } catch (e: Exception) {
                                adviceText = "Raw: " + rawResponseString.take(150)
                            }

                        } else {
                            // --- STANDARD OPENAI MODE ---
                            val endpoint = if (provider.baseUrl.endsWith("/")) "chat/completions" else "/chat/completions"
                            var fullUrl = provider.baseUrl + endpoint
                            val authHeader: String?
                            if (provider.useAuthHeader) {
                                authHeader = "Bearer ${provider.apiKey}"
                            } else {
                                fullUrl += "?token=${provider.apiKey}"
                                authHeader = null
                            }

                            val adviceResponse = RetrofitClientSlow.instance.getAdvice(
                                url = fullUrl,
                                auth = authHeader,
                                request = RetrofitClientSlow.convert(request)
                            )

                            adviceText = adviceResponse.choices?.firstOrNull()?.message?.content
                                ?: "無法取得建議"
                        }

                        adviceText = adviceText.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
                        speechRecognizer.updateAdvice(id, adviceText)

                    } catch (e: Exception) {
                        Log.e("ScamCheck", "Advice Error: ${e.message}")
                        speechRecognizer.updateAdvice(id, "Error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("ScamCheck", "Prediction Error: ${e.message}")
            }
        }
    }

    fun combineAndEvaluate() {
        val allItems = speechRecognizer.transcriptionHistory.value
        val selectedItems = allItems.filter { it.id in selectedIds }

        if (selectedItems.isNotEmpty()) {
            val sortedItems = selectedItems.reversed()
            val combinedText = sortedItems.joinToString("，") { it.text }

            val newId = speechRecognizer.addCombinedTranscription(combinedText)
            checkScamRisk(newId, combinedText)

            selectedIds.forEach { id ->
                speechRecognizer.deleteTranscription(id)
            }
            selectedIds.clear()
            isSelectionMode = false
        }
    }

    LaunchedEffect(speechRecognizer.transcriptionHistory.value.size) {
        val history = speechRecognizer.transcriptionHistory.value
        if (history.isNotEmpty()) {
            val latestItem = history.first()
            if (latestItem.riskScore == null && latestItem.text.isNotBlank()) {
                checkScamRisk(latestItem.id, latestItem.text)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { speechRecognizer.destroy() }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            initialShowManualInput = showManualInput,
            availableVoices = availableVoices,
            onDismiss = { showSettingsDialog = false },
            // UPDATED: Handle the new vibration boolean parameter
            onConfirm = { newUrl, newShowManualInput, newAdviceProvider, newAsrProvider, newYatingKey, newTtsEnabled, newVoiceName, newVibrationEnabled ->
                RetrofitClientSlow.updateSettings(newUrl, newAdviceProvider, newAsrProvider, newYatingKey)
                TtsConfig.isEnabled = newTtsEnabled
                TtsConfig.currentVoiceName = newVoiceName
                AlertConfig.isVibrationEnabled = newVibrationEnabled // Update config
                showManualInput = newShowManualInput
                showSettingsDialog = false
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp)
        ) {
            IconButton(
                onClick = { showSettingsDialog = true },
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.primary)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("即時語音轉錄", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                AudioVisualizer(
                    isRecording = speechRecognizer.isRecording.value,
                    soundLevel = speechRecognizer.soundLevel.floatValue,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                speechRecognizer.errorState.value?.let { error ->
                    Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                }
                Crossfade(targetState = speechRecognizer.recognizedText.value to speechRecognizer.partialText.value, label = "speech-text") { (recognized, partial) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (recognized.isNotEmpty()) {
                            Text(text = recognized, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                        if (speechRecognizer.isRecording.value && partial.isNotEmpty()) {
                            if (recognized.isNotEmpty()) Spacer(modifier = Modifier.height(8.dp))
                            Text(text = partial, fontSize = 18.sp, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                        if (recognized.isEmpty() && partial.isEmpty()) {
                            Text(
                                text = if (speechRecognizer.isRecording.value) "聆聽中..." else "點擊錄音按鈕開始說話...",
                                fontSize = 16.sp,
                                color = if (speechRecognizer.isRecording.value) MaterialTheme.colorScheme.primary else Color.Gray,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (recordAudioPermission.status.isGranted) {
                            if (speechRecognizer.isRecording.value) speechRecognizer.stopRecording() else speechRecognizer.startRecording()
                        } else {
                            recordAudioPermission.launchPermissionRequest()
                        }
                    },
                    modifier = Modifier.size(80.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = if (speechRecognizer.isRecording.value) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Icon(
                        painter = painterResource(id = if (speechRecognizer.isRecording.value) android.R.drawable.ic_media_pause else android.R.drawable.ic_btn_speak_now),
                        contentDescription = if (speechRecognizer.isRecording.value) "停止" else "錄音",
                        modifier = Modifier.size(40.dp),
                        tint = if (speechRecognizer.isRecording.value) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showManualInput,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualInputText,
                        onValueChange = { manualInputText = it },
                        label = { Text("手動輸入文字") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (manualInputText.isNotBlank()) {
                                speechRecognizer.addManualTranscription(manualInputText)
                                manualInputText = ""
                                keyboardController?.hide()
                            }
                        })
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (manualInputText.isNotBlank()) {
                                speechRecognizer.addManualTranscription(manualInputText)
                                manualInputText = ""
                                keyboardController?.hide()
                            }
                        },
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(painter = painterResource(id = android.R.drawable.ic_menu_send), contentDescription = "Send")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { isSelectionMode = false; selectedIds.clear() }) {
                        Icon(painterResource(android.R.drawable.ic_menu_close_clear_cancel), "Cancel")
                    }
                    Text(text = "已選擇 ${selectedIds.size} 筆", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { combineAndEvaluate() },
                    enabled = selectedIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("合併分析") }
            } else {
                Text(text = "對話紀錄", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (speechRecognizer.transcriptionHistory.value.isNotEmpty()) {
                    Button(
                        onClick = { speechRecognizer.clearHistory() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(painter = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel), contentDescription = "清除紀錄", modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                        Text(text = "清除", color = Color.White)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (speechRecognizer.transcriptionHistory.value.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(text = "尚無紀錄", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = listState,
                reverseLayout = false
            ) {
                items(
                    items = speechRecognizer.transcriptionHistory.value,
                    key = { it.id }
                ) { transcription ->
                    val isSelected = transcription.id in selectedIds
                    TranscriptionItem(
                        transcription = transcription,
                        isSelectionMode = isSelectionMode,
                        isSelected = isSelected,
                        onToggleSelection = {
                            if (!isSelectionMode) {
                                isSelectionMode = true
                                selectedIds.add(transcription.id)
                            } else {
                                if (isSelected) {
                                    selectedIds.remove(transcription.id)
                                    if (selectedIds.isEmpty()) isSelectionMode = false
                                } else {
                                    selectedIds.add(transcription.id)
                                }
                            }
                        },
                        onUpdate = { newText ->
                            speechRecognizer.updateTranscription(transcription.id, newText)
                            checkScamRisk(transcription.id, newText)
                        },
                        onDelete = { speechRecognizer.deleteTranscription(transcription.id) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        if (speechRecognizer.transcriptionHistory.value.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            RiskLevelMeter(score = maxRiskScore, highestRiskAdvice = maxRiskAdvice, isLoading = isRiskLoading)
        }
    }
}