# App Block — Testing & Verification Guide

This document outlines the testing strategy for **App Block**, detailing automated local JVM unit tests, Robolectric test suites, and physical device test procedures.

---

## 1. Automated Test Suite (Local JVM & Robolectric)

The automated test suite is located at `app/src/test/java/com/example/appblock/AppBlockLogicTest.kt` and tests core domain invariants and edge cases:

| # | Test Scenario | Verification Focus |
|---|---------------|--------------------|
| 1 | **Session Timing & Expiration Math** | Validates duration math, active status calculation, and countdown bounds. |
| 2 | **Expiration Detection** | Verifies that sessions whose `endTimeMillis` has passed report `isExpired = true`. |
| 3 | **Multi-Package Matching** | Validates package-matching logic against single and multiple blocked package sets. |
| 4 | **Strict Mode Invariants** | Ensures active sessions with `strictMode = true` reject premature cancellation and list modifications. |
| 5 | **State Persistence & Restoration** | Confirms session state serializes to disk and restores identically across simulated process death. |
| 6 | **App Selection Safety Filters** | Verifies that App Block itself, Android Settings, System UI, Emergency Dialer, and Package Installer cannot be selected. |
| 7 | **Optional Device Admin Safety** | Confirms sessions operate seamlessly without requiring Device Owner or Device Admin. |
| 8 | **Android 12+ Exact Alarm Fallback** | Validates `TimerManager` capability checks, fallback handling, and setting intent creation. |
| 9 | **Authoritative Timestamp Reconciliation** | Confirms packages unblock automatically when `endTimeMillis` passes, even if alarms are absent. |
| 10 | **Reboot Reconciliation** | Tests `BootReceiver` handling of active and expired sessions across simulated system reboots. |

### Running the Automated Tests
```bash
gradle :app:testDebugUnitTest
```

---

## 2. Physical Device Verification Procedure

### Setup
1. Connect your Android device via USB and enable USB Debugging.
2. Build and install the debug APK:
   ```bash
   gradle :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

### Verification Steps
1. **Accessibility Permission**:
   - Open **App Block**.
   - Tap **Open Accessibility Settings** and enable **App Block**.
   - Return to the app and confirm the status updates to **Active / Enabled**.
2. **Package Selection**:
   - Tap **Continue to App Selection**.
   - Select a test application (e.g., YouTube, Instagram, or Chrome).
3. **Session Configuration**:
   - Select **30 seconds (Quick Test)**.
   - Toggle **Strict Mode** on.
   - Tap **Start Focus Session**.
4. **Interception Test**:
   - Navigate to the home screen and launch the selected test app.
   - **Expected**: `BlockScreenActivity` immediately intercepts the launch, displaying the block screen with the live countdown and no bypass options.
5. **Natural Expiration**:
   - Allow the 30-second countdown to expire.
   - **Expected**: The block overlay transitions to **Session Complete**.
   - Re-open the test app from the launcher; it opens immediately and functions normally.
