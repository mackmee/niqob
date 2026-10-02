package com.niqob.app.data.repository

import com.niqob.app.core.common.Result
import com.niqob.app.data.local.LocalDataSourcePlaceholder
import com.niqob.app.data.local.LocalPairingDataSource
import com.niqob.app.domain.model.ChildDeviceStatus
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.model.UserMode
import com.niqob.app.domain.repository.PairingRepository
import kotlinx.coroutines.flow.Flow

class PairingRepositoryImpl(
    private val localPairingDataSource: LocalPairingDataSource = LocalPairingDataSource.getInstance(),
    private val localGeneralDataSource: LocalDataSourcePlaceholder = LocalDataSourcePlaceholder.getInstance()
) : PairingRepository {

    override suspend fun createPairingCode(): Result<PairingSession> {
        val session = localPairingDataSource.createPairingCode()
        updateGeneralDeviceStatus(session)
        return Result.Success(session)
    }

    override suspend fun connectChild(pairingCode: String): Result<PairingSession> {
        val result = localPairingDataSource.connectChild(pairingCode)
        if (result is Result.Success) {
            updateGeneralDeviceStatus(result.data)
        }
        return result
    }

    override fun getPairingSession(): Flow<PairingSession?> {
        return localPairingDataSource.pairingSession
    }

    override suspend fun revokePairing(): Result<Unit> {
        val result = localPairingDataSource.revokePairing()
        if (result is Result.Success) {
            localGeneralDataSource?.updateChildStatus(
                ChildDeviceStatus(
                    isConnected = false,
                    deviceName = "Not connected",
                    screenTimeMinutes = 0,
                    allowedAppsCount = 0,
                    safetyAlertsCount = 0
                )
            )
        }
        return result
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
