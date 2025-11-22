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
    fun checkScamRisk(textToCheck: String) {
        scope.launch {
            try {
                riskStatusText = "Analyzing..."
                val request = ScamCheckRequest(message = textToCheck)
                val response = RetrofitClient.instance.checkMessage(request)

                // Convert 0.95 to 95
                riskPercentage = (response.scam_probability * 100).toInt()

                riskStatusText = if(response.is_risk) "HIGH RISK DETECTED" else "Safe"

                Log.d("ScamCheck", "Risk: $riskPercentage%")
            } catch (e: Exception) {
                Log.e("ScamCheck", "Error: ${e.message}")
                riskStatusText = "Connection Error"
                // If error, maybe set risk to 0 or keep previous
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { speechRecognizer.destroy() }
    }

    // TRIGGER: Whenever a new item is added to history (a sentence is finished)
    // We check the most recent one (index 0)
    LaunchedEffect(speechRecognizer.transcriptionHistory.value) {
        val history = speechRecognizer.transcriptionHistory.value
        if (history.isNotEmpty()) {
            // Get the newest sentence
            val latestSentence = history.first().text
            checkScamRisk(latestSentence)
        }
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
                items(speechRecognizer.transcriptionHistory.value) { transcription ->
                    TranscriptionItem(
                        transcription = transcription,
                        onUpdate = { newText ->
                            speechRecognizer.updateTranscription(transcription.id, newText)
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
    var editedText by remember { mutableStateOf(transcription.text) }

    Card(
        modifier = Modifier
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            if (isEditing) {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    singleLine = false,
                    maxLines = 3
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            onUpdate(editedText)
                            isEditing = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Text("Save", color = Color.White)
                    }

                    Spacer(modifier = Modifier.size(8.dp))

                    Button(
                        onClick = {
                            editedText = transcription.text
                            isEditing = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = transcription.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { isEditing = true }
                            .padding(end = 8.dp)
                    )

                    Column {
                        Icon(
                            painter = painterResource(id = android.R.drawable.ic_menu_edit),
                            contentDescription = "Edit",
                            modifier = Modifier
                                .size(24.dp)
                                .clickable { isEditing = true }
                                .padding(bottom = 8.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )

                        Icon(
                            painter = painterResource(id = android.R.drawable.ic_menu_delete),
                            contentDescription = "Delete",
                            modifier = Modifier
                                .size(24.dp)
                                .clickable { onDelete() },
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}