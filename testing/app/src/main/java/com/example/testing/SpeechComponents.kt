package com.example.testing

import android.speech.tts.Voice
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
// import androidx.compose.ui.draw.scale // Removed: No longer needed as Switch is gone
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    initialShowManualInput: Boolean,
    availableVoices: List<Voice>,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean, AdviceProvider, AsrProvider, String, Boolean, String) -> Unit
) {
    val presets = ServerConfig.PRESETS
    var selectedOption by remember { mutableStateOf(ServerConfig.currentBaseUrl) }
    var customUrl by remember { mutableStateOf("") }
    val isCustomInitially = presets.none { it.first == ServerConfig.currentBaseUrl }
    var isCustomSelected by remember { mutableStateOf(isCustomInitially) }

    if (isCustomInitially && customUrl.isEmpty()) {
        customUrl = ServerConfig.currentBaseUrl
    }

    // --- 2. Advice Provider State ---
    val adviceProviders = ServerConfigAdvice.PROVIDERS
    var selectedAdviceProvider by remember { mutableStateOf(ServerConfigAdvice.currentProvider) }
    val isManualAdviceName = ServerConfigAdvice.MANUAL_PROVIDER_NAME

    // Manual Input States
    var manualAdviceUrl by remember { mutableStateOf("") }
    var manualAdviceKey by remember { mutableStateOf("") }
    var manualAdviceUseAuth by remember { mutableStateOf(false) }

    // Default to template if empty
    var manualRawTemplate by remember { mutableStateOf(ServerConfigAdvice.DEFAULT_RAW_TEMPLATE) }

    // Initialize manual states
    LaunchedEffect(Unit) {
        if (selectedAdviceProvider.name == isManualAdviceName) {
            manualAdviceUrl = selectedAdviceProvider.baseUrl
            manualAdviceKey = selectedAdviceProvider.apiKey
            manualAdviceUseAuth = selectedAdviceProvider.useAuthHeader
            if (selectedAdviceProvider.rawJsonTemplate.isNotBlank()) {
                manualRawTemplate = selectedAdviceProvider.rawJsonTemplate
            }
        } else {
            // Pre-fill with defaults if not currently selected
            val defaultManual = adviceProviders.find { it.name == isManualAdviceName }
            if (defaultManual != null) {
                manualAdviceUrl = defaultManual.baseUrl
                manualAdviceKey = defaultManual.apiKey
                manualAdviceUseAuth = defaultManual.useAuthHeader
                if (defaultManual.rawJsonTemplate.isNotBlank()) {
                    manualRawTemplate = defaultManual.rawJsonTemplate
                }
            }
        }
    }

    // --- 3. ASR & TTS State ---
    val asrProviders = ServerConfigAsr.PROVIDERS
    var selectedAsrProvider by remember { mutableStateOf(ServerConfigAsr.currentProvider) }
    var yatingApiKey by remember { mutableStateOf(ServerConfigAsr.yatingApiKey) }

    var isTtsEnabled by remember { mutableStateOf(TtsConfig.isEnabled) }
    var selectedVoiceName by remember { mutableStateOf(TtsConfig.currentVoiceName) }
    var isVoiceDropdownExpanded by remember { mutableStateOf(false) }

    var tempShowManualInput by remember { mutableStateOf(initialShowManualInput) }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("應用程式設定") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                // --- SECTION 1: Detection Server ---
                Text("1. 詐騙偵測伺服器", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
                    modifier = Modifier.clickable { isCustomSelected = true }.padding(vertical = 4.dp)
                ) {
                    RadioButton(selected = isCustomSelected, onClick = { isCustomSelected = true })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "manual input", style = MaterialTheme.typography.bodyMedium)
                }
                if (isCustomSelected) {
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("http") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // --- SECTION 2: Advice AI Model ---
                Text("2. AI 建議模型", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))

                adviceProviders.forEach { provider ->
                    val isThisSelected = selectedAdviceProvider.name == provider.name
                    val isManualOption = provider.name == isManualAdviceName

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAdviceProvider = provider }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = isThisSelected,
                                onClick = { selectedAdviceProvider = provider }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = provider.name, style = MaterialTheme.typography.bodyMedium)
                                if (!isManualOption) {
                                    Text(text = provider.baseUrl, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                            }
                        }

                        if (isManualOption && isThisSelected) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 32.dp, bottom = 8.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = manualAdviceUrl,
                                    onValueChange = { manualAdviceUrl = it },
                                    label = { Text("API URL (Full Endpoint)") },
                                    placeholder = { Text("https://api.openai.com/v1/chat/completions") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = manualAdviceKey,
                                    onValueChange = { manualAdviceKey = it },
                                    label = { Text("API Key") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Checkbox(
                                        checked = manualAdviceUseAuth,
                                        onCheckedChange = { manualAdviceUseAuth = it }
                                    )
                                    Text("Bearer Auth Header", style = MaterialTheme.typography.bodySmall)
                                }

                                // --- CHANGED: Always show JSON input, removed Switch and Standard inputs ---
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "JSON Body Template (Use {{TEXT}} for transcription):",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                OutlinedTextField(
                                    value = manualRawTemplate,
                                    onValueChange = { manualRawTemplate = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 6,
                                    maxLines = 15,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    placeholder = { Text("{\n  \"model\": \"gpt-4o\",\n  \"messages\": [{\"role\": \"user\", \"content\": \"{{TEXT}}\"}]\n}") }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // --- SECTION 3: ASR Source ---
                Text("3. 語音辨識來源 (ASR)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))

                asrProviders.forEach { provider ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedAsrProvider = provider }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = (selectedAsrProvider.id == provider.id),
                            onClick = { selectedAsrProvider = provider }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = provider.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (selectedAsrProvider.id == "yating") {
                    OutlinedTextField(
                        value = yatingApiKey,
                        onValueChange = { yatingApiKey = it },
                        label = { Text("Yating API Key") },
                        placeholder = { Text("Enter your API Key") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, bottom = 8.dp),
                        singleLine = true
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // --- SECTION 4: TTS Settings ---
                Text("4. 語音警示設定 (TTS)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("啟用語音朗讀")
                    Switch(checked = isTtsEnabled, onCheckedChange = { isTtsEnabled = it })
                }

                AnimatedVisibility(visible = isTtsEnabled) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        ExposedDropdownMenuBox(
                            expanded = isVoiceDropdownExpanded,
                            onExpandedChange = { isVoiceDropdownExpanded = !isVoiceDropdownExpanded }
                        ) {
                            val currentVoiceObj = availableVoices.find { it.name == selectedVoiceName }
                            val displayText = currentVoiceObj?.let { "${it.locale.displayCountry} - ${it.name}" } ?: "系統預設 (Default)"

                            OutlinedTextField(
                                value = displayText,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("選擇語音 (Voice)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isVoiceDropdownExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )

                            ExposedDropdownMenu(
                                expanded = isVoiceDropdownExpanded,
                                onDismissRequest = { isVoiceDropdownExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("系統預設 (Default)") },
                                    onClick = {
                                        selectedVoiceName = ""
                                        isVoiceDropdownExpanded = false
                                    }
                                )
                                availableVoices.forEach { voice ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(voice.locale.displayCountry.ifBlank { "Unknown Region" }, fontWeight = FontWeight.Bold)
                                                Text(voice.name, style = MaterialTheme.typography.bodySmall)
                                            }
                                        },
                                        onClick = {
                                            selectedVoiceName = voice.name
                                            isVoiceDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // --- SECTION 5: UI Settings ---
                Text("5. 顯示設定", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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

                val finalAdviceProvider = if (selectedAdviceProvider.name == isManualAdviceName) {
                    // Create a completely custom provider based on inputs
                    selectedAdviceProvider.copy(
                        baseUrl = manualAdviceUrl,
                        apiKey = manualAdviceKey,
                        useAuthHeader = manualAdviceUseAuth,

                        // --- CHANGED: Force Raw Mode to TRUE and set empty defaults for unused fields ---
                        isRawJsonMode = true,
                        rawJsonTemplate = manualRawTemplate,

                        // Fill required fields with dummy values since Raw Mode doesn't use them
                        modelId = "",
                        systemPrompt = "",
                        supportsReasoning = false
                    )
                } else {
                    selectedAdviceProvider
                }

                if (finalUrl.isNotBlank()) {
                    onConfirm(
                        finalUrl,
                        tempShowManualInput,
                        finalAdviceProvider,
                        selectedAsrProvider,
                        yatingApiKey,
                        isTtsEnabled,
                        selectedVoiceName
                    )
                }
            }) { Text("確定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// RiskLevelMeter and TranscriptionItem can remain unchanged below...
@Composable
fun RiskLevelMeter(score: Int, highestRiskAdvice: String?, isLoading: Boolean) {
    // ... (Use the code from your previous file)
    val animatedProgress by animateFloatAsState(targetValue = score / 100f, label = "riskProgress")
    val (color, label, iconId) = when {
        score > 70 -> Triple(MaterialTheme.colorScheme.error, "高度危險", android.R.drawable.ic_dialog_alert)
        score >= 50 -> Triple(Color(0xFFFFFFA0), "中度風險", android.R.drawable.ic_dialog_info)
        else -> Triple(Color(0xFF4CAF50), "安全", android.R.drawable.ic_lock_idle_lock)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painter = painterResource(id = iconId), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "最高偵測風險指數", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Text(text = "$score%", style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)), color = color, trackColor = Color.Gray.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
            }
            if (isLoading || !highestRiskAdvice.isNullOrEmpty()) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = color.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "防騙建議:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
                    Spacer(modifier = Modifier.height(4.dp))
                    if (isLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = color)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "AI 正在分析詐騙特徵...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                    } else {
                        Text(text = highestRiskAdvice ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptionItem(
    transcription: Transcription,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onUpdate: (String) -> Unit,
    onDelete: () -> Unit
) {
    // ... (Paste your TranscriptionItem code here, it is unchanged)
    var isEditing by remember { mutableStateOf(false) }
    var editedText by remember(transcription.id) { mutableStateOf(transcription.text) }

    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val borderWidth = if (isSelected) 2.dp else 0.dp

    val cardBackgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        val score = transcription.riskScore ?: 0
        when {
            score > 70 -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
            score >= 50 -> Color(0xFFFFF9C4)
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        }
    }

    val timestampStr = remember(transcription.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(transcription.timestamp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackgroundColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelection() },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Text(
                    text = timestampStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))

                if (transcription.riskScore != null) {
                    val score = transcription.riskScore
                    val (color, text) = when {
                        score > 70 -> MaterialTheme.colorScheme.error to "⚠️ 高風險 ($score%)"
                        score < 50 -> Color(0xFF4CAF50) to "✅ 安全 ($score%)"
                        else -> Color(0xFFA69B0E) to "⚠️ 需留意 ($score%)"
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
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (isEditing) {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { onUpdate(editedText); isEditing = false },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) { Text("儲存") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { editedText = transcription.text; isEditing = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) { Text("取消") }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = transcription.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                            .combinedClickable(
                                onClick = {
                                    if (isSelectionMode) {
                                        onToggleSelection()
                                    } else {
                                        editedText = transcription.text
                                        isEditing = true
                                    }
                                },
                                onLongClick = {
                                    onToggleSelection()
                                }
                            )
                    )

                    if (!isSelectionMode) {
                        Column {
                            Icon(
                                painter = painterResource(id = android.R.drawable.ic_menu_edit),
                                contentDescription = "編輯",
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable {
                                        editedText = transcription.text
                                        isEditing = true
                                    },
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Icon(
                                painter = painterResource(id = android.R.drawable.ic_menu_delete),
                                contentDescription = "刪除",
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { onDelete() },
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}