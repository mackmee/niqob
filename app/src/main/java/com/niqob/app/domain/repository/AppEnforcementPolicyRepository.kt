package com.niqob.app.domain.repository

import com.niqob.app.domain.model.AppEnforcementPolicy
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing application enforcement policies.
 * Decouples policy storage from evaluation logic.
 */
interface AppEnforcementPolicyRepository {

    /**
     * Observes the current application enforcement policy.
     */
    fun getPolicy(): StateFlow<AppEnforcementPolicy>

    /**
     * Updates the full application enforcement policy.
     */
    fun updatePolicy(policy: AppEnforcementPolicy)

    /**
     * Updates whether enforcement is active in the policy.
     */
    fun setEnforcementActive(active: Boolean)

    /**
     * Adds an allowed package name to the policy.
     */
    fun addAllowedPackage(packageName: String)

    /**
     * Removes an allowed package name from the policy.
     */
    fun removeAllowedPackage(packageName: String)

    /**
     * Adds an explicitly blocked package name to the policy.
     */
    fun addBlockedPackage(packageName: String)

    /**
     * Removes an explicitly blocked package name from the policy.
     */
    fun removeBlockedPackage(packageName: String)

    /**
     * Resets policy to safe defaults.
     */
    fun clear()
}
