package com.niqob.app.domain.enforcement

/**
 * Pure domain authenticator interface for parental screen-time gate override.
 *
 * Decouples PIN authentication from the presentation layer and persistence mechanism.
 */
interface ParentOverrideAuthenticator {
    /**
     * Authenticates the entered parent PIN.
     *
     * @param pin The PIN entered by the user.
     * @return True if authentication succeeds, false otherwise.
     */
    suspend fun authenticate(pin: String): Boolean
}
