package com.example.nav.overlay

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val Mint = Color(0xFFA5F3D0)
private val Muted = Color(0xFFABADB7)

/** Voice-only island. Transcripts are display-only; this window never needs keyboard focus. */
@Composable
fun AssistantPanel(
    expanded: Boolean, maxWidth: Dp, isThinking: Boolean, isListening: Boolean,
    isExecuting: Boolean, voiceLevel: Float, voiceTranscript: String, status: String,
    hasTask: Boolean, action: String, step: Int,
    onStartVoice: () -> Unit, onStopVoice: () -> Unit, onExpand: () -> Unit,
    onCollapse: () -> Unit, onStop: () -> Unit, onExit: () -> Unit
) {
    val label = when {
        isListening -> "Listening"
        isExecuting -> "Taking this step"
        isThinking -> "Thinking"
        hasTask -> "Step $step"
        else -> "Tap to speak"
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, surface = Color(0xFF101114), onSurface = Color.White)) {
        Surface(
            color = Color(0xFF101114), shape = RoundedCornerShape(30.dp),
            modifier = Modifier.width(if (expanded) maxWidth else minOf(224.dp, maxWidth)).animateContentSize()
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable(onClick = { if (expanded || hasTask || isThinking) onExpand() else onStartVoice() })
                        .padding(start = 18.dp, end = if (expanded) 4.dp else 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VoiceWave(voiceLevel, isListening, Modifier.size(28.dp, 24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("NaV", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(12.dp))
                    Text(label, color = if (isListening) Mint else Muted, style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (isThinking || isExecuting) CircularProgressIndicator(Modifier.padding(start = 6.dp).size(14.dp), color = Mint, strokeWidth = 2.dp)
                    if (expanded) IconButton(onClick = onCollapse, modifier = Modifier.semantics { contentDescription = "Minimize NaV" }) {
                        Text("−", color = Muted, style = MaterialTheme.typography.titleLarge)
                    }
                }
                if (expanded) {
                    Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                        Text(
                            if (isListening) voiceTranscript.ifBlank { "What can I help you do?" }
                            else status.ifBlank { "Your voice. A little guidance." },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                        )
                        if (!isListening && voiceTranscript.isNotBlank()) Text("You: $voiceTranscript", color = Muted,
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 10.dp))
                        Text(when {
                            isListening -> "Speak naturally. Your request sends when you finish."
                            action.isNotBlank() -> "$action\nSay “do this step” to confirm, or “next” after doing it yourself."
                            hasTask -> "Tap the mic and say “next”, “repeat”, or “stop”."
                            else -> "Try “Help me turn on dark mode”."
                        }, color = Muted, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = if (isListening) onStopVoice else onStartVoice,
                                enabled = !isExecuting, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color(0xFF10251C))
                            ) {
                                Text(if (isListening) "Cancel mic" else "Speak", fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = if (hasTask || isThinking || isListening) onStop else onExit) {
                                Text(if (hasTask || isThinking || isListening) "Stop" else "Close", color = Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceWave(level: Float, listening: Boolean, modifier: Modifier) {
    val amplitude by animateFloatAsState(if (listening) level.coerceIn(0f, 1f) else 0.15f, label = "voice level")
    Canvas(modifier) {
        val shape = floatArrayOf(0.35f, 0.7f, 1f, 0.65f, 0.4f)
        shape.forEachIndexed { index, scale ->
            val height = size.height * (0.18f + amplitude * 0.82f) * scale
            val x = size.width * (index + 0.5f) / shape.size
            drawLine(Mint, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2),
                strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
