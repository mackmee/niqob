package com.niqob.app.domain.enforcement

import com.niqob.app.domain.model.ForegroundAppInfo

/**
 * Domain interface for detecting the currently or most recently used foreground application.
 *
 * Implementations must remain isolated from domain logic and must never inspect
 * message contents, screen contents, notifications, or accessibility events.
 */
interface ForegroundAppDetector {

    /**
     * Retrieves the most recently active foreground application.
     *
     * @param excludeSelf If true, ignores NIQOB's own package name to determine
     *                    the external foreground application that was active prior to NIQOB.
     * @return [ForegroundAppInfo] containing package name and timestamp, or null if unavailable.
     */
    suspend fun getCurrentForegroundApp(excludeSelf: Boolean = false): ForegroundAppInfo?
}
