package com.niqob.app.domain.enforcement

import com.niqob.app.domain.model.EnforcementExecutionResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Pure domain interface for coordinating app enforcement lifecycle and package handling.
 *
 * All platform-specific enforcement mechanisms (AccessibilityService, DevicePolicyManager, etc.)
 * are isolated behind this interface.
 *
 * Implementations must never kill processes, show unauthorized overlays, or lock the user out.
 */
interface AppEnforcementController {

    /**
     * Observable state indicating whether the enforcement controller is actively running.
     */
    val isRunning: StateFlow<Boolean>

    /**
     * Starts monitoring/evaluating application enforcement.
     */
    suspend fun start()

    /**
     * Stops monitoring/evaluating application enforcement.
     */
    suspend fun stop()

    /**
     * Evaluates and handles a detected foreground package.
     *
     * @param packageName The package name entering the foreground.
     * @return [EnforcementExecutionResult] indicating preview or enforcement status.
     */
    suspend fun handleForegroundPackage(packageName: String): EnforcementExecutionResult
}
