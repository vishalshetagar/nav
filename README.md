# NaV — Android screen guidance with Groq

NaV is an Android 10+ floating assistant inspired by [Clicky](https://github.com/farzaa/clicky). It reads the foreground app through Android Accessibility, asks Groq for one next step, and highlights the exact control. Tap the voice island and speak your task. You can tap the control yourself or say **do this step** to have NaV click, scroll, go back, or open an app. It observes the result before requesting another step.

This repository builds an Android app, not a Windows/macOS client.

## Get started

1. Build/install `app/build/outputs/apk/debug/app-debug.apk`.
2. Open NaV. Paste your [Groq API key](https://console.groq.com/keys) and press **Save key**.
3. Enable NaV's accessibility service. It provides both screen access and the trusted assistant overlay; no separate overlay permission is needed. Allow microphone access: task input is voice-only, with no text box or keyboard.
4. Press **Start assistant** in the status card at the top. NaV waits for Android's accessibility connection and shows a compact black voice island at the top of the screen. If accessibility is disabled, it opens Android accessibility settings and starts when you return after enabling it. The status card distinguishes enabled from connected and shows startup failures persistently.
5. Open the app you need help with, then tap the island and speak, for example “Help me turn on dark mode in Settings”. A live waveform responds to the microphone. Your request sends when you finish speaking; the transcript is display-only.
6. Listen to the instruction and follow the highlight. Tap **Speak** and say **do this step** to confirm an action, **next** or **try again** to observe the current screen, **repeat** to hear the instruction, or **stop** to cancel. Say **close assistant** to shut down the island. These commands are handled locally without a Groq request (next/try again requests the next step).
7. Use the minus button to minimize the island. It remains touchable without taking keyboard focus from the app. **Stop** is also available as an immediate touch control; the notification can stop the assistant at any time.

The island expands for listening and guidance, and compacts or moves away from highlighted controls. Both floating windows use Android accessibility overlays, so ordinary overlay restrictions in Settings do not hide them. Straightforward requests such as “Open Settings” launch a uniquely matched installed app locally, without spending Groq tokens. Microphone listening is explicitly started by a tap, ends after one request, and never loops in the background. Android speech recognition and text-to-speech are used; Groq remains the only agent API.

## Groq-only operation and free-tier budget

- The only model endpoint is `https://api.groq.com/openai/v1/chat/completions`.
- Default: `openai/gpt-oss-20b`, low reasoning effort, 768 maximum completion tokens, strict JSON schema. There is no alternate provider or automatic model upgrade.
- One observation is limited to 6,400 serialized characters, at most 36 controls, 20 supporting text labels, and three short previous steps. Only the selected observation's exact target IDs can be acted on.
- Requests are paced at least 30 seconds apart. Capture happens **after** waiting, so the model sees a current screen. Groq's `Retry-After` and token reset headers can extend the delay. Network/API errors never cause automatic retry storms.
- A task is limited to 12 model requests. Unchanged screens do not trigger requests. The UI explains waiting, quota, authentication, and network errors.
- Free-plan limits vary by organization and can change; check [Groq's limits](https://console.groq.com/docs/rate-limits). Pacing reduces usage; it cannot guarantee unlimited free operation. Model support is documented in [Groq's model list](https://console.groq.com/docs/models) and [structured outputs](https://console.groq.com/docs/structured-outputs).

## Privacy and behavior

The key is encrypted with an Android Keystore AES-GCM key. Old plaintext key preferences migrate on first read. Keys/preferences are excluded from device transfer; backups are disabled. No key, screen text, or voice transcript is logged.

Screen text may contain personal information. NaV sends the bounded observation directly to Groq only during a task. Password nodes and Android-marked sensitive subtrees are excluded. No screenshots or OCR are collected. Recent responses and task history are kept in memory only. Voice input and speech output use Android's installed speech services, which may require their own network connection; no additional AI API key is needed.

Clicks resolve a node path in the observed window and recheck the entire screen fingerprint, current label, enabled state, and bounds. Changed screens invalidate the step. Duplicate labels are distinct targets. NaV does not generate coordinate taps, type into fields, enter credentials, or keep acting unattended. Every executable step shows its operation and target and requires the explicit spoken command **do this step**. A different step arriving while you speak cannot inherit that confirmation.

Apps with incomplete accessibility trees, custom canvases, games, protected screens, or controls that reject accessibility actions may require manual navigation. NaV explains missing context instead of guessing. Locked devices are not read.

## Build and verify

Use Android Studio with Android SDK 37 and JDK 21 (the bundled JBR is suitable). Set `JAVA_HOME` or Android Studio's Gradle JDK. Set `sdk.dir` in your untracked `local.properties`, or configure `ANDROID_HOME`. Machine-specific paths are not required in the repository.

On Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
.\gradlew.bat connectedDebugAndroidTest
```

On macOS/Linux use `./gradlew` with the same tasks. Device tests require an unlocked disposable emulator/device; they temporarily enable NaV accessibility and restore the previous setting afterward. Use at least 4 GB emulator RAM for a current Google Play system image. Tests use a separate fixture app with duplicate buttons, a nested label, a disabled control, a password field and a scrollable list. They do not call Groq or require a key.

Unit tests cover strict action validation, bounded and escaped observations, cancellation, incomplete replies, request pacing, rate-limit handling and unambiguous app matching. Device tests cover real accessibility extraction, exact clicks, stale-screen rejection, scrolling, startup, and expanding/collapsing the voice island without editable fields or keyboard focus. A successful build does not verify a live Groq account; test with your own key and representative apps before relying on it.

## Code map

- `accessibility/GuideAccessibilityService.kt`: bounded extraction and revalidated node actions.
- `llm/PromptBuilder.kt`, `ActionParser.kt`, `LLMService.kt`: compact observation, strict protocol and Groq transport.
- `overlay/FloatingBubbleService.kt`: cancellable task lifecycle and floating controls.
- `overlay/HighlightOverlay.kt`: correctly positioned touch-through highlights.
- `settings/ApiKeyStore.kt`: encrypted local credentials.

## Startup troubleshooting (1.0.1)

If the floating assistant is missing, open NaV and read the status card at the top. **Enabled** means the Android setting is on; **connected** means Android has actually bound the service. Startup waits up to eight seconds for binding. If it never connects, use **Open NaV accessibility settings**, toggle **Use NaV** off and on, and return. **Show voice island** restores the existing island to the top of the screen without creating a second window. Starting also saves any unsaved key text; an absent key no longer prevents the panel from appearing.

The startup regression tests cover delayed accessibility binding, reopening the panel without duplicate windows, and a persistent error when binding never happens.
