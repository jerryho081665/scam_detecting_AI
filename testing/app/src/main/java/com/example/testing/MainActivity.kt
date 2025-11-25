package com.example.testing

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            TestingTheme {
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

    // Scope for running background network tasks
    val scope = rememberCoroutineScope()

    // State for Scam Risk (0 to 100)
    var riskPercentage by remember { mutableIntStateOf(0) }
    var riskStatusText by remember { mutableStateOf("Waiting for input...") }

    // Function to call Python
    // 1. Modified function to accept ID
    fun checkScamRisk(id: String, textToCheck: String) {
        scope.launch {
            try {
                // Set status to waiting (optional UI tweak) or just wait for result
                val request = ScamCheckRequest(message = textToCheck)
                val response = RetrofitClient.instance.checkMessage(request)

                val score = (response.scam_probability * 100).toInt()

                // 2. Update the specific item in the list
                speechRecognizer.updateRisk(id, score)

                // 3. Update the global meter (optional, keeps the bottom bar working)
                riskPercentage = score
                riskStatusText = if(response.is_risk) "HIGH RISK DETECTED" else "Safe"

            } catch (e: Exception) {
                Log.e("ScamCheck", "Error: ${e.message}")
            }
        }
    }

    // 4. Trigger whenever a NEW item is added
    LaunchedEffect(speechRecognizer.transcriptionHistory.value.size) {
        val history = speechRecognizer.transcriptionHistory.value
        if (history.isNotEmpty()) {
            // Get the newest item
            val latestItem = history.first()

            // Only check if it hasn't been checked yet (riskScore is null)
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
                    text = "Current Transcription",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = speechRecognizer.recognizedText.value,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                if (speechRecognizer.isRecording.value && speechRecognizer.partialText.value.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = speechRecognizer.partialText.value,
                        fontSize = 18.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (recordAudioPermission.status.isGranted) {
                            if (speechRecognizer.isRecording.value) {
                                speechRecognizer.isRecording.value = false
                                speechRecognizer.stopListening()
                            } else {
                                speechRecognizer.isRecording.value = true
                                speechRecognizer.startListening()
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
                        contentDescription = if (speechRecognizer.isRecording.value) "Stop" else "Mic",
                        modifier = Modifier.size(40.dp),
                        tint = if (speechRecognizer.isRecording.value)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "History",
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
                        contentDescription = "Clear history",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                    Text(text = "Clear", color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (speechRecognizer.transcriptionHistory.value.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No transcriptions yet",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // FIX 2: Add the 'key'. This prevents items from swapping data when the list updates.
                items(
                    items = speechRecognizer.transcriptionHistory.value,
                    key = { it.id }
                ) { transcription ->
                    TranscriptionItem(
                        transcription = transcription,
                        onUpdate = { newText ->
                            // 1. Update the local list
                            speechRecognizer.updateTranscription(transcription.id, newText)

                            // 2. FIX 3: Trigger the Python Check immediately!
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

        // Meter Bar at the bottom (Now displays RISK)
        Spacer(modifier = Modifier.height(16.dp))
        MeterBar(percentage = riskPercentage, statusText = riskStatusText)
    }
}

@Composable
fun MeterBar(percentage: Int, statusText: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Scam Risk Level",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "$percentage%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if(percentage > 50) Color.Red else MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = percentage / 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp)),
                color = when {
                    percentage < 30 -> Color.Green   // Safe
                    percentage < 70 -> Color.Yellow  // Caution
                    else -> Color.Red                // DANGER
                },
                trackColor = MaterialTheme.colorScheme.surfaceContainerLow
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Status Text
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = if (percentage >= 50) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
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

    // FIX 1: Use 'remember(transcription.id)'
    // This forces the text to reset if the row is recycled for a different message
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

            // Risk Badge
            if (transcription.riskScore != null) {
                val score = transcription.riskScore
                val isHighRisk = score >= 50
                val color = if (isHighRisk) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
                val text = if (isHighRisk) "⚠️ High Risk ($score%)" else "✅ Safe ($score%)"

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(color.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(text = text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

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
                        // Save Button
                        onClick = {
                            onUpdate(editedText) // Send the new text back
                            isEditing = false
                        },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) { Text("Save") }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        // Cancel Button
                        onClick = {
                            editedText = transcription.text // Reset to original
                            isEditing = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) { Text("Cancel") }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        text = transcription.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                // Optional: Initialize text when clicking to edit
                                editedText = transcription.text
                                isEditing = true
                            }
                            .padding(end = 8.dp)
                    )
                    Column {
                        Icon(
                            painter = painterResource(id = android.R.drawable.ic_menu_edit),
                            contentDescription = "Edit",
                            modifier = Modifier.size(20.dp).clickable {
                                editedText = transcription.text
                                isEditing = true
                            },
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Icon(
                            painter = painterResource(id = android.R.drawable.ic_menu_delete),
                            contentDescription = "Delete",
                            modifier = Modifier.size(20.dp).clickable { onDelete() },
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}