package app.xodos2.ui.drawer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.SettingsVoice
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.xodos2.voip.SpeechInputManager
import app.xodos2.voip.VoipMicBridge

enum class VoipMode {
    VOIP_MIC_STREAM,  // Streams PCM over TCP 4714 for proot Linux apps (Discord, WebRTC, etc.)
    VOICE_TO_TERMINAL // Speech-to-Text directly typing commands into proot terminal
}

/**
 * VoIP & Microphone input bar located at the bottom of the drawer.
 * Extracted and enhanced from The412Banner/NewTermux.
 */
@Composable
fun VoipDrawerBottomBar(
    onSendToTerminal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    var selectedMode by remember { mutableStateOf(VoipMode.VOIP_MIC_STREAM) }
    var isExpanded by remember { mutableStateOf(false) }

    // VoIP Bridge states
    val isStreaming by VoipMicBridge.isStreaming.collectAsState()
    val connectedClients by VoipMicBridge.connectedClientsCount.collectAsState()
    val bridgeStatus by VoipMicBridge.statusMessage.collectAsState()

    // Speech-To-Text manager & states
    val speechManager = remember {
        SpeechInputManager(context)
    }
    val isListening by speechManager.isListening.collectAsState()
    val lastSpeechResult by speechManager.lastResult.collectAsState()

    DisposableEffect(speechManager) {
        speechManager.setCallback(object : SpeechInputManager.SpeechCallback {
            override fun onResult(text: String) {
                if (text.isNotBlank()) {
                    onSendToTerminal("$text\n")
                    Toast.makeText(context, "Voice input sent: $text", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(error: String) {
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            }

            override fun onListeningStarted() {}
            override fun onListeningStopped() {}
        })

        onDispose {
            speechManager.destroy()
        }
    }

    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordPermission = granted
        if (granted) {
            when (selectedMode) {
                VoipMode.VOIP_MIC_STREAM -> VoipMicBridge.startStreaming(context)
                VoipMode.VOICE_TO_TERMINAL -> speechManager.startListening()
            }
        } else {
            Toast.makeText(context, "Microphone permission required for VoIP & Voice features", Toast.LENGTH_LONG).show()
        }
    }

    val isActive = (selectedMode == VoipMode.VOIP_MIC_STREAM && isStreaming) ||
            (selectedMode == VoipMode.VOICE_TO_TERMINAL && isListening)

    // Animated pulse scale for active microphone
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val activeGlowColor by animateColorAsState(
        targetValue = when {
            isActive && selectedMode == VoipMode.VOIP_MIC_STREAM -> Color(0xFF00E676) // Bright emerald
            isActive && selectedMode == VoipMode.VOICE_TO_TERMINAL -> Color(0xFFFF5252) // Vibrant red (NewTermux listening)
            else -> Color(0xFFBB86FC) // NewTermux idle violet
        },
        label = "glow"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xE6141026), // Deep translucent obsidian-violet
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            activeGlowColor.copy(alpha = if (isActive) 0.6f else 0.25f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(12.dp)
        ) {
            // Header Row: VoIP Badge, Mode Toggle, Expand Arrow
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Mic Action Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    activeGlowColor.copy(alpha = 0.35f),
                                    Color.Transparent
                                )
                            )
                        )
                        .border(1.5.dp, activeGlowColor, CircleShape)
                        .clickable {
                            if (!hasRecordPermission) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                when (selectedMode) {
                                    VoipMode.VOIP_MIC_STREAM -> {
                                        val nowStreaming = VoipMicBridge.toggleStreaming(context)
                                        if (nowStreaming) {
                                            onSendToTerminal(VoipMicBridge.getProotSetupCommand())
                                        }
                                    }
                                    VoipMode.VOICE_TO_TERMINAL -> {
                                        if (isListening) speechManager.stopListening() else speechManager.startListening()
                                    }
                                }
                            }
                        }
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                        contentDescription = "VoIP Microphone",
                        tint = activeGlowColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title and Status
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { isExpanded = !isExpanded }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Proot VoIP Mic",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isActive) activeGlowColor.copy(alpha = 0.25f)
                                    else Color.White.copy(alpha = 0.1f)
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = if (isActive) "ACTIVE" else "MUTED",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (isActive) activeGlowColor else Color.White.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }

                    val statusSubtext = when {
                        selectedMode == VoipMode.VOIP_MIC_STREAM && isStreaming ->
                            "TCP 127.0.0.1:4714 • ${if (connectedClients > 0) "$connectedClients client(s) connected" else "Ready for connection"}"
                        selectedMode == VoipMode.VOICE_TO_TERMINAL && isListening ->
                            "Dictating voice command..."
                        selectedMode == VoipMode.VOICE_TO_TERMINAL ->
                            "Tap mic to dictate to proot"
                        else ->
                            "Tap to enable VoIP microphone"
                    }

                    Text(
                        text = statusSubtext,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        ),
                        maxLines = 1
                    )
                }

                // Expand/Collapse Toggle Button
                TextButton(
                    onClick = { isExpanded = !isExpanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isExpanded) "Less" else "Options",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Expanded Controls & Proot Integration Helpers
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f), thickness = 0.5.dp)
                    Spacer(Modifier.height(8.dp))

                    // Mode Selector Chips
                    Text(
                        text = "Mode",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedMode == VoipMode.VOIP_MIC_STREAM,
                            onClick = {
                                if (isListening) speechManager.stopListening()
                                selectedMode = VoipMode.VOIP_MIC_STREAM
                            },
                            label = { Text("Proot VoIP Stream", fontSize = 11.sp) },
                            leadingIcon = {
                                Icon(Icons.Rounded.SettingsVoice, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00E676).copy(alpha = 0.2f),
                                selectedLabelColor = Color(0xFF00E676),
                                selectedLeadingIconColor = Color(0xFF00E676)
                            )
                        )

                        FilterChip(
                            selected = selectedMode == VoipMode.VOICE_TO_TERMINAL,
                            onClick = {
                                if (isStreaming) VoipMicBridge.stopStreaming()
                                selectedMode = VoipMode.VOICE_TO_TERMINAL
                            },
                            label = { Text("Voice Dictation", fontSize = 11.sp) },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFBB86FC).copy(alpha = 0.2f),
                                selectedLabelColor = Color(0xFFBB86FC),
                                selectedLeadingIconColor = Color(0xFFBB86FC)
                            )
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    if (selectedMode == VoipMode.VOIP_MIC_STREAM) {
                        Text(
                            text = "Stream microphone audio directly into Discord, Mumble, WebRTC or PulseAudio inside proot.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 11.sp
                            )
                        )
                        Spacer(Modifier.height(8.dp))

                        // One-click action to attach VoIP to PulseAudio inside proot
                        Button(
                            onClick = {
                                val cmd = VoipMicBridge.getProotSetupCommand()
                                onSendToTerminal("$cmd\n")
                                Toast.makeText(context, "Configuring PulseAudio VoIP source in proot...", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1E88E5).copy(alpha = 0.35f),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Attach Mic to Proot PulseAudio", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        // Voice Dictation Mode
                        Text(
                            text = "Dictated speech from NewTermux STT will be written directly to the active proot terminal.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 11.sp
                            )
                        )
                        if (lastSpeechResult.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.White.copy(alpha = 0.08f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = lastSpeechResult,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            color = Color.White,
                                            fontSize = 11.sp
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            onSendToTerminal("$lastSpeechResult\n")
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.Send,
                                            contentDescription = "Send",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
