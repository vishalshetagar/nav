package com.example.nav

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.nav.overlay.AssistantRuntime
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import com.example.nav.settings.ApiKeyStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.nav.accessibility.GuideAccessibilityService
import com.example.nav.overlay.FloatingBubbleService
import com.example.nav.voice.TTSManager
import com.example.nav.voice.VoiceInfo

class MainActivity : ComponentActivity() {

    private var ttsManager: TTSManager? = null
    private var permissionRefresh by mutableIntStateOf(0)
    private var startAfterMicGrant = false
    private val microphonePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRefresh++
        if (startAfterMicGrant) {
            startAfterMicGrant = false
            if (granted) startAssistantService()
            else AssistantRuntime.failed("NaV is voice-only. Allow microphone access to speak to the assistant.")
        }
    }
    private var startAfterPermission = false
    private var awaitingReady = false
    private var startupJob: Job? = null
    private val assistantReady = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (awaitingReady) { awaitingReady = false; moveTaskToBack(true) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("start_after_mic", startAfterMicGrant)
        outState.putBoolean("start_after_permission", startAfterPermission)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        unregisterReceiver(assistantReady)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        permissionRefresh++
        if (startAfterPermission && isAccessibilityServiceEnabled()) {
            startAfterPermission = false
            startAssistantService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startAfterMicGrant = savedInstanceState?.getBoolean("start_after_mic") ?: false
        startAfterPermission = savedInstanceState?.getBoolean("start_after_permission") ?: false
        ContextCompat.registerReceiver(this, assistantReady, IntentFilter(FloatingBubbleService.ACTION_READY), ContextCompat.RECEIVER_NOT_EXPORTED)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var apiKey by remember { mutableStateOf(getSavedApiKey()) }
                    var selectedVoiceId by remember { mutableStateOf(getSavedVoiceId()) }
                    var selectedLanguage by remember { mutableStateOf(getSavedLanguage()) }
                    val context = LocalContext.current
                    val refresh = permissionRefresh
                    val connected by GuideAccessibilityService.connectionState.collectAsState()
                    val runtime by AssistantRuntime.state.collectAsState()
                    val hasAccessibilityPermission = remember(refresh, connected) { isAccessibilityServiceEnabled() }
                    
                    var availableVoices by remember { mutableStateOf(emptyList<VoiceInfo>()) }
                    
                    // Initialize TTS to get voices
                    DisposableEffect(Unit) {
                        ttsManager = TTSManager(context) { success ->
                            if (success) {
                                availableVoices = ttsManager?.getAvailableVoices() ?: emptyList()
                            }
                        }
                        onDispose {
                            ttsManager?.destroy()
                        }
                    }

                    val hasMicPermission = remember(refresh) {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    }

                    OnboardingScreen(
                        apiKey = apiKey,
                        onApiKeyChange = { 
                            if (saveApiKey(it)) apiKey = it
                        },
                        availableVoices = availableVoices,
                        selectedVoiceId = selectedVoiceId,
                        onVoiceChange = {
                            selectedVoiceId = it
                            saveVoiceId(it)
                            ttsManager?.speak("This is a sample of the selected voice", it)
                        },
                        selectedLanguage = selectedLanguage,
                        onLanguageChange = {
                            selectedLanguage = it
                            saveLanguage(it)
                        },
                        hasMicPermission = hasMicPermission,
                        hasAccessibilityPermission = hasAccessibilityPermission,
                        onGrantMic = { microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onGrantAccessibility = { requestAccessibilityPermission() },
                        connected = connected,
                        runtime = runtime,
                        onStartAssistant = { draft ->
                            if (draft == apiKey || saveApiKey(draft)) {
                                apiKey = draft
                                startAssistantService()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun getSavedApiKey(): String = ApiKeyStore(this).read()

    private fun saveApiKey(key: String): Boolean = runCatching { ApiKeyStore(this).write(key) }
        .onFailure { Toast.makeText(this, "Could not securely save the key. Please try again.", Toast.LENGTH_LONG).show() }
        .isSuccess

    private fun getSavedVoiceId(): String {
        val prefs = getSharedPreferences("nav_prefs", MODE_PRIVATE)
        return prefs.getString("preferred_voice_id", "") ?: ""
    }

    private fun saveVoiceId(voiceId: String) {
        val prefs = getSharedPreferences("nav_prefs", MODE_PRIVATE)
        prefs.edit().putString("preferred_voice_id", voiceId).apply()
    }

    private fun getSavedLanguage(): String {
        val prefs = getSharedPreferences("nav_prefs", MODE_PRIVATE)
        return prefs.getString("preferred_language", "en-US") ?: "en-US"
    }

    private fun saveLanguage(lang: String) {
        val prefs = getSharedPreferences("nav_prefs", MODE_PRIVATE)
        prefs.edit().putString("preferred_language", lang).apply()
    }

    private fun requestAccessibilityPermission() {
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(this, GuideAccessibilityService::class.java))
        runCatching { startActivity(details) }.onFailure {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun startAssistantService() {
        if (startupJob?.isActive == true) return
        if (!isAccessibilityServiceEnabled()) {
            startAfterPermission = true
            AssistantRuntime.failed("Enable Use NaV on the next screen, then return here. The assistant will open automatically.")
            requestAccessibilityPermission()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startAfterMicGrant = true
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        awaitingReady = true
        AssistantRuntime.starting()
        startupJob = lifecycleScope.launch {
            try {
                startForegroundService(Intent(this@MainActivity, FloatingBubbleService::class.java))
                val result = withTimeoutOrNull(12_000) {
                    AssistantRuntime.state.first { it.phase != AssistantRuntime.Phase.STARTING }
                }
                if (result == null) {
                    awaitingReady = false
                    AssistantRuntime.failed("The floating panel did not open. Re-enable NaV accessibility, then tap Start assistant again.")
                    stopService(Intent(this@MainActivity, FloatingBubbleService::class.java))
                } else if (result.phase != AssistantRuntime.Phase.RUNNING) awaitingReady = false
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                awaitingReady = false
                AssistantRuntime.failed("Android could not start NaV. Keep this screen open and try again. (${error.javaClass.simpleName})")
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedService = ComponentName(this, GuideAccessibilityService::class.java)
        return Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').any { enabledService ->
            ComponentName.unflattenFromString(enabledService) == expectedService
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    availableVoices: List<VoiceInfo>,
    selectedVoiceId: String,
    onVoiceChange: (String) -> Unit,
    selectedLanguage: String,
    onLanguageChange: (String) -> Unit,
    hasMicPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    onGrantMic: () -> Unit,
    onGrantAccessibility: () -> Unit,
    onStartAssistant: (String) -> Unit,
    connected: Boolean,
    runtime: AssistantRuntime.State
) {
    val contextForStop = LocalContext.current
    var keyDraft by remember(apiKey) { mutableStateOf(apiKey) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text("NaV · Your voice guide", style = MaterialTheme.typography.headlineMedium)
        Text("Tap the island and say what you want to do. NaV guides you out loud. Say “do this step” to let it tap for you.",
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 12.dp))
        Card(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(when {
                    connected -> "Accessibility connected"
                    hasAccessibilityPermission -> "Accessibility enabled - waiting for Android to connect"
                    else -> "Accessibility is not connected"
                }, style = MaterialTheme.typography.titleMedium)
                Text(runtime.message, modifier = Modifier.padding(vertical = 8.dp))
                Button(onClick = { onStartAssistant(keyDraft) }, modifier = Modifier.fillMaxWidth(),
                    enabled = runtime.phase != AssistantRuntime.Phase.STARTING) {
                    Text(when {
                        runtime.phase == AssistantRuntime.Phase.STARTING -> "Opening assistant..."
                        runtime.phase == AssistantRuntime.Phase.RUNNING -> "Show voice island"
                        !hasAccessibilityPermission -> "Enable accessibility and start"
                        else -> "Start assistant"
                    })
                }
                TextButton(onClick = onGrantAccessibility) { Text("Open NaV accessibility settings") }
                if (runtime.phase == AssistantRuntime.Phase.RUNNING) {
                    TextButton(onClick = { contextForStop.stopService(Intent(contextForStop, FloatingBubbleService::class.java)) }) { Text("Stop assistant") }
                }
                if (keyDraft.isBlank()) Text("You can open the assistant now. Add your Groq key below to use screen guidance.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("During a task, visible app text and controls are sent to Groq. Password and Android-marked sensitive fields are excluded. Other screen content may contain personal information. No screenshots are captured. Stop ends the task immediately.",
            style = MaterialTheme.typography.bodySmall)
        Text("Your API key is encrypted on this device. Use your own Groq key; NaV has no other AI provider.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text("Groq API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        
        Spacer(modifier = Modifier.height(12.dp))

        Row {
            Button(onClick = { onApiKeyChange(keyDraft) }, enabled = keyDraft != apiKey) { Text("Save key") }
            TextButton(onClick = { keyDraft = ""; onApiKeyChange("") }, enabled = apiKey.isNotBlank()) { Text("Remove key") }
        }
        Text("Groq free-tier mode: compact context and paced requests. Get a key at console.groq.com.", style = MaterialTheme.typography.bodySmall)

        // Language Selection
        val languages = listOf(
            "en-US" to "English",
            "hi-IN" to "Hindi (हिंदी)",
            "kn-IN" to "Kannada (ಕನ್ನಡ)"
        )
        var langExpanded by remember { mutableStateOf(false) }
        val currentLangName = languages.find { it.first == selectedLanguage }?.second ?: "Select Language"

        ExposedDropdownMenuBox(
            expanded = langExpanded,
            onExpandedChange = { langExpanded = !langExpanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = currentLangName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Language") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = langExpanded,
                onDismissRequest = { langExpanded = false }
            ) {
                languages.forEach { (code, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onLanguageChange(code)
                            langExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Voice Selection Dropdown
        var expanded by remember { mutableStateOf(false) }
        val currentVoiceName = availableVoices.find { it.id == selectedVoiceId }?.displayName ?: "Select Voice"

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = currentVoiceName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Assistant Voice") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                availableVoices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice.displayName) },
                        onClick = {
                            onVoiceChange(voice.id)
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Text("NaV needs these permissions:", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = onGrantAccessibility, 
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hasAccessibilityPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
        ) {
            Text(if (hasAccessibilityPermission) "1. Accessibility Service Enabled" else "1. Enable Accessibility Service")
        }
        Text("Allows NaV to read visible controls and perform a step when you say “do this step”.", style = MaterialTheme.typography.bodySmall)

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onGrantMic, 
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hasMicPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
        ) {
            Text(if (hasMicPermission) "2. Microphone Granted" else "2. Grant Microphone Permission")
        }
        Text("Allows you to talk to the assistant.", style = MaterialTheme.typography.bodySmall)

        Spacer(modifier = Modifier.height(12.dp))

        Spacer(modifier = Modifier.height(32.dp))
        
        if (!hasMicPermission) {
            Text(
                "NaV is voice-only. Microphone access is required; Start assistant will ask for it.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
