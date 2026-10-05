package com.niqob.app.domain.enforcement

import com.niqob.app.domain.model.ParentOverrideSession

/**
 * Storage abstraction for persisting and restoring active [ParentOverrideSession]s.
 *
 * Requirements:
 * - Persists session metadata across process death, app restart, and device reboot.
 * - Does NOT store credentials or PINs.
 * - Authoritatively validates expiration on load: expired sessions are never returned as active.
 */
interface ParentOverrideSessionStore {

    /**
     * Saves the active override session.
     */
    fun save(session: ParentOverrideSession)

    /**
     * Loads the stored session if present.
     * If the session is expired relative to [currentTimeMillis], it must be treated as inactive.
     */
    fun load(currentTimeMillis: Long = System.currentTimeMillis()): ParentOverrideSession?

    /**
     * Clears any stored override session.
     */
    fun clear()
}
