package com.niqob.app.domain.repository

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.UserMode
import kotlinx.coroutines.flow.Flow

interface PairingRepository {
    suspend fun createPairingCode(): Result<PairingSession>
    suspend fun connectChild(pairingCode: String): Result<PairingSession>
    fun getPairingSession(): Flow<PairingSession?>
    suspend fun revokePairing(): Result<Unit>
    fun getCurrentRole(): Flow<UserMode>
    suspend fun setCurrentRole(role: UserMode)
    suspend fun syncPairingSession(): Result<PairingSession?> = Result.Success(null)
    suspend fun clearParentSession(): Result<Unit> = Result.Success(Unit)
    fun getChildDeviceId(): String? = null
    fun getChildDeviceSecret(): String? = null
}
