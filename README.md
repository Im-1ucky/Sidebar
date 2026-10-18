# Android Sidebar

A native Android sidebar that provides quick access to screenshot capture, screen recording, and app shortcuts through a persistent floating overlay.

Built with Kotlin and Android's native APIs, Android Sidebar is designed to work on non-rooted Android devices.

## Features

- **Floating sidebar** — access sidebar controls without returning to the app dashboard.
- **Screenshot capture** — capture screenshots using the supported Android screenshot mechanism.
- **Screen recording** — record the screen after granting Android's screen-capture consent.
- **Audio recording options** — configure available audio sources, including device audio and microphone audio, subject to Android and device support.
- **App shortcuts** — select installed apps and launch them from the sidebar.
- **Storage selection** — choose separate folders for screenshots and screen recordings.
- **Persistent service** — keep the sidebar available when the dashboard is closed, subject to Android background-execution restrictions.
- **System-aware theme** — follow Android's light and dark theme with a neutral black, white, and grey design.

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose and Android native views
- **Platform:** Android
- **Minimum SDK:** Android 11 (API 30)
- **Compile SDK:** API 37
- **Target SDK:** API 36
- **Build system:** Gradle with Kotlin DSL

## Requirements

- Android Studio with a compatible Android SDK and JDK.
- Android device running Android 11 or later.
- USB debugging enabled for installation through ADB, or use Android Studio's device deployment.
- Required permissions granted for the features you want to use.

Root access is not required.

## Getting Started

### 1. Clone the repository

```bash
git clone <YOUR_REPOSITORY_URL>
cd Sidebar
```

Replace `<YOUR_REPOSITORY_URL>` with your Git repository URL.

Alternatively, open the existing project directory directly in Android Studio.

### 2. Sync Gradle

Open the project in Android Studio and allow Gradle to synchronize dependencies.

### 3. Build the APK

From the project root, run:

```bash
./gradlew assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 4. Install the app

Connect your Android device with USB debugging enabled, then run:

```bash
./gradlew installDebug
```

Alternatively, install the APK manually from Android Studio or transfer it to your device.

## Permissions and Setup

Android Sidebar relies on system permissions for features that operate outside the main activity.

| Permission or access    | Purpose                                                      |
| ----------------------- | ------------------------------------------------------------ |
| Display over other apps | Display the floating sidebar                                 |
| Notifications           | Show service and recording notifications where required      |
| Foreground service      | Keep the sidebar service running                             |
| Screen-capture consent  | Allow screen recording through Android's MediaProjection API |
| Microphone              | Record external audio when requested                         |
| Storage folder access   | Save screenshots and recordings to user-selected folders     |

Grant only the permissions required by the features you use.

### Storage configuration

Choose a destination folder for screenshots and another for recordings through the folder picker. Android's Storage Access Framework provides access to the selected locations without requiring unrestricted storage access.

## How It Works

### Sidebar service

The sidebar runs independently of the dashboard activity through a foreground service. The service manages the floating overlay and its controls.

Closing the dashboard does not necessarily stop the sidebar. However, Android may stop background services under certain conditions.

### Screenshot capture

The screenshot flow uses the Android-supported capture mechanism implemented by the app. Screenshot access depends on the relevant permissions and system restrictions.

### Screen recording

Screen recording uses Android's MediaProjection framework. Android requires user consent before a recording session can begin. Recording is managed separately from the sidebar service because it has its own lifecycle and foreground-service requirements.

### App shortcuts

The sidebar can display selected installed applications for quick launching. Launching applications in floating windows depends on device and Android support; ordinary app launching is not equivalent to unrestricted floating-window support.

## Important Limitations

- **Background execution:** Battery optimization and vendor-specific restrictions may stop the sidebar service.
- **Force-stop:** Android generally prevents a force-stopped app from restarting services until the user launches it again.
- **Screen recording:** MediaProjection requires system consent, and recording sessions are subject to Android lifecycle restrictions.
- **Audio capture:** Internal audio availability depends on Android version, device support, and the target application's audio-capture policy.
- **Screenshot access:** Screenshot capture may be restricted by secure windows, protected content, or system-level limitations.
- **Floating windows:** Launching arbitrary applications in resizable floating windows is not universally supported on Android.
- **Device compatibility:** Behavior may differ across manufacturers and Android versions.

## Development

To build the project:

```bash
./gradlew assembleDebug
```

To install it on a connected device:

```bash
./gradlew installDebug
```

To clean generated build files:

```bash
./gradlew clean
```

## Project Status

Android Sidebar is a native Android utility under development. Features and compatibility may vary as implementation and device testing continue.

## License

No license has been specified yet. Add a `LICENSE` file before distributing the project as open source.
