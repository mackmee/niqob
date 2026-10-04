package com.niqob.app.domain.enforcement

/**
 * Interface abstracting execution of controlled platform enforcement actions.
 *
 * Allows pure JVM testing without calling real Android WindowManager or Accessibility APIs.
 */
interface EnforcementActionPerformer {
    /**
     * Executes navigation to Home.
     *
     * @return True if dispatch succeeded.
     */
    fun sendHome(): Boolean

    /**
     * Launches the NIQOB ScreenTimeLimitReachedScreen.
     *
     * @param targetPackageName The blocked package that triggered the action.
     * @return True if launch intent was dispatched.
     */
    fun launchLimitScreen(targetPackageName: String): Boolean
}
