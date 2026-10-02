package com.niqob.app.domain.model

data class PairingSession(
    val pairingId: String,
    val parentDeviceId: String,
    val childDeviceId: String? = null,
    val pairingCode: String,
    val createdAt: Long,
    val expiresAt: Long,
    val status: PairingStatus,
    val familyId: String? = null,
    val connectedAt: Long? = null,
    val revokedAt: Long? = null
) {

    fun isExpired(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return currentTimeMillis > expiresAt
    }

    fun isConnectable(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return status == PairingStatus.PENDING && !isExpired(currentTimeMillis)
    }
}
