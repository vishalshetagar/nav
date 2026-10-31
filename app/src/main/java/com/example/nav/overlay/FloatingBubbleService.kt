package com.example.nav.overlay

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import kotlinx.coroutines.flow.first
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.*
import com.example.nav.MainActivity
import com.example.nav.R
import com.example.nav.accessibility.GuideAccessibilityService
import com.example.nav.llm.*
import com.example.nav.model.*
import com.example.nav.settings.ApiKeyStore
import com.example.nav.voice.TTSManager
import com.example.nav.voice.VoiceCommand
import com.example.nav.voice.VoiceInputManager
import kotlinx.coroutines.*
import kotlin.math.roundToInt

class FloatingBubbleService : LifecycleService(), SavedStateRegistryOwner {
    private val registry = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = registry.savedStateRegistry
    private lateinit var windowManager: WindowManager
    private lateinit var voice: VoiceInputManager
    private lateinit var speech: TTSManager
    private lateinit var highlighter: HighlightOverlay
    private var view: ComposeView? = null
    private val llm = LLMService()
    private val builder = PromptBuilder()
    private var expanded by mutableStateOf(false)
    private var thinking by mutableStateOf(false)
    private var listening by mutableStateOf(false)
    private var voiceLevel by mutableFloatStateOf(0f)
    private var transcript by mutableStateOf("")
    private var status by mutableStateOf("")
    private var session by mutableStateOf<GuidanceSession?>(null)
    private var pending by mutableStateOf<AssistantAction?>(null)
    private var observation: Observation? = null
    private var clarificationGoal: String? = null
    private var requestJob: Job? = null
    private var changeJob: Job? = null
    private var receiverRegistered = false
    private var startupJob: Job? = null
    private var foregroundStarted = false
    private var microphoneService = false
    private var executing by mutableStateOf(false)
    private var stepCount by mutableIntStateOf(0)

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 40 }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { checkScreenChange() }
    }

    override fun onCreate() {
        super.onCreate()
        registry.performRestore(null)
        voice = VoiceInputManager(this)
        speech = TTSManager(this) {}
        AssistantRuntime.starting()
        try {
            startAsForeground()
            foregroundStarted = true
        } catch (error: Exception) {
            Log.e("NaVStartup", "Foreground service could not start", error)
            AssistantRuntime.failed("Android could not start the assistant service. Return to NaV and tap Start assistant again. (${error.javaClass.simpleName})")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (!foregroundStarted) return START_NOT_STICKY
        if (!microphoneService && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAsForeground() // Upgrade microphone access after returning from permission setup.
        }
        if (view != null) {
            // Starting again always brings the existing panel back into view.
            params.x = 0
            params.y = (8 * resources.displayMetrics.density).roundToInt()
            showPanel(false)
            AssistantRuntime.running()
            sendBroadcast(Intent(ACTION_READY).setPackage(packageName))
        } else if (startupJob?.isActive != true) {
            startupJob = lifecycleScope.launch {
                try {
                    val connected = withTimeoutOrNull(8_000) {
                        GuideAccessibilityService.connectionState.first { it }
                    } == true
                    if (!connected) {
                        AssistantRuntime.failed("NaV is enabled but Android has not connected it. Open Accessibility settings, turn NaV off and on, then return and tap Start assistant.")
                        stopSelf()
                        return@launch
                    }
                    windowManager = GuideAccessibilityService.overlayWindowManager()
                        ?: error("Accessibility window manager unavailable")
                    params.x = 0
                    params.y = (8 * resources.displayMetrics.density).roundToInt()
                    highlighter = HighlightOverlay(this@FloatingBubbleService, this@FloatingBubbleService, windowManager)
                    if (!receiverRegistered) {
                        ContextCompat.registerReceiver(this@FloatingBubbleService, receiver,
                            IntentFilter(GuideAccessibilityService.ACTION_SCREEN_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
                        receiverRegistered = true
                    }
                    expanded = false
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    showAssistant()
                    view?.doOnPreDraw {
                        if (view?.isAttachedToWindow == true) {
                            AssistantRuntime.running()
                            sendBroadcast(Intent(ACTION_READY).setPackage(packageName))
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    Log.e("NaVStartup", "Could not attach accessibility overlay", error)
                    AssistantRuntime.failed("NaV could not display its floating panel. Re-enable NaV accessibility and try again. (${error.javaClass.simpleName})")
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val channel = "nav_assistant"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, "NaV assistant", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, FloatingBubbleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val settings = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("NaV is ready to help").setContentText("Tap the NaV island and speak to start a task.")
            .setContentIntent(settings).addAction(Notification.Action.Builder(null, "Stop assistant", stop).build())
            .setOngoing(true).build()
        microphoneService = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val types = (if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) or
            (if (microphoneService && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        try { startForeground(1, notification, types) } catch (_: SecurityException) {
            microphoneService = false
            startForeground(1, notification, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        }
    }

    private fun showAssistant() {
        view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingBubbleService)
            setViewTreeSavedStateRegistryOwner(this@FloatingBubbleService)
            setContent {
                AssistantPanel(
                    expanded = expanded,
                    maxWidth = minOf(352, resources.configuration.screenWidthDp - 24).coerceAtLeast(200).dp,
                    isThinking = thinking, isListening = listening, isExecuting = executing,
                    voiceLevel = voiceLevel, voiceTranscript = transcript, status = status,
                    hasTask = session != null, action = actionDescription(), step = stepCount,
                    onStartVoice = ::startVoice, onStopVoice = ::stopVoice,
                    onExpand = { showPanel(true) }, onCollapse = { showPanel(false) },
                    onStop = { stopTask(); announce(status) }, onExit = { stopSelf() }
                )
            }
        }
        windowManager.addView(view, params)
    }

    private fun showPanel(value: Boolean) {
        expanded = value
        params.y = ((if (value) 32 else 8) * resources.displayMetrics.density).roundToInt()
        params.x = 0
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        view?.let { windowManager.updateViewLayout(it, params) }
        if (!value) stopVoice()
    }

    private fun handleUserIntent(input: String) {
        val goal = input.trim()
        if (goal.isBlank()) return
        if (goal.length > 800) { announce("Please keep your request under 800 characters."); return }
        if (goal.lowercase() in setOf("stop", "cancel", "stop task")) { stopTask(); return }
        val clarified = clarificationGoal?.let { "$it. User reply: $goal" } ?: goal
        stopTask()
        UserIntentParser.explicitAppLaunch(goal)?.let { appName ->
            if (SystemActionExecutor(this).openAppByName(appName)) {
                status = "Opened $appName."
                announce(status)
                showPanel(false)
                return
            }
        }
        if (!GuideAccessibilityService.connected) { status = "Enable NaV's accessibility service in settings first."; announce(status); return }
        if (ApiKeyStore(this).read().isBlank()) { status = "Add your Groq API key in NaV settings first."; announce(status); return }
        session = GuidanceSession(clarified.take(800))
        stepCount = 0
        showPanel(false)
        requestStep()
    }

    private fun stopTask() {
        requestJob?.cancel(); changeJob?.cancel()
        requestJob = null; changeJob = null
        session = null; pending = null; observation = null; clarificationGoal = null
        thinking = false; executing = false
        stopVoice(); speech.stop(); if (::highlighter.isInitialized) highlighter.clear()
        status = "Task stopped. Tap to speak whenever you need help."
    }

    private fun requestStep() {
        val current = session ?: return
        if (thinking || executing || listening) return
        if (current.stepCount >= 12) { finish("This task has reached 12 steps. Start a new request to continue."); return }
        pending = null
        highlighter.clear()
        stopVoice()
        thinking = true
        showPanel(true)
        requestJob = lifecycleScope.launch {
            try {
                val wait = llm.waitMillis
                status = if (wait > 0) "Waiting ${(wait + 999) / 1000}s for the next free-tier request..." else "Reading the screen..."
                llm.awaitTurn()
                ensureActive()
                if (session !== current) return@launch
                val screen = captureStableScreen()
                if (screen == null) {
                    status = "I cannot read this screen. Open the app you want help with, then say “try again”."; announce(status)
                    return@launch
                }
                val prepared = builder.build(screen, current.goal, current.history,
                    getSharedPreferences("nav_prefs", MODE_PRIVATE).getString("preferred_language", "en-US") ?: "en-US")
                status = "Finding the next step..."
                current.stepCount++
                stepCount = current.stepCount
                val result = llm.nextStep(ApiKeyStore(this@FloatingBubbleService).read(), prepared)
                ensureActive()
                if (session !== current) return@launch
                when (result) {
                    is AgentResult.Failure -> { status = result.message; announce(status) }
                    is AgentResult.Success -> {
                        val fresh = GuideAccessibilityService.getCurrentContext()
                        if (fresh?.fingerprint() != screen.fingerprint()) {
                            status = "The screen changed while I was thinking. Say “try again” for this screen."; announce(status)
                            return@launch
                        }
                        observation = prepared
                        val action = result.action
                        when (action.type) {
                            ActionType.COMPLETE, ActionType.EXPLAIN -> finish(action.instruction)
                            ActionType.CLARIFY -> {
                                val original = current.goal
                                finish(action.instruction)
                                clarificationGoal = original
                            }
                            else -> {
                                pending = action
                                status = action.instruction
                                announce("$status Tap the mic and say do this step to confirm, or next after doing it yourself.")
                                prepared.targets[action.target]?.let {
                                    placeAwayFrom(it.bounds)
                                    highlighter.showHighlight(it.bounds)
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "Could not read this screen. Tap Speak and say try again."; announce(status) }
            finally { if (session === current) thinking = false }
        }
    }

    private suspend fun captureStableScreen(): ScreenContext? {
        // Avoid spending a request on a half-rendered transition or a closing keyboard.
        var previous = GuideAccessibilityService.getCurrentContext() ?: return null
        repeat(8) {
            delay(350)
            val current = GuideAccessibilityService.getCurrentContext() ?: return null
            if (current.fingerprint() == previous.fingerprint()) return current
            previous = current
        }
        return null
    }

    private fun actionDescription(): String {
        val action = pending ?: return ""
        return when (action.type) {
            ActionType.TAP -> "Tap: ${observation?.targets?.get(action.target)?.text.orEmpty()}"
            ActionType.SCROLL -> "Scroll ${action.value}"
            ActionType.BACK -> "Go back"
            ActionType.OPEN_APP -> "Open ${action.value}"
            else -> ""
        }
    }

    private fun executeStep() {
        val action = pending ?: return
        val observed = observation ?: return
        val current = session ?: return
        if (thinking || executing) return
        val fresh = GuideAccessibilityService.getCurrentContext()
        if (fresh?.fingerprint() != observed.context.fingerprint()) {
            pending = null; highlighter.clear()
            status = "The screen changed. Say “try again” to find the correct target."; announce(status)
            return
        }
        highlighter.clear()
        executing = true
        pending = null // Disable duplicate clicks before sending the accessibility action.
        val success = if (action.type == ActionType.OPEN_APP) SystemActionExecutor(this).openAppByName(action.value)
            else GuideAccessibilityService.execute(action, observed.context, observed.targets[action.target])
        if (!success) {
            executing = false
            status = if (action.type == ActionType.OPEN_APP) "App not found or its name is ambiguous. Open it yourself, then say “try again”."
                else "This control did not accept the action. Try it yourself, then say “try again”."
            announce(status)
            return
        }
        current.history.add(GuidanceStep("Performed: ${actionDescriptionFor(action, observed)}", action.target, fresh.appPackage, fresh.fingerprint()))
        status = "Checking the result..."
        changeJob?.cancel()
        changeJob = lifecycleScope.launch {
            delay(1000)
            if (session !== current) return@launch
            executing = false
            val after = GuideAccessibilityService.getCurrentContext()
            if (after != null && after.fingerprint() != observed.context.fingerprint()) requestStep()
            else { status = "The action was accepted, but the screen has not changed. Check the app, then say “try again”."; announce(status) }
        }
    }

    private fun actionDescriptionFor(action: AssistantAction, observed: Observation) =
        "${action.type.name.lowercase()} ${observed.targets[action.target]?.text ?: action.value}"

    private fun checkScreenChange() {
        val current = session ?: return
        val action = pending ?: return
        val observed = observation ?: return
        if (thinking || executing || listening || changeJob?.isActive == true) return
        changeJob = lifecycleScope.launch {
            delay(700)
            if (session !== current || pending !== action) return@launch
            val fresh = GuideAccessibilityService.getCurrentContext()
            if (fresh == null) {
                highlighter.clear()
                pending = null
                status = "This screen is unavailable. Return to the app, then say “try again”."; announce(status)
            } else if (fresh.fingerprint() != observed.context.fingerprint()) {
                highlighter.clear()
                current.history.add(GuidanceStep("Screen changed after guidance: ${action.instruction}", action.target, observed.context.appPackage, observed.context.fingerprint()))
                requestStep()
            }
        }
    }

    private fun placeAwayFrom(bounds: Rect) {
        if (listening) return
        val density = resources.displayMetrics.density
        // Compact the island if the target is near it, so guidance never covers the control.
        if (bounds.top < (460 * density).roundToInt()) {
            expanded = false
            params.y = if (bounds.top < (100 * density).roundToInt())
                (resources.displayMetrics.heightPixels / 2) else (8 * density).roundToInt()
        } else params.y = ((if (expanded) 32 else 8) * density).roundToInt()
        view?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun finish(message: String) {
        session = null; pending = null; observation = null
        thinking = false
        highlighter.clear()
        status = message
        announce(message)
        // A response arriving during a voice session must not move or cancel its microphone.
        if (!listening) showPanel(true)
    }

    private fun announce(message: String) {
        if (listening) return // Do not feed our own response into speech recognition.
        speech.speak(message, getSharedPreferences("nav_prefs", MODE_PRIVATE).getString("preferred_voice_id", null))
    }

    private fun handleVoiceResult(result: String, actionAtStart: AssistantAction?) {
        when (VoiceCommand.parse(result)) {
            VoiceCommand.CONFIRM -> {
                if (pending?.canExecute == true && pending === actionAtStart) executeStep()
                else announce(if (pending != null) "The suggested step changed. Tap Speak and confirm the current step."
                    else "There is no step ready to confirm. ${if (thinking) "I am still thinking." else "Tell me what you want to do."}")
            }
            VoiceCommand.NEXT -> {
                if (session != null) {
                    if (thinking) announce("I am still finding the next step.") else requestStep()
                } else announce("Tell me what you would like help with first.")
            }
            VoiceCommand.REPEAT -> announce(pending?.instruction ?: status.ifBlank { "Tap the island and tell me what you want to do." })
            VoiceCommand.STOP -> { stopTask(); announce(status) }
            VoiceCommand.CLOSE -> stopSelf()
            null -> handleUserIntent(result)
        }
    }

    private fun startVoice() {
        if (listening || executing) return
        showPanel(true)
        if (!microphoneService || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Allow microphone access in NaV, then start the assistant again."
            announce(status)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        speech.stop()
        val actionAtStart = pending
        listening = true; voiceLevel = 0f; transcript = ""
        voice.startListening(getSharedPreferences("nav_prefs", MODE_PRIVATE).getString("preferred_language", "en-US") ?: "en-US",
            object : VoiceInputManager.VoiceListener {
                override fun onReadyForSpeech() = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) { voiceLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                // Keep the session active until a final result or error arrives.
                override fun onEndOfSpeech() { voiceLevel = 0f }
                override fun onError(error: Int) {
                    listening = false; voiceLevel = 0f
                    status = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is off. Allow it in NaV settings."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition could not connect. Check your connection and tap Speak again."
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn’t catch that. Tap Speak and try again."
                        else -> "Speech recognition is unavailable. Check your phone’s speech service, then tap Speak again."
                    }
                    announce(status)
                }
                override fun onResults(results: String) {
                    listening = false; voiceLevel = 0f; transcript = results
                    handleVoiceResult(results, actionAtStart)
                }
                override fun onPartialResults(partialResults: String) { transcript = partialResults }
            })
    }

    private fun stopVoice() { voice.cancel(); listening = false; voiceLevel = 0f }

    override fun onDestroy() {
        startupJob?.cancel()
        stopTask()
        AssistantRuntime.stopped()
        if (receiverRegistered) unregisterReceiver(receiver)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        voice.destroy(); speech.destroy()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.nav.STOP_ASSISTANT"
        const val ACTION_READY = "com.example.nav.ASSISTANT_READY"
    }
}
