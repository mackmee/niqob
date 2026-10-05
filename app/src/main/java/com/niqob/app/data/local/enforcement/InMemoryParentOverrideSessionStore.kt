package com.niqob.app.data.local.enforcement

import com.niqob.app.domain.enforcement.ParentOverrideSessionStore
import com.niqob.app.domain.model.ParentOverrideSession

/**
 * Thread-safe in-memory implementation of [ParentOverrideSessionStore].
 * Useful for unit testing and fallback scenarios where Android Context is not available.
 */
class InMemoryParentOverrideSessionStore(
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : ParentOverrideSessionStore {

    @Volatile
    private var storedSession: ParentOverrideSession? = null

    @Synchronized
    override fun save(session: ParentOverrideSession) {
        storedSession = session
    }

    @Synchronized
    override fun load(currentTimeMillis: Long): ParentOverrideSession? {
        val session = storedSession ?: return null
        if (session.isExpired(currentTimeMillis)) {
            storedSession = null
            return null
        }
        return session
    }

    @Synchronized
    override fun clear() {
        storedSession = null
    }
}
