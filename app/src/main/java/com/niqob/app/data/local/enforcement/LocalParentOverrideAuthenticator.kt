package com.niqob.app.data.local.enforcement

import com.niqob.app.domain.enforcement.ParentOverrideAuthenticator

/**
 * Local development implementation of [ParentOverrideAuthenticator].
 *
 * SAFETY & SECURITY NOTICE:
 * - This implementation uses a development-only default PIN ("1234") strictly for local verification.
 * - In production, this must be replaced with a cryptographic hash stored in Android Keystore /
 *   EncryptedSharedPreferences or validated via the parent's Supabase auth session.
 * - No real production secrets are stored in this file.
 */
class LocalParentOverrideAuthenticator(
    private val devPin: String = DEFAULT_DEV_PIN
) : ParentOverrideAuthenticator {

    override suspend fun authenticate(pin: String): Boolean {
        if (pin.isBlank()) return false
        return pin.trim() == devPin.trim()
    }

    companion object {
        const val DEFAULT_DEV_PIN = "1234"
    }
}
