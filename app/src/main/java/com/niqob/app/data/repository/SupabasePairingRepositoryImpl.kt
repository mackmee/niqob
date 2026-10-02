package com.niqob.app.data.repository

import com.niqob.app.core.common.Result
import com.niqob.app.data.local.LocalDataSourcePlaceholder
import com.niqob.app.data.local.LocalPairingDataSource
import com.niqob.app.data.remote.supabase.SupabaseConfig
import com.niqob.app.data.remote.supabase.SupabaseRemoteDataSource
import com.niqob.app.domain.model.ChildDeviceStatus
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.model.UserMode
import com.niqob.app.domain.repository.PairingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Production-ready PairingRepository implementation integrating with Supabase Cloud
 * with graceful fallback to local simulation when Supabase is not configured.
 */
class SupabasePairingRepositoryImpl(
    private val remoteDataSource: SupabaseRemoteDataSource = SupabaseRemoteDataSource(),
    private val authRepository: AuthRepositoryImpl = AuthRepositoryImpl.getInstance(),
    private val localPairingDataSource: LocalPairingDataSource = LocalPairingDataSource.getInstance(),
    private val localGeneralDataSource: LocalDataSourcePlaceholder? = LocalDataSourcePlaceholder.getInstance()
) : PairingRepository {

    private val localParentDeviceId: String = "parent_dev_${UUID.randomUUID().toString().take(8)}"
    private val _pairingSession = MutableStateFlow<PairingSession?>(null)

    init {
        // Initialize state from local data source if available
        _pairingSession.value = localPairingDataSource.createPairingCode().copy(status = PairingStatus.PENDING)
    }

    override suspend fun createPairingCode(): Result<PairingSession> {
        if (SupabaseConfig.isConfigured) {
            val token = authRepository.getAuthToken()
            if (token.isNullOrBlank()) {
                return Result.Error(IllegalStateException("Parent must be logged in to create a pairing code."))
            }

            return when (val result = remoteDataSource.createPairingSession(localParentDeviceId, token)) {
                is Result.Success -> {
                    val dto = result.data
                    val session = PairingSession(
                        pairingId = dto.sessionId ?: UUID.randomUUID().toString(),
                        parentDeviceId = dto.parentDeviceId ?: localParentDeviceId,
                        childDeviceId = null,
                        pairingCode = dto.pairingCode ?: "",
                        createdAt = System.currentTimeMillis(),
                        expiresAt = dto.expiresAt ?: (System.currentTimeMillis() + 10 * 60 * 1000L),
                        status = PairingStatus.PENDING,
                        familyId = dto.familyId
                    )
                    _pairingSession.value = session
                    updateGeneralDeviceStatus(session)
                    Result.Success(session)
                }
                is Result.Error -> {
                    Result.Error(result.exception)
                }
                is Result.Loading -> Result.Loading
            }
        } else {
            // Local fallback
            val session = localPairingDataSource.createPairingCode()
            _pairingSession.value = session
            updateGeneralDeviceStatus(session)
            return Result.Success(session)
        }
    }

    override suspend fun connectChild(pairingCode: String): Result<PairingSession> {
        val trimmedCode = pairingCode.trim()
        if (trimmedCode.length != 6 || !trimmedCode.all { it.isDigit() }) {
            return Result.Error(IllegalArgumentException("Pairing code must be a 6-digit number."))
        }

        if (SupabaseConfig.isConfigured) {
            return when (val result = remoteDataSource.pairChildDevice(trimmedCode, "Child Android Device")) {
                is Result.Success -> {
                    val dto = result.data
                    val session = PairingSession(
                        pairingId = dto.sessionId ?: UUID.randomUUID().toString(),
                        parentDeviceId = "parent_cloud",
                        childDeviceId = dto.deviceId ?: "child_dev_connected",
                        pairingCode = trimmedCode,
                        createdAt = System.currentTimeMillis(),
                        expiresAt = System.currentTimeMillis() + 10 * 60 * 1000L,
                        status = PairingStatus.CONNECTED,
                        familyId = dto.familyId,
                        connectedAt = System.currentTimeMillis()
                    )
                    _pairingSession.value = session
                    updateGeneralDeviceStatus(session)
                    Result.Success(session)
                }
                is Result.Error -> {
                    Result.Error(result.exception)
                }
                is Result.Loading -> Result.Loading
            }
        } else {
            // Local fallback
            val localResult = localPairingDataSource.connectChild(trimmedCode)
            if (localResult is Result.Success) {
                _pairingSession.value = localResult.data
                updateGeneralDeviceStatus(localResult.data)
            }
            return localResult
        }
    }


    override fun getPairingSession(): Flow<PairingSession?> {
        return _pairingSession.asStateFlow()
    }

    override suspend fun revokePairing(): Result<Unit> {
        val currentSession = _pairingSession.value

        if (SupabaseConfig.isConfigured && currentSession != null) {
            val token = authRepository.getAuthToken()
            if (!token.isNullOrBlank()) {
                remoteDataSource.revokePairingSession(currentSession.pairingId, token)
            }
        }

        if (currentSession != null) {
            val revoked = currentSession.copy(status = PairingStatus.REVOKED, revokedAt = System.currentTimeMillis())
            _pairingSession.value = revoked
        }

        localPairingDataSource.revokePairing()

        localGeneralDataSource?.updateChildStatus(
            ChildDeviceStatus(
                isConnected = false,
                deviceName = "Not connected",
                screenTimeMinutes = 0,
                allowedAppsCount = 0,
                safetyAlertsCount = 0
            )
        )

        return Result.Success(Unit)
    }

    override fun getCurrentRole(): Flow<UserMode> {
        return localPairingDataSource.currentRole
    }

    override suspend fun setCurrentRole(role: UserMode) {
        localPairingDataSource.setCurrentRole(role)
        localGeneralDataSource?.setUserMode(role)
    }

    private fun updateGeneralDeviceStatus(session: PairingSession) {
        val isConnected = session.status == PairingStatus.CONNECTED
        val deviceName = if (isConnected) {
            session.childDeviceId ?: "Child Device (Connected)"
        } else {
            "Not connected"
        }

        localGeneralDataSource?.updateChildStatus(
            ChildDeviceStatus(
                isConnected = isConnected,
                deviceName = deviceName,
                screenTimeMinutes = 0,
                allowedAppsCount = 0,
                safetyAlertsCount = 0
            )
        )
    }
}
