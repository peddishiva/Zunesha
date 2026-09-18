package com.example.appblock.ui

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.appblock.admin.DevicePolicyController
import com.example.appblock.data.AppRepository
import com.example.appblock.data.BlockPreferences
import com.example.appblock.domain.FocusSessionManager
import com.example.appblock.domain.SessionActionResult
import com.example.appblock.model.FocusSession
import com.example.appblock.model.InstalledApp
import com.example.appblock.model.LockState
import com.example.appblock.model.LockStatus
import com.example.appblock.model.SessionStatus
import com.example.appblock.receiver.BlockExpirationReceiver
import com.example.appblock.service.AccessibilityServiceHelper
import com.example.appblock.timer.TimerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppScreen {
    ACCESSIBILITY_SETUP,
    SELECT_APPS,
    SESSION_SETUP,
    ACTIVE_SESSION,
    SESSION_COMPLETE
}

data class BlockUiState(
    val currentScreen: AppScreen = AppScreen.ACCESSIBILITY_SETUP,
    val isAccessibilityEnabled: Boolean = false,
    val isAdminActive: Boolean = false,
    val installedApps: List<InstalledApp> = emptyList(),
    val filteredApps: List<InstalledApp> = emptyList(),
    val searchQuery: String = "",
    val selectedPackages: Set<String> = emptySet(),
    val selectedApp: InstalledApp? = null,
    val selectedDurationMinutes: Int = 1,
    val selectedDurationSeconds: Long = 60L,
    val strictMode: Boolean = false,
    val remainingSeconds: Long = 0L,
    val targetPackageName: String = "",
    val targetAppName: String = "",
    val session: FocusSession = FocusSession(),
    val errorMessage: String? = null,
    val isLoadingApps: Boolean = false,
    val canScheduleExactAlarms: Boolean = true,

    // Backward compatibility fields
    val isDeviceOwner: Boolean = false,
    val isTargetSuspended: Boolean = false,
    val adbCommand: String = "",
    val adbCommandShort: String = "",
    val adbRemoveCommand: String = ""
)

class BlockViewModel @JvmOverloads constructor(
    application: Application,
    private val sessionManager: FocusSessionManager = FocusSessionManager(application),
    private val appRepository: AppRepository = AppRepository(application),
    private val preferences: BlockPreferences = BlockPreferences(application),
    private val controller: DevicePolicyController = DevicePolicyController(application),
    private val timerManager: TimerManager = TimerManager(application)
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AppBlock"
    }

    private val _uiState = MutableStateFlow(BlockUiState())
    val uiState: StateFlow<BlockUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null

    init {
        Log.i(TAG, "BlockViewModel initialized")
        val savedPackages = preferences.getSelectedPackages()
        _uiState.update { it.copy(selectedPackages = savedPackages) }

        refreshState()
        loadInstalledApps()
        observeExpirationEvents()
    }

    /**
     * Refreshes accessibility status, admin status, exact alarm capability, and reconciles session timing.
     */
    fun refreshState() {
        val context = getApplication<Application>()
        val isAccessEnabled = AccessibilityServiceHelper.isAccessibilityServiceEnabled(context)
        val isAdminActive = controller.isAdminActive()
        val canScheduleExact = timerManager.canScheduleExactAlarms()
        val session = sessionManager.reconcileSessionState()

        val primaryPackage = session.blockedPackages.firstOrNull() ?: ""
        val primaryAppLabel = session.primaryAppLabel ?: primaryPackage

        _uiState.update { current ->
            val nextScreen = when {
                session.status == SessionStatus.ACTIVE && !session.isExpired -> AppScreen.ACTIVE_SESSION
                session.status == SessionStatus.COMPLETED || (session.status == SessionStatus.ACTIVE && session.isExpired) -> AppScreen.SESSION_COMPLETE
                !isAccessEnabled -> AppScreen.ACCESSIBILITY_SETUP
                current.currentScreen == AppScreen.ACCESSIBILITY_SETUP -> AppScreen.SELECT_APPS
                else -> current.currentScreen
            }

            current.copy(
                isAccessibilityEnabled = isAccessEnabled,
                isAdminActive = isAdminActive,
                canScheduleExactAlarms = canScheduleExact,
                session = session,
                targetPackageName = primaryPackage,
                targetAppName = primaryAppLabel,
                remainingSeconds = session.remainingSeconds,
                currentScreen = nextScreen
            )
        }

        if (session.status == SessionStatus.ACTIVE && !session.isExpired) {
            startCountdownLoop()
        } else {
            countdownJob?.cancel()
        }
    }

    /**
     * Loads launchable apps excluding self, launchers, and critical system apps.
     */
    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingApps = true) }
            val apps = appRepository.getLaunchableApps()
            val currentSelected = _uiState.value.selectedPackages

            val updatedApps = apps.map { app ->
                app.copy(isSelected = currentSelected.contains(app.packageName))
            }

            _uiState.update { current ->
                val query = current.searchQuery
                val filtered = if (query.isBlank()) {
                    updatedApps
                } else {
                    updatedApps.filter {
                        it.appName.contains(query, ignoreCase = true) ||
                                it.packageName.contains(query, ignoreCase = true)
                    }
                }
                current.copy(
                    installedApps = updatedApps,
                    filteredApps = filtered,
                    isLoadingApps = false
                )
            }
        }
    }

    /**
     * Updates the app search query and filters the list.
     */
    fun setSearchQuery(query: String) {
        _uiState.update { current ->
            val filtered = if (query.isBlank()) {
                current.installedApps
            } else {
                current.installedApps.filter {
                    it.appName.contains(query, ignoreCase = true) ||
                            it.packageName.contains(query, ignoreCase = true)
                }
            }
            current.copy(searchQuery = query, filteredApps = filtered)
        }
    }

    /**
     * Toggles selection state for a given package.
     */
    fun togglePackageSelection(packageName: String) {
        val currentSession = sessionManager.getCurrentSession()
        if (currentSession.status == SessionStatus.ACTIVE && currentSession.strictMode) {
            _uiState.update { it.copy(errorMessage = "Strict Mode is active: Blocked apps cannot be modified.") }
            return
        }

        _uiState.update { current ->
            val updatedSelection = current.selectedPackages.toMutableSet()
            if (updatedSelection.contains(packageName)) {
                updatedSelection.remove(packageName)
            } else {
                updatedSelection.add(packageName)
            }

            preferences.saveSelectedPackages(updatedSelection)

            val updatedApps = current.installedApps.map {
                if (it.packageName == packageName) it.copy(isSelected = updatedSelection.contains(packageName)) else it
            }
            val updatedFiltered = current.filteredApps.map {
                if (it.packageName == packageName) it.copy(isSelected = updatedSelection.contains(packageName)) else it
            }

            val firstSelected = updatedApps.firstOrNull { updatedSelection.contains(it.packageName) }

            current.copy(
                selectedPackages = updatedSelection,
                selectedApp = firstSelected,
                installedApps = updatedApps,
                filteredApps = updatedFiltered
            )
        }
    }

    /**
     * Selects a single app (for backward compatibility with single-app selector).
     */
    fun selectApp(app: InstalledApp) {
        togglePackageSelection(app.packageName)
        _uiState.update { it.copy(selectedApp = app) }
    }

    /**
     * Clears all selected apps.
     */
    fun clearSelection() {
        val currentSession = sessionManager.getCurrentSession()
        if (currentSession.status == SessionStatus.ACTIVE && currentSession.strictMode) {
            _uiState.update { it.copy(errorMessage = "Strict Mode is active: Blocked apps cannot be modified.") }
            return
        }

        preferences.saveSelectedPackages(emptySet())
        _uiState.update { current ->
            val updatedApps = current.installedApps.map { it.copy(isSelected = false) }
            val updatedFiltered = current.filteredApps.map { it.copy(isSelected = false) }
            current.copy(
                selectedPackages = emptySet(),
                selectedApp = null,
                installedApps = updatedApps,
                filteredApps = updatedFiltered
            )
        }
    }

    /**
     * Sets duration in seconds (supporting presets like 60s, 300s, 900s, 1500s, 3600s).
     */
    fun setDurationSeconds(seconds: Long) {
        val currentSession = sessionManager.getCurrentSession()
        if (currentSession.status == SessionStatus.ACTIVE && currentSession.strictMode) {
            _uiState.update { it.copy(errorMessage = "Strict Mode is active: Duration cannot be changed.") }
            return
        }
        _uiState.update {
            it.copy(
                selectedDurationSeconds = seconds,
                selectedDurationMinutes = (seconds / 60).toInt().coerceAtLeast(1)
            )
        }
    }

    /**
     * Toggles Strict Mode on/off in the configuration screen.
     */
    fun toggleStrictMode(enabled: Boolean) {
        val currentSession = sessionManager.getCurrentSession()
        if (currentSession.status == SessionStatus.ACTIVE && currentSession.strictMode) {
            _uiState.update { it.copy(errorMessage = "Strict Mode is active and cannot be modified.") }
            return
        }
        _uiState.update { it.copy(strictMode = enabled) }
    }

    /**
     * Starts the focus session using the domain FocusSessionManager.
     */
    fun startFocusSession() {
        val selected = _uiState.value.selectedPackages
        if (selected.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please select at least one application to block.") }
            return
        }

        val duration = _uiState.value.selectedDurationSeconds
        val strict = _uiState.value.strictMode
        val primaryApp = _uiState.value.installedApps.firstOrNull { selected.contains(it.packageName) }?.appName

        val result = sessionManager.startSession(
            packages = selected,
            durationSeconds = duration,
            strictMode = strict,
            primaryAppName = primaryApp
        )

        when (result) {
            is SessionActionResult.Success -> {
                val session = sessionManager.getCurrentSession()
                _uiState.update {
                    it.copy(
                        session = session,
                        targetPackageName = session.blockedPackages.firstOrNull() ?: "",
                        targetAppName = session.primaryAppLabel ?: "",
                        remainingSeconds = session.remainingSeconds,
                        currentScreen = AppScreen.ACTIVE_SESSION,
                        errorMessage = null
                    )
                }
                startCountdownLoop()
            }
            is SessionActionResult.Rejected -> {
                _uiState.update { it.copy(errorMessage = result.reason) }
            }
        }
    }

    /**
     * Cancels the active session (rejected if Strict Mode is active).
     */
    fun cancelFocusSession() {
        val result = sessionManager.cancelSession()
        when (result) {
            is SessionActionResult.Success -> {
                countdownJob?.cancel()
                _uiState.update {
                    it.copy(
                        session = FocusSession(status = SessionStatus.IDLE),
                        currentScreen = AppScreen.SELECT_APPS,
                        errorMessage = null
                    )
                }
            }
            is SessionActionResult.Rejected -> {
                _uiState.update { it.copy(errorMessage = result.reason) }
            }
        }
    }

    /**
     * Resets completed session back to IDLE so user can plan the next session.
     */
    fun acknowledgeCompletion() {
        sessionManager.acknowledgeCompletion()
        _uiState.update {
            it.copy(
                session = FocusSession(status = SessionStatus.IDLE),
                currentScreen = AppScreen.SELECT_APPS
            )
        }
    }

    fun navigateTo(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // --- Legacy compatibility methods for existing UI/tests ---
    fun checkInitialState() = refreshState()
    fun refreshDeviceOwnerStatus() = refreshState()
    fun selectDuration(seconds: Long) = setDurationSeconds(seconds)
    fun onSearchQueryChange(query: String) = setSearchQuery(query)
    fun startBlock() = startFocusSession()
    fun backToSelectApp() = navigateTo(AppScreen.SELECT_APPS)
    fun resetToSelectApp() = acknowledgeCompletion()
    fun proceedFromSetup() = navigateTo(AppScreen.SELECT_APPS)
    fun confirmAppSelection() = navigateTo(AppScreen.SESSION_SETUP)

    fun startLock(packageName: String, appName: String, durationSeconds: Long) {
        val result = sessionManager.startSession(
            packages = setOf(packageName),
            durationSeconds = durationSeconds,
            strictMode = _uiState.value.strictMode,
            primaryAppName = appName
        )
        if (result is SessionActionResult.Success) {
            refreshState()
        }
    }

    fun resetLockState() {
        cancelFocusSession()
    }

    /**
     * Intent to launch the system Settings page for exact alarms (Android 12+).
     */
    fun getExactAlarmSettingsIntent(): Intent? {
        return timerManager.getExactAlarmSettingsIntent()
    }

    private fun startCountdownLoop() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                val session = sessionManager.reconcileSessionState()
                val rem = session.remainingSeconds
                _uiState.update {
                    it.copy(
                        session = session,
                        remainingSeconds = rem
                    )
                }
                if (session.status != SessionStatus.ACTIVE || session.isExpired) {
                    _uiState.update { it.copy(currentScreen = AppScreen.SESSION_COMPLETE) }
                    break
                }
            }
        }
    }

    private fun observeExpirationEvents() {
        viewModelScope.launch {
            BlockExpirationReceiver.expirationEvents.collect {
                refreshState()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }
}
