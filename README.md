# App Block

A modern, consumer-focused Android distraction blocker designed to help users reclaim focus by restricting access to distracting applications during timed sessions.

---

## 1. Overview

**App Block** helps users eliminate digital distractions during work, study, or digital detox periods. Unlike enterprise-focused device-owner tools or insecure screen-overlay workarounds, App Block utilizes standard Android accessibility events paired with robust domain-level session enforcement to block distraction apps instantly and reliably.

---

## 2. Key Features

- **Custom Distraction App Selection**: Select any installed user application (social media, streaming, games, browsers) while safeguarding critical system components (Phone, Settings, Emergency, System UI).
- **Flexible Focus Sessions**: Configurable countdown durations ranging from quick focus sprints (30s test / 5m / 15m) to deep-work sessions (25m / 45m / 60m).
- **Strict Mode Enforcement**: When enabled, focus sessions cannot be prematurely cancelled, bypassed, or modified until the timer naturally completes.
- **Immediate Foreground Interception**: Android `AccessibilityService` detects when a blocked app window gains focus and instantly launches an un-bypassable blocking interface (`BlockScreenActivity`).
- **Resilient Background Timing**: Employs wall-clock timestamps (`endTimeMillis`) as the authoritative source of truth, complemented by Android 12+ `AlarmManager` exact/inexact background scheduling and `BOOT_COMPLETED` reconciliation across device restarts.
- **Optional Anti-Uninstall Layer**: Experimental Device Administration receiver for users who desire an additional guard against uninstallation during sessions, without making Device Admin mandatory for core functionality.

---

## 3. Technology Stack & Architecture

- **Language & Framework**: Kotlin, Jetpack Compose, Material 3 (M3).
- **UI Architecture**: Single Activity (`MainActivity`) with reactive Jetpack Compose navigation and MVVM state management (`BlockViewModel`).
- **Domain Enforcement**: Clean Architecture separation (`FocusSessionManager`, `StrictModeController`).
- **Interception Engine**: `AccessibilityService` (`AppBlockAccessibilityService`) processing window state change events (`TYPE_WINDOW_STATE_CHANGED`).
- **Blocking Overlay**: Fullscreen dialog activity (`BlockScreenActivity`) with back-press suppression and home-intent routing.
- **Persistence**: Synchronous atomic persistence using Android `SharedPreferences` (`BlockPreferences`).
- **Background Wakeup**: `AlarmManager` with `SCHEDULE_EXACT_ALARM` support on Android 12+ and graceful fallback to inexact alarms.

---

## 4. System Architecture

```
User Selects Apps & Duration
           │
           ▼
    [FocusSessionManager] ──> Persists to [BlockPreferences]
           │
           ├──────────────────────────────┐
           ▼                              ▼
 [TimerManager (AlarmManager)]    [AppBlockAccessibilityService]
  • SCHEDULE_EXACT_ALARM           • Listens to TYPE_WINDOW_STATE_CHANGED
  • Exact / Inexact fallback       • Evaluates isPackageBlocked(pkg)
  • Wakes device on expiration     • Evaluates endTimeMillis authority
           │                              │
           │ (on expiration alarm)        │ (if blocked package detected)
           ▼                              ▼
[BlockExpirationReceiver]         [BlockScreenActivity]
  • Marks session COMPLETED        • Displays full-screen block
  • Unblocks all applications      • Live countdown timer
                                   • Routes user back to home
```

### Authoritative Timing Architecture
Session validity is strictly calculated from the wall-clock timestamp (`endTimeMillis`):
- `FocusSession.isActive`: Evaluated as `status == SessionStatus.ACTIVE && System.currentTimeMillis() < endTimeMillis`.
- **Reconciliation**: Even if background alarms are delayed or batched by Doze Mode, the moment any interaction occurs (launching an app or resuming App Block), `reconcileSessionState()` runs and immediately transitions expired sessions to `COMPLETED`.

---

## 5. Required Permissions

| Permission | Protection Level | Purpose |
|------------|------------------|---------|
| `BIND_ACCESSIBILITY_SERVICE` | Signature / System | Required for foreground package detection to intercept blocked apps. |
| `SCHEDULE_EXACT_ALARM` | Normal / Runtime on API 31+ | Optional high-precision background timer wakeup on Android 12+. |
| `RECEIVE_BOOT_COMPLETED` | Normal | Reconciles and restores active session schedules upon device reboot. |
| `QUERY_ALL_PACKAGES` | Normal | Discovers installed user apps for distraction selection. |

---

## 6. Project Structure

```
app/src/main/java/com/example/appblock/
├── admin/
│   ├── AppDeviceAdminReceiver.kt     # Optional experimental anti-uninstall receiver
│   └── DevicePolicyController.kt     # Device policy verification helper
├── data/
│   ├── AppRepository.kt              # App discovery & safety filtering
│   └── BlockPreferences.kt          # SharedPreferences persistence
├── domain/
│   ├── FocusSessionManager.kt        # Session lifecycle & reconciliation
│   └── StrictModeController.kt       # Strict mode invariants
├── model/
│   ├── FocusSession.kt               # Session domain model & state
│   ├── InstalledApp.kt               # Application display model
│   └── LockState.kt                  # Timing models
├── receiver/
│   ├── BlockExpirationReceiver.kt    # AlarmManager wakeup receiver
│   └── BootReceiver.kt               # Boot completion reconciliation
├── service/
│   ├── AccessibilityServiceHelper.kt # Permission intent helper
│   └── AppBlockAccessibilityService.kt # Real-time foreground detection
├── timer/
│   └── TimerManager.kt               # Android 12+ AlarmManager scheduler
└── ui/
    ├── AppBlockApp.kt                # Jetpack Compose UI screens
    ├── BlockScreenActivity.kt        # Interception block overlay
    ├── BlockViewModel.kt             # UI state holder & business logic
    └── theme/                        # Material 3 typography, color & shape
```

---

## 7. Building & Testing

### Prerequisites
- Android Studio Ladybug or newer.
- Android SDK 36 (Minimum SDK 24 / Android 7.0+).
- JDK 11+.

### Build Commands
```bash
# Compile debug APK
gradle :app:assembleDebug

# Run unit and Robolectric tests
gradle :app:testDebugUnitTest
```

---

## 8. Privacy & Security

- **Zero Remote Telemetry**: All blocking decisions, package lists, and timers run 100% locally on-device.
- **Protected Components**: System Settings, Emergency Dialer, Package Installer, and System UI can never be selected or blocked.
- **No Data Harvesting**: The accessibility service only inspects the current package name for blocked list matching; no UI content, text input, or keystrokes are inspected, logged, or transmitted.

---

## 9. License

This project is open source and available under the [MIT License](LICENSE).
