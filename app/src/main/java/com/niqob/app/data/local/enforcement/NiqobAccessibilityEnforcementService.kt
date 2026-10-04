package com.niqob.app.data.local.enforcement

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.niqob.app.MainActivity
import com.niqob.app.data.repository.LocalAppEnforcementPolicyRepository
import com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository
import com.niqob.app.domain.enforcement.AppEnforcementController
import com.niqob.app.domain.enforcement.AppEnforcementEvaluator
import com.niqob.app.domain.enforcement.EnforcementActionPerformer
import com.niqob.app.domain.enforcement.EnforcementDebouncer
import com.niqob.app.domain.model.AppEnforcementAction
import com.niqob.app.domain.model.EnforcementActionResult
import com.niqob.app.domain.model.EnforcementExecutionResult
import com.niqob.app.domain.repository.AppEnforcementPolicyRepository
import com.niqob.app.domain.repository.ScreenTimeEnforcementRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android AccessibilityService implementation for BYOD foreground application enforcement.
 *
 * PHASE 4E CONTROLLED PROTOTYPE:
 * - Detects foreground package via [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED].
 * - When an unpermitted application becomes foreground during active screen-time enforcement:
 *     1. Dispatches `GLOBAL_ACTION_HOME` to move the unallowed app to background.
 *     2. Opens NIQOB's ScreenTimeLimitReachedScreen.
 * - Monotonic debounce mechanism prevents repeated enforcement or rapid relaunch loops.
 * - NIQOB self-application and the default system Home launcher are explicitly exempt.
 *
 * STRICT SAFETY & PRIVACY SAFEGUARDS:
 * - canRetrieveWindowContent="false" is enforced in XML configuration.
 * - Does NOT inspect accessibility node trees or call getRootInActiveWindow().
 * - Does NOT read screen text, passwords, or messages.
 * - Does NOT call killProcess, forceStopPackage, or use SYSTEM_ALERT_WINDOW overlays.
 */
class NiqobAccessibilityEnforcementService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var actionPerformer: EnforcementActionPerformer = DefaultActionPerformer()
    var debouncer: EnforcementDebouncer = EnforcementDebouncer(
        cooldownDurationMillis = 1500L,
        timeProvider = { SystemClock.elapsedRealtime() }
    )

    var enforcementRepository: ScreenTimeEnforcementRepository = LocalScreenTimeEnforcementRepository.getInstance()
    var policyRepository: AppEnforcementPolicyRepository = LocalAppEnforcementPolicyRepository.getInstance()

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isServiceActive.value = true
        Log.i(TAG, "NiqobAccessibilityEnforcementService connected (BYOD Bridge)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString()?.trim() ?: return
        if (packageName.isBlank()) return

        processPackageEvent(packageName)
    }

    fun processPackageEvent(packageName: String): EnforcementExecutionResult? {
        // 1. Ignore NIQOB package itself
        val selfPkg = try { applicationContext.packageName } catch (_: Throwable) { "com.niqob.app" }
        if (packageName == selfPkg || packageName == "com.niqob.app") {
            return null
        }

        // 2. Ignore Home Launcher and SystemUI to prevent loops
        if (isSystemNavigationOrHome(packageName)) {
            return null
        }

        // 3. Record latest detected package
        _latestDetectedPackage.value = packageName

        // 4. Read current enforcement state and policy
        val enforcementState = enforcementRepository.getEnforcementState().value
        val policy = policyRepository.getPolicy().value

        // 5. Evaluate package using domain evaluator
        val decision = AppEnforcementEvaluator.evaluate(
            packageName = packageName,
            enforcementState = enforcementState,
            policy = policy
        )

        return if (decision.decision == AppEnforcementAction.ALLOW) {
            val result = EnforcementExecutionResult(
                packageName = packageName,
                actionResult = EnforcementActionResult.PREVIEW_ALLOW,
                decision = decision,
                mechanismName = "AccessibilityService (BYOD)",
                message = "Allowed: '${decision.packageName}' is permitted.",
                isRealBlockExecuted = false,
                timestampMillis = decision.timestampMillis
            )
            _latestExecutionResult.value = result
            result
        } else {
            // Target is BLOCK: Check debounce
            if (!debouncer.shouldEnforce(packageName)) {
                Log.d(TAG, "Enforcement for '$packageName' debounced (cooldown active)")
                return null
            }

            Log.i(TAG, "Enforcing block on '$packageName': sending HOME and opening NIQOB limit gate")

            // Execute controlled block action:
            // 1. Send HOME
            actionPerformer.sendHome()

            // 2. Open NIQOB limit screen
            serviceScope.launch {
                delay(200) // Small delay allows HOME transition to complete before launching NIQOB
                actionPerformer.launchLimitScreen(packageName)
            }

            val result = EnforcementExecutionResult(
                packageName = packageName,
                actionResult = EnforcementActionResult.ENFORCED_HOME_REDIRECT,
                decision = decision,
                mechanismName = "AccessibilityService (BYOD)",
                message = "Blocked: Redirected to Home & opened ScreenTimeLimitReachedScreen.",
                isRealBlockExecuted = true,
                timestampMillis = decision.timestampMillis
            )
            _latestExecutionResult.value = result
            result
        }
    }

    private fun isSystemNavigationOrHome(packageName: String): Boolean {
        if (packageName == "com.android.systemui") return true
        return try {
            val pm = packageManager ?: return false
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            val launcherPkg = resolveInfo?.activityInfo?.packageName
            packageName == launcherPkg
        } catch (_: Throwable) {
            false
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "NiqobAccessibilityEnforcementService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceActive.value = false
        serviceScope.cancel()
        Log.i(TAG, "NiqobAccessibilityEnforcementService destroyed")
    }

    private inner class DefaultActionPerformer : EnforcementActionPerformer {
        override fun sendHome(): Boolean {
            return try {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform GLOBAL_ACTION_HOME", e)
                false
            }
        }

        override fun launchLimitScreen(targetPackageName: String): Boolean {
            return try {
                val intent = Intent(this@NiqobAccessibilityEnforcementService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(MainActivity.EXTRA_NAVIGATE_TO_LIMIT_GATE, true)
                    putExtra(MainActivity.EXTRA_BLOCKED_TARGET_PACKAGE, targetPackageName)
                }
                startActivity(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch limit screen activity", e)
                false
            }
        }
    }

    companion object {
        private const val TAG = "NiqobAccessService"

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _latestDetectedPackage = MutableStateFlow<String?>(null)
        val latestDetectedPackage: StateFlow<String?> = _latestDetectedPackage.asStateFlow()

        private val _latestExecutionResult = MutableStateFlow<EnforcementExecutionResult?>(null)
        val latestExecutionResult: StateFlow<EnforcementExecutionResult?> = _latestExecutionResult.asStateFlow()

        var activeController: AppEnforcementController? = null

        fun setServiceActiveForTesting(active: Boolean) {
            _isServiceActive.value = active
        }

        fun resetForTesting() {
            _isServiceActive.value = false
            _latestDetectedPackage.value = null
            _latestExecutionResult.value = null
            activeController = null
        }
    }
}
