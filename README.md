# Voxera SDK — Android

Kotlin SDK for the Voxera Voice Platform. New integrations should import
`com.voxera.sdk.*` and use `VoxeraClient` with `VoxeraConfig`. The old
`com.rocs.sdk` namespace remains available for one compatibility cycle.

## Requirements

| Requirement | Version |
|---|---|
| Android API | 24+ (Android 7.0) |
| Kotlin | 1.9+ |
| Compile SDK | 34 |
| JVM Target | 17 |

## Installation

### From JitPack

The SDK is built and served by [JitPack](https://jitpack.io/#voxera-voice/voxera-android)
from this repository's tags. No account or token is needed.

**1. Add the JitPack repository** to your project-level `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

**2. Add the dependency** to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.voxera-voice:voxera-android:1.1.43")
}
```

The SDK resolves the public `io.github.webrtc-sdk:android:144.7559.09`
artifact transitively from Maven Central. Do not install a second WebRTC
artifact in the application; multiple `org.webrtc` implementations can cause
duplicate classes or native symbol conflicts.

### Local Project Dependency

If you have the SDK source checked out alongside your app:

```kotlin
// settings.gradle.kts
include(":sdk-android")
project(":sdk-android").projectDir = file("../sdk-android")

// app/build.gradle.kts
dependencies {
    implementation(project(":sdk-android"))
}
```

### Android Manifest Permissions

Add the following to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

<uses-feature android:name="android.hardware.microphone" android:required="true" />
<uses-feature android:name="android.hardware.camera"     android:required="false" />
```

> **Note:** You must request `RECORD_AUDIO` (and `CAMERA` for video) at runtime on Android 6.0+.

---

## Quick Start

```kotlin
import com.voxera.sdk.*

// 1. Configure
val config = VoxeraConfig(
    appKey    = "YOUR_APP_KEY",
    serverUrl = "https://your-server.example.com",
)

// 2. Create client
val client = VoxeraClient(context, config)

// 3. Listen for events
client.listener = object : VoxeraListener {
    override fun onConnectionStatusChanged(status: ConnectionStatus) {
        // IDLE → CONNECTING → CONNECTED
    }
    override fun onMessage(message: ConversationMessage) {
        println("${message.role}: ${message.content}")
    }
    override fun onSpeakingStatusChanged(status: SpeakingStatus) {
        // USER, AI, or NONE
    }
    override fun onError(error: VoxeraException) {
        Log.e("Voxera", "${error.code}: ${error.message}")
    }
    override fun onToolCalls(tools: List<ToolCall>, messageId: String) {
        val action = tools.firstOrNull() ?: return
        Log.d("Voxera", "action_id=${action.id}, name=${action.function.name}, args=${action.function.arguments}")
    }
}

// 4. Connect (establishes WebRTC transport + audio producer)
client.connect()

// 5. Start AI conversation (after connection is established)
client.startConversation()

// 6. End session
client.endConversation()
client.disconnect()
```

## Tool Call Integration

Use this flow when the assistant calls a tool and you need to send back its output.

The server emits the call as `tool-triggered`. The SDK subscribes to it and
hands you the payload; nothing extra is needed here.

### 1) Receive the tool call

Handle tool actions in `onToolCalls`:

```kotlin
override fun onToolCalls(tools: List<ToolCall>, messageId: String) {
        val action = tools.firstOrNull() ?: return
        val actionId = action.id
        val name = action.function.name
        val arguments = action.function.arguments // JSONObject, already parsed
}
```

Server payload shape:

```json
{
    "type": "tool-triggered",
    "content": {
        "action_id": "YJ8hC9qHC",
        "name": "schedule_message",
        "arguments": {
            "schedule_message": {
                "message": "Hi Ayman, is the appointment confirmed?",
                "recipient": { "name": "ayman" },
                "schedule": { "time": "2026-04-17T14:00:00" }
            }
        }
    }
}
```

### 2) Send `select-actions` output

Call:

```kotlin
client.selectAction(
        message = "message was scheduled to ayman",
        actionId = "YJ8hC9qHC"
)
```

This sends:

```json
{
    "message": "message was scheduled to ayman",
    "event": "tool-output",
    "action_id": "YJ8hC9qHC"
}
```

---

## Configuration

### `VoxeraConfig`

| Parameter | Type | Default | Description |
|---|---|---|---|
| `appKey` | `String` | — | **Required.** Your application key. |
| `serverUrl` | `String` | — | **Required.** Media server URL. |
| `userId` | `String?` | `null` | User identifier for session continuity. |
| `threadId` | `String?` | `null` | Thread identifier for conversation continuity. |
| `selectedVoice` | `String?` | `null` | TTS voice identifier. |
| `username` | `String?` | `null` | Display name of the person speaking — passed to the AI in the system prompt. |
| `userInfo` | `Map<String, Any>?` | `null` | Additional context/metadata about the user — appended to the AI system prompt. |
| `language` | `String?` | `null` | BCP-47 tag (`"ar"`, `"fr-CA"`) or `"auto"`. See below. |

### Language

The agent answers in whatever language the caller speaks, and follows them if
they switch mid-call. Nothing to configure.

Set `language` when you already know it — `Locale.getDefault().toLanguageTag()`
usually is that answer. A tag makes speech-to-text more accurate and slightly
faster, because it no longer has to work the language out from the first moment
of audio. `"auto"` asks for detection explicitly, which is also how you
override an agent that was pinned to the wrong language.
## API Reference

### `VoxeraClient`

#### Constructor

```kotlin
VoxeraClient(context: Context, config: VoxeraConfig)
```

#### Properties

| Property | Type | Description |
|---|---|---|
| `connectionStatus` | `ConnectionStatus` | Current connection state. |
| `conversationStatus` | `ConversationStatus` | Current conversation state. |
| `speakingStatus` | `SpeakingStatus` | Who is currently speaking. |
| `messages` | `MutableList<ConversationMessage>` | All conversation messages. |
| `sessionId` | `String?` | Current session ID. |
| `isConnected` | `Boolean` | `true` when connected. |
| `isConversationActive` | `Boolean` | `true` when conversation is active. |
| `isMuted` | `Boolean` | `true` when the local microphone is muted. |
| `isVideoEnabled` | `Boolean` | `true` when local camera is active. |
| `localVideoTrack` | `VideoTrack?` | Local camera track for rendering. |
| `isFrontCamera` | `Boolean` | Current camera facing. |

#### Connection Methods

| Method | Description |
|---|---|
| `connect()` | Connect to the server, set up WebRTC transports, and start audio. |
| `disconnect()` | End conversation (if active) and tear down the connection. |

#### Conversation Methods

| Method | Description |
|---|---|
| `startConversation()` | Begin the AI voice conversation. |
| `endConversation()` | End the current conversation. |
| `sendMessage(content)` | Send a text message to the AI (requires connection). |
| `selectAction(message, actionId)` | Send tool output for a received tool call. |
| `setMuted(muted)` | Mute or unmute the local microphone. |
| `toggleMute()` | Toggle the local microphone mute state. |

### `VoxeraListener`

Implement this interface to receive SDK events:

```kotlin
interface VoxeraListener {
    fun onConnectionStatusChanged(status: ConnectionStatus) {}
    fun onConversationStatusChanged(status: ConversationStatus) {}
    fun onSpeakingStatusChanged(status: SpeakingStatus) {}
    fun onMessage(message: ConversationMessage) {}
    fun onTranscript(text: String, isFinal: Boolean) {}
    fun onError(error: VoxeraException) {}
    fun onAudioLevel(level: Float) {}
    fun onAIAudioLevel(level: Float) {}
    fun onLocalVideoTrack(track: VideoTrack?) {}
    fun onRemoteAudioTrack(track: AudioTrack?) {}
    fun onRemoteVideoTrack(track: VideoTrack?) {}
    fun onMuteChanged(muted: Boolean) {}

    // Meeting events
    fun onRoomParticipantsUpdated(participants: List<RoomParticipant>) {}
    fun onTranscriptionsUpdated(transcriptions: List<TranscriptionEntry>) {}
    fun onWaitingRoomUpdated(waitingRoom: List<WaitingRoomEntry>) {}
    fun onBookmarksUpdated(bookmarks: List<MeetingBookmark>) {}
    fun onSummariesUpdated(summaries: List<MeetingSummary>) {}
    fun onMinutesUpdated(minutes: MeetingMinutes?) {}
    fun onRoomModeChanged(mode: RoomMode) {}
    fun onIsHostChanged(isHost: Boolean) {}
    fun onMeetingEvent(event: String, data: JSONObject) {}
    fun onToolCalls(tools: List<ToolCall>, messageId: String) {}
}
```

---

### Enums

#### `ConnectionStatus`
`IDLE` → `CONNECTING` → `CONNECTED` → `RECONNECTING` → `DISCONNECTED` → `ERROR`

#### `ConversationStatus`
`IDLE` → `STARTING` → `ACTIVE` → `ENDING` → `ENDED`

#### `SpeakingStatus`
`USER` | `AI` | `NONE`

#### `RoomMode`
`AI_MEETING` (`"ai-meeting"`) | `NORMAL_MEETING` (`"normal-meeting"`)

---

### Data Types

#### `ConversationMessage`

| Field | Type | Description |
|---|---|---|
| `id` | `String` | Unique message ID. |
| `role` | `MessageRole` | `USER`, `ASSISTANT`, or `SYSTEM`. |
| `content` | `String` | Message text. |
| `timestamp` | `Long` | Epoch milliseconds. |
| `metadata` | `Map<String, Any>?` | Optional metadata. |

#### `VoxeraException`

| Field | Type | Description |
|---|---|---|
| `message` | `String` | Human-readable error description. |
| `code` | `VoxeraErrorCode` | Error category. |
| `details` | `Map<String, Any>?` | Optional details. |

#### `VoxeraErrorCode`

`CONNECTION_FAILED` · `AUTHENTICATION_FAILED` · `WEBRTC_ERROR` · `MEDIA_ACCESS_DENIED` · `TIMEOUT` · `SERVER_ERROR` · `INVALID_CONFIG` · `NETWORK_ERROR` · `UNKNOWN_ERROR`

---

## Usage Examples

### Start & End a Conversation

```kotlin
val config = VoxeraConfig(
    appKey    = "YOUR_APP_KEY",
    serverUrl = "https://your-server.example.com",
    userId    = "user-123",           // optional, for session continuity
    threadId  = "thread-abc",         // optional, for conversation continuity
    selectedVoice = "voice-id",       // optional, TTS voice
    username  = "Alice",              // optional, tells the AI who is speaking
    userInfo  = mapOf("plan" to "pro", "locale" to "en-US"),  // optional, extra context for system prompt
)

val client = VoxeraClient(context, config)

client.listener = object : VoxeraListener {
    override fun onConnectionStatusChanged(status: ConnectionStatus) {
        when (status) {
            ConnectionStatus.CONNECTED -> {
                // Ready — start the AI voice conversation
                client.startConversation()
            }
            ConnectionStatus.DISCONNECTED -> {
                // Connection closed
            }
            ConnectionStatus.ERROR -> {
                // Handle connection failure
            }
            else -> {}
        }
    }
    override fun onConversationStatusChanged(status: ConversationStatus) {
        // IDLE → STARTING → ACTIVE → ENDING → ENDED
    }
    override fun onMessage(message: ConversationMessage) {
        Log.d("Voxera", "${message.role}: ${message.content}")
    }
    override fun onError(error: VoxeraException) {
        Log.e("Voxera", "${error.code}: ${error.message}")
    }
}

// Connect (sets up WebRTC transports + audio producer)
client.connect()

// Later — end the conversation and disconnect
client.endConversation()
client.disconnect()
```

### Mute & Unmute

```kotlin
// Mute the local microphone
client.setMuted(true)

// Unmute
client.setMuted(false)

// Listen for mute state changes
client.listener = object : VoxeraListener {
    override fun onMuteChanged(muted: Boolean) {
        // Update UI mute button
    }
}
```

### Toggle Speaker (Earpiece ↔ Speakerphone)

Speaker routing is handled via Android's `AudioManager`, not the SDK itself:

```kotlin
val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

// Switch to speakerphone
audioManager.isSpeakerphoneOn = true
audioManager.mode = AudioManager.MODE_NORMAL

// Switch to earpiece
audioManager.isSpeakerphoneOn = false
audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
```

### Listen Mode

Listen mode mutes the user's microphone so the AI doesn't receive speech input, while still playing AI audio. This is useful for monitoring or passive listening:

```kotlin
var isListenMode = false

fun toggleListenMode() {
    isListenMode = !isListenMode
    // Mute mic so the AI only plays audio, user doesn't transmit
    client.setMuted(isListenMode)
}
```

When listen mode is active, the conversation remains connected and you still receive `onMessage` and `onSpeakingStatusChanged` callbacks — the user just doesn't transmit audio.

### Video (Camera)

```kotlin
// Start the front camera after connecting
client.startCamera(front = true)

// Listen for local & remote video tracks
client.listener = object : VoxeraListener {
    override fun onLocalVideoTrack(track: VideoTrack?) {
        // Attach to a SurfaceViewRenderer
        track?.addSink(localSurfaceView)
    }
    override fun onRemoteVideoTrack(track: VideoTrack?) {
        track?.addSink(remoteSurfaceView)
    }
}

// Initialize SurfaceViewRenderers with the SDK's shared EglBase
localSurfaceView.init(client.eglBase?.eglBaseContext, null)
remoteSurfaceView.init(client.eglBase?.eglBaseContext, null)

// Switch between front and back camera
client.switchCamera()

// Stop the camera
client.stopCamera()
```

### Full ViewModel Example (Jetpack Compose)

```kotlin
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private var client: VoxeraClient? = null

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    data class UiState(
        val connectionStatus: ConnectionStatus = ConnectionStatus.IDLE,
        val messages: List<ConversationMessage> = emptyList(),
        val isMuted: Boolean = false,
        val isSpeakerOn: Boolean = true,
        val isListenMode: Boolean = false,
        val isVideoEnabled: Boolean = false,
    )

    fun startCall(appKey: String, serverUrl: String) {
        val config = VoxeraConfig(appKey = appKey, serverUrl = serverUrl)
        client = VoxeraClient(getApplication(), config).apply {
            listener = object : VoxeraListener {
                override fun onConnectionStatusChanged(status: ConnectionStatus) {
                    _uiState.value = _uiState.value.copy(connectionStatus = status)
                    if (status == ConnectionStatus.CONNECTED) startConversation()
                }
                override fun onMessage(message: ConversationMessage) {
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + message
                    )
                }
                override fun onMuteChanged(muted: Boolean) {
                    _uiState.value = _uiState.value.copy(isMuted = muted)
                }
                override fun onLocalVideoTrack(track: VideoTrack?) {
                    _uiState.value = _uiState.value.copy(isVideoEnabled = track != null)
                }
                override fun onError(error: VoxeraException) {
                    Log.e("Chat", "${error.code}: ${error.message}")
                }
            }
        }
        // Default to speakerphone
        val audioManager = getApplication<Application>()
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = true
        client?.connect()
    }

    fun endCall() {
        client?.endConversation()
        client?.disconnect()
        client = null
    }

    fun toggleMute() {
        val next = !_uiState.value.isMuted
        _uiState.value = _uiState.value.copy(isMuted = next)
        client?.setMuted(next)
    }

    fun toggleListenMode() {
        val next = !_uiState.value.isListenMode
        _uiState.value = _uiState.value.copy(isListenMode = next)
        client?.setMuted(next)  // mute mic in listen mode
    }

    fun toggleSpeaker() {
        val next = !_uiState.value.isSpeakerOn
        _uiState.value = _uiState.value.copy(isSpeakerOn = next)
        val audioManager = getApplication<Application>()
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = next
        audioManager.mode = if (next) AudioManager.MODE_NORMAL
                            else AudioManager.MODE_IN_COMMUNICATION
    }

    fun toggleCamera() {
        val c = client ?: return
        if (c.isVideoEnabled) {
            c.stopCamera()
        } else {
            c.startCamera()
        }
    }

    fun switchCamera() { client?.switchCamera() }

    override fun onCleared() { client?.disconnect() }
}
```

## Publishing a New Version

JitPack builds a release from a git tag, so publishing is tagging:

1. Bump `voxeraVersion` in `build.gradle.kts`, and the pins in the React
   Native and Flutter bridges to match.
2. Push to `voxera-voice/voxera-android` and tag the commit with the version:

   ```bash
   git tag 1.1.43 && git push origin 1.1.43
   ```

3. Open `https://jitpack.io/#voxera-voice/voxera-android/1.1.43` (or request
   the artifact) to trigger the build. `jitpack.yml` pins JDK 17 and runs
   `./gradlew publishReleasePublicationToMavenLocal`, which you can run locally
   first to catch a failing build before the tag is public.

A tag, once built, is cached by JitPack; fix mistakes with a new version.

---

## Demo App

The `android-voice-chat-demo/` directory contains a full Jetpack Compose demo app. To run it:

1. Open `android-voice-chat-demo/` in Android Studio
2. The demo references the SDK as a local project dependency
3. Set your `appKey` in the setup screen
4. Build and run on a device (emulator mic may not work reliably)

---

## Architecture

```
com.voxera.sdk/
└── VoxeraAliases.kt     — Public Voxera API facade and migration aliases
com.rocs.sdk/             — Compatibility implementation namespace
├── SocketSignaling.kt    — Socket.IO signaling layer
└── WebRTCManager.kt      — mediasoup Device, transports, producers, consumers
```

The SDK uses **mediasoup-client-android** for WebRTC transport (SFU architecture) and **Socket.IO** for signaling. All networking runs on Kotlin coroutines. UI callbacks are dispatched to `Dispatchers.Main`.

## License

Proprietary. All rights reserved.
