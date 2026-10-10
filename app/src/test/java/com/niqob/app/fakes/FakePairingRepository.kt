package com.niqob.app.fakes

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.model.UserMode
import com.niqob.app.domain.repository.PairingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class FakePairingRepository : PairingRepository {

    private val _pairingSession = MutableStateFlow<PairingSession?>(null)
    private val _currentRole = MutableStateFlow(UserMode.UNSELECTED)

    var currentMockTimeMillis: Long = System.currentTimeMillis()

    override suspend fun createPairingCode(): Result<PairingSession> {
        val session = PairingSession(
            pairingId = UUID.randomUUID().toString(),
            parentDeviceId = "parent_mock_dev",
            childDeviceId = null,
            pairingCode = "123456",
            createdAt = currentMockTimeMillis,
            expiresAt = currentMockTimeMillis + 10 * 60 * 1000L,
            status = PairingStatus.PENDING,
            familyId = "fam_mock_123"
        )
        _pairingSession.value = session
        return Result.Success(session)
    }

    override suspend fun connectChild(pairingCode: String): Result<PairingSession> {
        val trimmed = pairingCode.trim()
        if (trimmed.length != 6 || !trimmed.all { it.isDigit() }) {
            return Result.Error(IllegalArgumentException("Pairing code must be a 6-digit number."))
        }

        val session = _pairingSession.value
            ?: return Result.Error(Exception("Invalid pairing code. Please verify the code shown on the parent device."))

        if (session.pairingCode != trimmed) {
            return Result.Error(Exception("Invalid pairing code. Please verify the code shown on the parent device."))
        }

        if (session.status == PairingStatus.REVOKED) {
            return Result.Error(Exception("This pairing code has been revoked by the parent."))
        }

        if (session.status == PairingStatus.CONNECTED) {
            return Result.Error(Exception("This pairing code has already been used."))
        }

        if (session.isExpired(currentMockTimeMillis) || session.status == PairingStatus.EXPIRED) {
            _pairingSession.value = session.copy(status = PairingStatus.EXPIRED)
            return Result.Error(Exception("This pairing code has expired. Please generate a new code on the parent device."))
        }

        val connected = session.copy(
            status = PairingStatus.CONNECTED,
            childDeviceId = "child_dev_${UUID.randomUUID().toString().take(6)}",
            connectedAt = currentMockTimeMillis
        )
        _pairingSession.value = connected
        return Result.Success(connected)
    }

    override fun getPairingSession(): Flow<PairingSession?> {
        return _pairingSession.asStateFlow()
    }

    override suspend fun revokePairing(): Result<Unit> {
        val session = _pairingSession.value
        if (session != null) {
            _pairingSession.value = session.copy(
                status = PairingStatus.REVOKED,
                revokedAt = currentMockTimeMillis
            )
        }
        return Result.Success(Unit)
    }

    override fun getCurrentRole(): Flow<UserMode> {
        return _currentRole.asStateFlow()
    }

    override suspend fun setCurrentRole(role: UserMode) {
        _currentRole.value = role
    }

    var fakeChildDeviceId: String? = null
    var fakeChildDeviceSecret: String? = null

    override fun getChildDeviceId(): String? {
        return fakeChildDeviceId ?: _pairingSession.value?.childDeviceId
    }

    override fun getChildDeviceSecret(): String? {
        return fakeChildDeviceSecret
    }

    fun setSessionDirectly(session: PairingSession?) {
        _pairingSession.value = session
    }
}
