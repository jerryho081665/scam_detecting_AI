package com.example.testing

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.window.Dialog
import com.example.testing.ui.theme.TestingTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TestingTheme(darkTheme = true) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SpeechToTextScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpeechToTextScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val recordAudioPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val speechRecognizer = remember { SpeechRecognizerUtil(context) }
    val keyboardController = LocalSoftwareKeyboardController.current

    val scope = rememberCoroutineScope()
    var manualInputText by remember { mutableStateOf("") }

    // --- SETTINGS STATE ---
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showManualInput by remember { mutableStateOf(true) }

    // --- LOGIC: Calculate Highest Risk Item ---
    val highestRiskItem by remember {
        derivedStateOf {
            speechRecognizer.transcriptionHistory.value
                .filter { it.riskScore != null }
                .maxByOrNull { it.riskScore!! }
        }
    }

    val maxRiskScore = highestRiskItem?.riskScore ?: 0
    // We specifically extract the advice for the highest risk item
    val maxRiskAdvice = highestRiskItem?.advice

    fun checkScamRisk(id: String, textToCheck: String) {
        scope.launch {
            try {
                // STEP 1: FAST Request
                val request = ScamCheckRequest(message = textToCheck)
                val response = RetrofitClientFast.instance.checkMessage(request)

                val score = (response.scam_probability * 100).toInt()
                speechRecognizer.updateRisk(id, score, null)

                // STEP 2: SLOW Request (Advice)
                if (score > 70) {
                    try {
                        val adviceResponse = RetrofitClientSlow.instance.getAdvice(
                            RetrofitClientSlow.convert(request)
                        )

                        speechRecognizer.updateAdvice(id, adviceResponse.choices[0].message.content)


                    } catch (e: Exception) {
                        Log.e("ScamCheck", "Advice Error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("ScamCheck", "Prediction Error: ${e.message}")
            }
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

    val languageButtonText = when (speechRecognizer.currentLanguage.value) {
        "zh-TW" -> "中文"
        else -> "EN"
    }

    if (showSettingsDialog) {
        SettingsDialog(
            initialShowManualInput = showManualInput,
            onDismiss = { showSettingsDialog = false },
            onConfirm = { newUrl, newShowManualInput ->
                RetrofitClientSlow.updateUrl(newUrl)
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
        // --- 1. SPEECH AREA ---
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
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Button(
                onClick = { speechRecognizer.toggleLanguage() },
                modifier = Modifier.align(Alignment.TopEnd),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(text = languageButtonText, color = Color.White)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "即時語音轉錄",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Crossfade(
                    targetState = speechRecognizer.recognizedText.value to speechRecognizer.partialText.value,
                    label = "speech-text"
                ) { (recognized, partial) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (recognized.isNotEmpty()) {
                            Text(
                                text = recognized,
                                fontSize = 20.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (speechRecognizer.isRecording.value && partial.isNotEmpty()) {
                            if (recognized.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Text(
                                text = partial,
                                fontSize = 18.sp,
                                color = Color.Gray,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
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
                            if (speechRecognizer.isRecording.value) {
                                speechRecognizer.stopRecording()
                            } else {
                                speechRecognizer.startRecording()
                            }
                        } else {
                            recordAudioPermission.launchPermissionRequest()
                        }
                    },
                    modifier = Modifier.size(80.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (speechRecognizer.isRecording.value)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (speechRecognizer.isRecording.value)
                                android.R.drawable.ic_media_pause
                            else
                                android.R.drawable.ic_btn_speak_now
                        ),
                        contentDescription = if (speechRecognizer.isRecording.value) "停止" else "錄音",
                        modifier = Modifier.size(40.dp),
                        tint = if (speechRecognizer.isRecording.value)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // --- 2. MANUAL INPUT AREA ---
        AnimatedVisibility(
            visible = showManualInput,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualInputText,
                        onValueChange = { manualInputText = it },
                        label = { Text("手動輸入文字") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (manualInputText.isNotBlank()) {
                                    speechRecognizer.addManualTranscription(manualInputText)
                                    manualInputText = ""
                                    keyboardController?.hide()
                                }
                            }
                        )
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
                        Icon(
                            painter = painterResource(id = android.R.drawable.ic_menu_send),
                            contentDescription = "Send"
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 3. HISTORY HEADER ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "對話紀錄",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (speechRecognizer.transcriptionHistory.value.isNotEmpty()) {
                Button(
                    onClick = { speechRecognizer.clearHistory() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                        contentDescription = "清除紀錄",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                    Text(text = "清除", color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- 4. HISTORY LIST ---
        if (speechRecognizer.transcriptionHistory.value.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "尚無紀錄",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(
                    items = speechRecognizer.transcriptionHistory.value,
                    key = { it.id }
                ) { transcription ->
                    TranscriptionItem(
                        transcription = transcription,
                        onUpdate = { newText ->
                            speechRecognizer.updateTranscription(transcription.id, newText)
                            checkScamRisk(transcription.id, newText)
                        },
                        onDelete = {
                            speechRecognizer.deleteTranscription(transcription.id)
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        // --- 5. RISK METER (Static View - No Toggle) ---
        Spacer(modifier = Modifier.height(12.dp))
        RiskLevelMeter(
            score = maxRiskScore,
            highestRiskAdvice = maxRiskAdvice
        )
    }
}

// --- SETTINGS DIALOG (Unchanged) ---
@Composable
fun SettingsDialog(
    initialShowManualInput: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (url: String, showManualInput: Boolean) -> Unit
) {
    val presets = ServerConfig.PRESETS
    var selectedOption by remember { mutableStateOf(ServerConfig.currentBaseUrl) }
    var customUrl by remember { mutableStateOf("") }
    var tempShowManualInput by remember { mutableStateOf(initialShowManualInput) }

    val isCustomInitially = presets.none { it.first == ServerConfig.currentBaseUrl }
    var isCustomSelected by remember { mutableStateOf(isCustomInitially) }

    if (isCustomInitially && customUrl.isEmpty()) {
        customUrl = ServerConfig.currentBaseUrl
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("應用程式設定") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("偵測伺服器", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))

                presets.forEach { (url, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedOption = url; isCustomSelected = false }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = (selectedOption == url && !isCustomSelected),
                            onClick = { selectedOption = url; isCustomSelected = false }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                            Text(text = url, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isCustomSelected = true }
                        .padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = isCustomSelected,
                        onClick = { isCustomSelected = true }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "手動輸入", style = MaterialTheme.typography.bodyMedium)
                }

                if (isCustomSelected) {
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("請輸入網址") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text("顯示設定", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { tempShowManualInput = !tempShowManualInput }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("顯示手動輸入欄位")
                    Switch(checked = tempShowManualInput, onCheckedChange = { tempShowManualInput = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val finalUrl = if (isCustomSelected) customUrl else selectedOption
                if (finalUrl.isNotBlank()) onConfirm(finalUrl, tempShowManualInput)
            }) { Text("確定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// --- UPDATED RISK METER (Removed Toggle) ---
@Composable
fun RiskLevelMeter(
    score: Int,
    highestRiskAdvice: String?
) {
    val animatedProgress by animateFloatAsState(
        targetValue = score / 100f,
        label = "riskProgress"
    )

    // CHANGED: "Safe" is now anything < 50
    val (color, label, iconId) = when {
        score > 70 -> Triple(MaterialTheme.colorScheme.error, "高度危險 (DANGER)", android.R.drawable.ic_dialog_alert)
        score >= 50 -> Triple(Color(0xFFFFA000), "中度風險 (WARNING)", android.R.drawable.ic_dialog_info)
        else -> Triple(Color(0xFF4CAF50), "安全 (SAFE)", android.R.drawable.ic_lock_idle_lock)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // --- TOP ROW: Score & Label ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = iconId),
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "最高偵測風險指數",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "$score%",
                    style = MaterialTheme.typography.titleLarge,
                    color = color,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- PROGRESS BAR ---
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp)),
                color = color,
                trackColor = Color.Gray.copy(alpha = 0.2f),
            )

            Spacer(modifier = Modifier.height(4.dp))

            // --- BOTTOM LABEL ---
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = color
                )
            }

            // --- STATIC ADVICE VIEW ---
            // If advice exists, show it permanently. No toggles.
            if (!highestRiskAdvice.isNullOrEmpty()) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = color.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "主要警告 (Main Warning):",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                    Text(
                        text = highestRiskAdvice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun TranscriptionItem(
    transcription: Transcription,
    onUpdate: (String) -> Unit,
    onDelete: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editedText by remember(transcription.id) { mutableStateOf(transcription.text) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // --- RISK LABEL (Simplified) ---
            if (transcription.riskScore != null) {
                val score = transcription.riskScore
                // CHANGED: "Safe" is now anything < 50
                val (color, text) = when {
                    score > 70 -> MaterialTheme.colorScheme.error to "⚠️ 高風險 ($score%)"
                    score < 50 -> Color(0xFF4CAF50) to "✅ 安全 ($score%)"
                    else -> Color(0xFFFFFFA0) to "⚠️ 需留意 ($score%)"
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(color.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // --- TEXT CONTENT ---
            if (isEditing) {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = {
                            onUpdate(editedText)
                            isEditing = false
                        },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("儲存")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            editedText = transcription.text
                            isEditing = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("取消")
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        text = transcription.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                editedText = transcription.text
                                isEditing = true
                            }
                            .padding(end = 8.dp)
                    )
                    Column {
                        Icon(
                            painter = painterResource(id = android.R.drawable.ic_menu_edit),
                            contentDescription = "編輯",
                            modifier = Modifier.size(20.dp).clickable {
                                editedText = transcription.text
                                isEditing = true
                            },
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Icon(
                            painter = painterResource(id = android.R.drawable.ic_menu_delete),
                            contentDescription = "刪除",
                            modifier = Modifier.size(20.dp).clickable { onDelete() },
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}