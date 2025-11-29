package com.example.testing

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.random.Random

@Composable
fun AudioVisualizer(
    modifier: Modifier = Modifier,
    isRecording: Boolean,
    soundLevel: Float // Expected 0.0f to 1.0f
) {
    // Number of bars in the visualizer
    val barCount = 30

    // We keep a history of amplitudes to create a "wave" effect
    // We shift values: new value enters at right, old values move left
    val amplitudes = remember { mutableStateListOf<Float>().apply {
        repeat(barCount) { add(0.1f) }
    }}

    // Smooth out the raw input level
    val animatedLevel by animateFloatAsState(
        targetValue = if (isRecording) soundLevel.coerceAtLeast(0.1f) else 0.05f,
        animationSpec = tween(100),
        label = "volume"
    )

    // Infinite transition for idle animation (breathing effect)
    val infiniteTransition = rememberInfiniteTransition(label = "idle")
    val idlePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase"
    )

    // Update the amplitudes list continuously
    LaunchedEffect(animatedLevel) {
        if (isRecording) {
            // Shift list to left
            amplitudes.removeAt(0)
            // Add new value with some randomness to look organic
            val randomFactor = Random.nextFloat() * 0.4f + 0.8f // 0.8 to 1.2
            amplitudes.add((animatedLevel * randomFactor).coerceIn(0.1f, 1.0f))
        } else {
            // Flat line logic when stopped
            amplitudes.removeAt(0)
            amplitudes.add(0.05f)
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.primaryContainer

    Canvas(modifier = modifier.fillMaxWidth().height(60.dp)) {
        val barWidth = size.width / (barCount * 1.5f)
        val gap = barWidth / 2f
        val startX = (size.width - (barCount * (barWidth + gap))) / 2f
        val centerY = size.height / 2f
        val maxBarHeight = size.height * 0.8f

        amplitudes.forEachIndexed { index, amplitude ->
            val x = startX + index * (barWidth + gap)

            // Calculate height
            val height = if (isRecording) {
                // Active Wave
                maxBarHeight * amplitude
            } else {
                // Idle Wave (Sine wave effect)
                val offset = index.toFloat() / barCount
                val wave = kotlin.math.sin((idlePhase + offset) * 2 * Math.PI).toFloat()
                maxBarHeight * 0.1f * (1 + wave * 0.5f)
            }

            // Draw Bar (Mirrored from center)
            drawLine(
                color = if (isRecording) primaryColor else secondaryColor,
                start = Offset(x, centerY - height / 2),
                end = Offset(x, centerY + height / 2),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}