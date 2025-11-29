package com.example.testing

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// --- SETTINGS DIALOG ---
@Composable
fun SettingsDialog(
    initialShowManualInput: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (url: String, showManualInput: Boolean, adviceProvider: AdviceProvider) -> Unit
) {
    // 1. Detection Server State
    val presets = ServerConfig.PRESETS
    var selectedOption by remember { mutableStateOf(ServerConfig.currentBaseUrl) }
    var customUrl by remember { mutableStateOf("") }
    val isCustomInitially = presets.none { it.first == ServerConfig.currentBaseUrl }
    var isCustomSelected by remember { mutableStateOf(isCustomInitially) }

    if (isCustomInitially && customUrl.isEmpty()) {
        customUrl = ServerConfig.currentBaseUrl
    }

    // 2. Advice Provider State
    val adviceProviders = ServerConfigAdvice.PROVIDERS
    var selectedAdviceProvider by remember { mutableStateOf(ServerConfigAdvice.currentProvider) }

    // Logic for Manual Advice Input
    val isManualAdviceName = ServerConfigAdvice.MANUAL_PROVIDER_NAME
    var customAdviceUrl by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (selectedAdviceProvider.name == isManualAdviceName) {
            customAdviceUrl = selectedAdviceProvider.baseUrl
        }
    }

    // 3. UI State
    var tempShowManualInput by remember { mutableStateOf(initialShowManualInput) }

    // 4. Scroll State
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("應用程式設定") },
        text = {
            // UPDATED: Added verticalScroll to the Column
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
                            if (provider.name != isManualAdviceName) {
                                Text(text = provider.baseUrl, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    }

                    if (provider.name == isManualAdviceName && isThisSelected) {
                        OutlinedTextField(
                            value = customAdviceUrl,
                            onValueChange = { customAdviceUrl = it },
                            label = { Text("API URL (v1/)") },
                            placeholder = { Text("https://your-url.com/v1/") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp, bottom = 8.dp),
                            singleLine = true
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // --- SECTION 3: UI Settings ---
                Text("3. 顯示設定", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
                    selectedAdviceProvider.copy(baseUrl = customAdviceUrl)
                } else {
                    selectedAdviceProvider
                }

                if (finalUrl.isNotBlank()) {
                    onConfirm(finalUrl, tempShowManualInput, finalAdviceProvider)
                }
            }) { Text("確定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// RiskLevelMeter and TranscriptionItem remain unchanged
@Composable
fun RiskLevelMeter(score: Int, highestRiskAdvice: String?, isLoading: Boolean) {
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
    var isEditing by remember { mutableStateOf(false) }
    var editedText by remember(transcription.id) { mutableStateOf(transcription.text) }

    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val borderWidth = if (isSelected) 2.dp else 0.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceContainerLow
        )
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

                if (transcription.riskScore != null) {
                    val score = transcription.riskScore
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
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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