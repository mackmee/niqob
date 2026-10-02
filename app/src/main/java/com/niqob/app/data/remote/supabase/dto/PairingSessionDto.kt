package com.niqob.app.data.remote.supabase.dto

import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.PairingStatus

data class PairingSessionDto(
    val id: String,
    val familyId: String,
    val parentDeviceId: String,
    val childDeviceId: String? = null,
    val pairingCode: String,
    val status: String = "PENDING",
    val expiresAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val connectedAt: Long? = null,
    val revokedAt: Long? = null
) {
    fun toDomain(): PairingSession {
        val parsedStatus = try {
            PairingStatus.valueOf(status.uppercase())
        } catch (_: Exception) {
            PairingStatus.PENDING
        }
        return PairingSession(
            pairingId = id,
            parentDeviceId = parentDeviceId,
            childDeviceId = childDeviceId,
            pairingCode = pairingCode,
            createdAt = createdAt,
            expiresAt = expiresAt,
            status = parsedStatus,
            familyId = familyId,
            connectedAt = connectedAt,
            revokedAt = revokedAt
        )
    }
}
