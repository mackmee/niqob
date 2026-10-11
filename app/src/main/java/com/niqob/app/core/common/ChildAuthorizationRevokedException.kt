package com.niqob.app.core.common

/**
 * Typed exception representing permanent rejection of child device authorization by the server
 * (e.g., error_code = UNAUTHORIZED due to pairing revocation, expiration, or invalid credentials).
 *
 * Distinguishes permanent authorization failure from transient network/server failures to prevent
 * indefinite retry loops and trigger local pairing state invalidation.
 */
class ChildAuthorizationRevokedException(
    val errorCode: String = "UNAUTHORIZED",
    override val message: String = "Child device authorization was rejected or revoked by the server."
) : Exception(message)
