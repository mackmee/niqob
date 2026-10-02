package com.niqob.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.model.UserMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.random.Random

/**
 * Local mock pairing data source.
 * Provides in-memory reactive state synchronized with Android SharedPreferences when available.
 * Designed to be seamlessly replaced with a real cloud/backend API client in future phases.
 */
class LocalPairingDataSource(
    private val sharedPreferences: SharedPreferences? = null
) {
    companion object {
        const val TEN_MINUTES_MILLIS = 10 * 60 * 1000L

        private const val PREF_KEY_ROLE = "niqob_pref_user_role"
        private const val PREF_KEY_SESSION_ID = "niqob_pref_session_id"
        private const val PREF_KEY_PARENT_DEVICE_ID = "niqob_pref_parent_dev_id"
        private const val PREF_KEY_CHILD_DEVICE_ID = "niqob_pref_child_dev_id"
        private const val PREF_KEY_PAIRING_CODE = "niqob_pref_pairing_code"
        private const val PREF_KEY_CREATED_AT = "niqob_pref_created_at"
        private const val PREF_KEY_EXPIRES_AT = "niqob_pref_expires_at"
        private const val PREF_KEY_STATUS = "niqob_pref_status"

        // Default shared singleton instance for local mock demonstrations
        @Volatile
        private var instance: LocalPairingDataSource? = null

        fun getInstance(context: Context? = null): LocalPairingDataSource {
            return instance ?: synchronized(this) {
                instance ?: LocalPairingDataSource(
                    context?.applicationContext?.getSharedPreferences("niqob_pairing_prefs", Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }

        fun resetInstanceForTests() {
            instance = null
        }
    }

    private val localParentDeviceId: String = generateDeviceId("parent")
    private val localChildDeviceId: String = generateDeviceId("child")

    private val _currentRole = MutableStateFlow(restoreRole())
    val currentRole: Flow<UserMode> = _currentRole.asStateFlow()

    private val _pairingSession = MutableStateFlow<PairingSession?>(restoreSession())
    val pairingSession: Flow<PairingSession?> = _pairingSession.asStateFlow()

    fun setCurrentRole(role: UserMode) {
        _currentRole.value = role
        sharedPreferences?.edit()?.putString(PREF_KEY_ROLE, role.name)?.apply()
    }

    fun getCurrentRoleValue(): UserMode = _currentRole.value

    fun createPairingCode(currentTimeMillis: Long = System.currentTimeMillis()): PairingSession {
        // Generate a 6-digit numeric pairing code
        val numericCode = String.format("%06d", Random.nextInt(0, 1_000_000))
        val session = PairingSession(
            pairingId = UUID.randomUUID().toString(),
            parentDeviceId = localParentDeviceId,
            childDeviceId = null,
            pairingCode = numericCode,
            createdAt = currentTimeMillis,
            expiresAt = currentTimeMillis + TEN_MINUTES_MILLIS,
            status = PairingStatus.PENDING
        )

        _pairingSession.value = session
        persistSession(session)
        return session
    }

    fun connectChild(
        code: String,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): Result<PairingSession> {
        val currentSession = _pairingSession.value
            ?: return Result.Error(IllegalArgumentException("Invalid pairing code."))

        val trimmedCode = code.trim()

        if (currentSession.pairingCode != trimmedCode) {
            return Result.Error(IllegalArgumentException("Invalid pairing code."))
        }

        if (currentSession.isExpired(currentTimeMillis)) {
            val expiredSession = currentSession.copy(status = PairingStatus.EXPIRED)
            _pairingSession.value = expiredSession
            persistSession(expiredSession)
            return Result.Error(IllegalStateException("This pairing code has expired."))
        }

        if (currentSession.status == PairingStatus.REVOKED) {
            return Result.Error(IllegalStateException("This pairing code has been revoked."))
        }

        if (currentSession.status == PairingStatus.CONNECTED) {
            return Result.Success(currentSession)
        }

        val connectedSession = currentSession.copy(
            childDeviceId = localChildDeviceId,
            status = PairingStatus.CONNECTED
        )
        _pairingSession.value = connectedSession
        persistSession(connectedSession)
        return Result.Success(connectedSession)
    }

    fun revokePairing(): Result<Unit> {
        val currentSession = _pairingSession.value
        if (currentSession != null) {
            val revokedSession = currentSession.copy(status = PairingStatus.REVOKED)
            _pairingSession.value = revokedSession
            persistSession(revokedSession)
        }
        return Result.Success(Unit)
    }

    private fun generateDeviceId(prefix: String): String {
        return "${prefix}_dev_${Random.nextInt(1000, 9999)}"
    }

    private fun persistSession(session: PairingSession) {
        sharedPreferences?.edit()?.apply {
            putString(PREF_KEY_SESSION_ID, session.pairingId)
            putString(PREF_KEY_PARENT_DEVICE_ID, session.parentDeviceId)
            putString(PREF_KEY_CHILD_DEVICE_ID, session.childDeviceId)
            putString(PREF_KEY_PAIRING_CODE, session.pairingCode)
            putLong(PREF_KEY_CREATED_AT, session.createdAt)
            putLong(PREF_KEY_EXPIRES_AT, session.expiresAt)
            putString(PREF_KEY_STATUS, session.status.name)
            apply()
        }
    }

    private fun restoreSession(): PairingSession? {
        val prefs = sharedPreferences ?: return null
        val id = prefs.getString(PREF_KEY_SESSION_ID, null) ?: return null
        val parentId = prefs.getString(PREF_KEY_PARENT_DEVICE_ID, "parent_dev_0000") ?: "parent_dev_0000"
        val childId = prefs.getString(PREF_KEY_CHILD_DEVICE_ID, null)
        val code = prefs.getString(PREF_KEY_PAIRING_CODE, null) ?: return null
        val createdAt = prefs.getLong(PREF_KEY_CREATED_AT, 0L)
        val expiresAt = prefs.getLong(PREF_KEY_EXPIRES_AT, 0L)
        val statusStr = prefs.getString(PREF_KEY_STATUS, PairingStatus.PENDING.name) ?: PairingStatus.PENDING.name
        val status = try {
            PairingStatus.valueOf(statusStr)
        } catch (_: Exception) {
            PairingStatus.PENDING
        }

        return PairingSession(
            pairingId = id,
            parentDeviceId = parentId,
            childDeviceId = childId,
            pairingCode = code,
            createdAt = createdAt,
            expiresAt = expiresAt,
            status = status
        )
    }

    private fun restoreRole(): UserMode {
        val roleStr = sharedPreferences?.getString(PREF_KEY_ROLE, null) ?: return UserMode.UNSELECTED
        return try {
            UserMode.valueOf(roleStr)
        } catch (_: Exception) {
            UserMode.UNSELECTED
        }
    }
}
