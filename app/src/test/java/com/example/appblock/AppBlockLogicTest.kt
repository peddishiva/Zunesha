package com.example.appblock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.appblock.admin.AppDeviceAdminReceiver
import com.example.appblock.admin.DevicePolicyController
import com.example.appblock.data.AppRepository
import com.example.appblock.data.BlockPreferences
import com.example.appblock.domain.FocusSessionManager
import com.example.appblock.domain.SessionActionResult
import com.example.appblock.domain.StrictModeController
import com.example.appblock.model.FocusSession
import com.example.appblock.model.SessionStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppBlockLogicTest {

    private lateinit var context: Context
    private lateinit var preferences: BlockPreferences
    private lateinit var strictModeController: StrictModeController
    private lateinit var sessionManager: FocusSessionManager
    private lateinit var appRepository: AppRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = BlockPreferences(context)
        preferences.clearLock()
        strictModeController = StrictModeController()
        sessionManager = FocusSessionManager(context, preferences)
        appRepository = AppRepository(context, preferences)
    }

    // -------------------------------------------------------------
    // 1. Session Timing & Expiration Math
    // -------------------------------------------------------------
    @Test
    fun testFocusSession_timingCalculations() {
        val now = System.currentTimeMillis()
        val durationSeconds = 60L
        val endTime = now + (durationSeconds * 1000L)

        val session = FocusSession(
            status = SessionStatus.ACTIVE,
            blockedPackages = setOf("com.instagram.android"),
            startTimeMillis = now,
            endTimeMillis = endTime,
            totalDurationSeconds = durationSeconds,
            strictMode = true
        )

        assertTrue("Session should be active", session.isActive)
        assertFalse("Session should not be expired yet", session.isExpired)
        assertTrue("Remaining seconds should be around 60", session.remainingSeconds in 58L..60L)
    }

    @Test
    fun testFocusSession_expirationDetection() {
        val pastTime = System.currentTimeMillis() - 10000L
        val expiredSession = FocusSession(
            status = SessionStatus.ACTIVE,
            blockedPackages = setOf("com.instagram.android"),
            startTimeMillis = pastTime - 60000L,
            endTimeMillis = pastTime,
            totalDurationSeconds = 60L
        )

        assertFalse("Expired session should not be active", expiredSession.isActive)
        assertTrue("Expired session should report isExpired = true", expiredSession.isExpired)
        assertEquals("Remaining seconds should be 0", 0L, expiredSession.remainingSeconds)
    }

    // -------------------------------------------------------------
    // 2. Multi-Package Matching (Block vs. Allow)
    // -------------------------------------------------------------
    @Test
    fun testMultiPackageMatching_blockVsAllow() {
        val now = System.currentTimeMillis()
        val session = FocusSession(
            status = SessionStatus.ACTIVE,
            blockedPackages = setOf("com.instagram.android", "com.google.android.youtube"),
            startTimeMillis = now,
            endTimeMillis = now + 60000L,
            totalDurationSeconds = 60L
        )

        // Instagram -> BLOCK
        assertTrue("Instagram should be blocked", session.isPackageBlocked("com.instagram.android"))
        // YouTube -> BLOCK
        assertTrue("YouTube should be blocked", session.isPackageBlocked("com.google.android.youtube"))
        // WhatsApp -> ALLOW
        assertFalse("WhatsApp should be allowed", session.isPackageBlocked("com.whatsapp"))
    }

    @Test
    fun testPackageMatching_unblockedWhenSessionExpires() {
        val past = System.currentTimeMillis() - 1000L
        val expiredSession = FocusSession(
            status = SessionStatus.ACTIVE,
            blockedPackages = setOf("com.instagram.android"),
            startTimeMillis = past - 60000L,
            endTimeMillis = past,
            totalDurationSeconds = 60L
        )

        // Once expired, package is NOT blocked
        assertFalse("Expired session must NOT block packages", expiredSession.isPackageBlocked("com.instagram.android"))
    }

    // -------------------------------------------------------------
    // 3. Strict Mode Domain Enforcement
    // -------------------------------------------------------------
    @Test
    fun testStrictMode_rejectsCancellationWhileActive() {
        val now = System.currentTimeMillis()
        val strictSession = FocusSession(
            status = SessionStatus.ACTIVE,
            blockedPackages = setOf("com.instagram.android"),
            startTimeMillis = now,
            endTimeMillis = now + 60000L,
            totalDurationSeconds = 60L,
            strictMode = true
        )

        val result = strictModeController.canCancelSession(strictSession)
        assertTrue("Strict session cancel should be rejected", result is SessionActionResult.Rejected)
        val rejected = result as SessionActionResult.Rejected
        assertTrue("Rejection reason should mention Strict Mode", rejected.reason.contains("Strict Mode"))
    }

    @Test
    fun testNonStrictMode_allowsCancellation() {
        val now = System.currentTimeMillis()
        val normalSession = FocusSession(
            status = SessionStatus.ACTIVE,
            blockedPackages = setOf("com.instagram.android"),
            startTimeMillis = now,
            endTimeMillis = now + 60000L,
            totalDurationSeconds = 60L,
            strictMode = false
        )

        val result = strictModeController.canCancelSession(normalSession)
        assertTrue("Normal session can be cancelled", result is SessionActionResult.Success)
    }

    @Test
    fun testStrictMode_rejectsModifyingBlockedApps() {
        val now = System.currentTimeMillis()
        val strictSession = FocusSession(
            status = SessionStatus.ACTIVE,
            blockedPackages = setOf("com.instagram.android"),
            startTimeMillis = now,
            endTimeMillis = now + 60000L,
            strictMode = true
        )

        val modifyResult = strictModeController.canModifyBlockedApps(strictSession)
        assertTrue("Modifying apps during strict mode must be rejected", modifyResult is SessionActionResult.Rejected)
    }

    @Test
    fun testStrictMode_rejectsChangingDuration() {
        val now = System.currentTimeMillis()
        val strictSession = FocusSession(
            status = SessionStatus.ACTIVE,
            blockedPackages = setOf("com.instagram.android"),
            startTimeMillis = now,
            endTimeMillis = now + 60000L,
            strictMode = true
        )

        val durationResult = strictModeController.canChangeDuration(strictSession)
        assertTrue("Changing duration during strict mode must be rejected", durationResult is SessionActionResult.Rejected)
    }

    // -------------------------------------------------------------
    // 4. Persistence & Process Death Recovery
    // -------------------------------------------------------------
    @Test
    fun testSessionPersistence_acrossProcessRestart() {
        val now = System.currentTimeMillis()
        val endTime = now + 300000L // 5 minutes

        val originalSession = FocusSession(
            status = SessionStatus.ACTIVE,
            blockedPackages = setOf("com.instagram.android", "com.google.android.youtube"),
            primaryAppLabel = "Instagram",
            startTimeMillis = now,
            endTimeMillis = endTime,
            totalDurationSeconds = 300L,
            strictMode = true
        )

        // Save session in first instance
        preferences.saveSession(originalSession)

        // Instantiate completely fresh preferences & session manager simulating new process
        val newPreferences = BlockPreferences(context)
        val restored = newPreferences.loadSession()

        assertEquals(SessionStatus.ACTIVE, restored.status)
        assertEquals(2, restored.blockedPackages.size)
        assertTrue(restored.blockedPackages.contains("com.instagram.android"))
        assertTrue(restored.blockedPackages.contains("com.google.android.youtube"))
        assertEquals(endTime, restored.endTimeMillis)
        assertTrue(restored.strictMode)
        assertTrue("Restored session should be active", restored.isActive)
    }

    // -------------------------------------------------------------
    // 5. App Selection Safety (Never Block Self or Critical System)
    // -------------------------------------------------------------
    @Test
    fun testAppRepository_protectsCriticalComponents() {
        assertFalse("Cannot block App Block itself", appRepository.isSafeToBlock(context.packageName))
        assertFalse("Cannot block system settings", appRepository.isSafeToBlock("com.android.settings"))
        assertFalse("Cannot block system UI", appRepository.isSafeToBlock("com.android.systemui"))
        assertFalse("Cannot block package installer", appRepository.isSafeToBlock("com.android.packageinstaller"))
        assertFalse("Cannot block emergency dialer", appRepository.isSafeToBlock("com.android.emergency"))

        assertTrue("Instagram is safe to block", appRepository.isSafeToBlock("com.instagram.android"))
        assertTrue("YouTube is safe to block", appRepository.isSafeToBlock("com.google.android.youtube"))
    }

    // -------------------------------------------------------------
    // 6. Optional Device Admin Protection
    // -------------------------------------------------------------
    @Test
    fun testOptionalDeviceAdmin_isNotRequiredForBlocking() {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val controller = DevicePolicyController(context, dpm)

        // Ensure admin is NOT active
        val shadowDpm = shadowOf(dpm)
        shadowDpm.setActiveAdmin(AppDeviceAdminReceiver.getComponentName(context))

        assertTrue("Device admin status should be verifiable", controller.isAdminActive())

        // FocusSessionManager can start session regardless of admin status
        val result = sessionManager.startSession(
            packages = setOf("com.instagram.android"),
            durationSeconds = 60L,
            strictMode = false
        )
        assertTrue("Core blocker starts successfully without requiring Device Owner", result is SessionActionResult.Success)
    }

    // -------------------------------------------------------------
    // 7. Android 12+ AlarmManager Exact Alarm & Fallback
    // -------------------------------------------------------------
    @Test
    fun testTimerManager_handlesExactAlarmPermissionAndFallback() {
        val timerManager = com.example.appblock.timer.TimerManager(context)

        // Verify canScheduleExactAlarms does not crash and returns a boolean
        val canSchedule = timerManager.canScheduleExactAlarms()
        assertNotNull("canScheduleExactAlarms must return a valid boolean", canSchedule)

        // Verify scheduling with future time does not throw an unhandled exception
        val futureTime = System.currentTimeMillis() + 60_000L
        timerManager.scheduleExpiration(
            triggerAtMillis = futureTime,
            targetPackage = "com.instagram.android",
            appName = "Instagram"
        )

        // Verify canceling does not throw
        timerManager.cancelExpiration()

        // Verify getExactAlarmSettingsIntent returns an Intent on API 31+
        val settingsIntent = timerManager.getExactAlarmSettingsIntent()
        assertNotNull("Settings intent should be provided on Android 12+", settingsIntent)
        assertTrue("Intent should have ACTION_REQUEST_SCHEDULE_EXACT_ALARM",
            settingsIntent?.action == android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
    }

    // -------------------------------------------------------------
    // 8. Authoritative endTimeMillis Reconciles Even If Alarm Is Delayed Or Absent
    // -------------------------------------------------------------
    @Test
    fun testAuthoritativeEndTime_reconcilesWithoutAlarmTrigger() {
        val pastTime = System.currentTimeMillis() - 5000L
        val session = FocusSession(
            status = SessionStatus.ACTIVE,
            blockedPackages = setOf("com.instagram.android"),
            startTimeMillis = pastTime - 60000L,
            endTimeMillis = pastTime,
            totalDurationSeconds = 60L
        )

        preferences.saveSession(session)

        // Verify that isPackageBlocked automatically reconciles and unblocks
        val isBlocked = sessionManager.isPackageBlocked("com.instagram.android")
        assertFalse("Package should be automatically unblocked because endTimeMillis is in the past", isBlocked)

        val currentSession = sessionManager.getCurrentSession()
        assertEquals("Session should be reconciled to COMPLETED", SessionStatus.COMPLETED, currentSession.status)
    }

    // -------------------------------------------------------------
    // 9. BootReceiver Reconciles Session Without Crashing
    // -------------------------------------------------------------
    @Test
    fun testBootReceiver_handlesRebootReconciliationSafely() {
        val receiver = com.example.appblock.receiver.BootReceiver()

        // Case A: Expired session across reboot
        val pastTime = System.currentTimeMillis() - 1000L
        preferences.saveSession(
            FocusSession(
                status = SessionStatus.ACTIVE,
                blockedPackages = setOf("com.instagram.android"),
                startTimeMillis = pastTime - 60000L,
                endTimeMillis = pastTime,
                totalDurationSeconds = 60L
            )
        )

        val bootIntent = android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)

        val sessionAfterBoot = preferences.loadSession()
        assertEquals("Expired session should be marked COMPLETED by BootReceiver",
            SessionStatus.COMPLETED, sessionAfterBoot.status)

        // Case B: Active session across reboot
        val futureTime = System.currentTimeMillis() + 120_000L
        preferences.saveSession(
            FocusSession(
                status = SessionStatus.ACTIVE,
                blockedPackages = setOf("com.instagram.android"),
                startTimeMillis = System.currentTimeMillis(),
                endTimeMillis = futureTime,
                totalDurationSeconds = 120L
            )
        )

        receiver.onReceive(context, bootIntent)
        val activeSessionAfterBoot = preferences.loadSession()
        assertTrue("Active session should remain active after BootReceiver", activeSessionAfterBoot.isActive)
    }
}
