package com.niqob.app.data.repository

import com.niqob.app.domain.model.AppEnforcementPolicy
import com.niqob.app.domain.repository.AppEnforcementPolicyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thread-safe, in-memory implementation of AppEnforcementPolicyRepository.
 * Does not require Supabase or network access.
 */
class LocalAppEnforcementPolicyRepository : AppEnforcementPolicyRepository {

    private val _policy = MutableStateFlow(AppEnforcementPolicy.DEFAULT)

    override fun getPolicy(): StateFlow<AppEnforcementPolicy> {
        return _policy.asStateFlow()
    }

    override fun updatePolicy(policy: AppEnforcementPolicy) {
        _policy.value = policy
    }

    override fun setEnforcementActive(active: Boolean) {
        _policy.value = _policy.value.copy(isEnforcementActive = active)
    }

    override fun addAllowedPackage(packageName: String) {
        val trimmed = packageName.trim()
        if (trimmed.isNotEmpty()) {
            _policy.value = _policy.value.copy(
                allowedPackageNames = _policy.value.allowedPackageNames + trimmed
            )
        }
    }

    override fun removeAllowedPackage(packageName: String) {
        val trimmed = packageName.trim()
        _policy.value = _policy.value.copy(
            allowedPackageNames = _policy.value.allowedPackageNames - trimmed
        )
    }

    override fun addBlockedPackage(packageName: String) {
        val trimmed = packageName.trim()
        if (trimmed.isNotEmpty()) {
            _policy.value = _policy.value.copy(
                blockedPackageNames = _policy.value.blockedPackageNames + trimmed
            )
        }
    }

    override fun removeBlockedPackage(packageName: String) {
        val trimmed = packageName.trim()
        _policy.value = _policy.value.copy(
            blockedPackageNames = _policy.value.blockedPackageNames - trimmed
        )
    }

    override fun clear() {
        _policy.value = AppEnforcementPolicy.DEFAULT
    }

    companion object {
        @Volatile
        private var instance: LocalAppEnforcementPolicyRepository? = null

        fun getInstance(): LocalAppEnforcementPolicyRepository {
            return instance ?: synchronized(this) {
                instance ?: LocalAppEnforcementPolicyRepository().also { instance = it }
            }
        }

        fun resetForTesting() {
            instance = null
        }
    }
}
