package com.niqob.app.data.remote.supabase.dto

import com.niqob.app.domain.model.Device

data class DeviceDto(
    val id: String,
    val familyId: String,
    val userId: String? = null,
    val deviceName: String,
    val platform: String = "ANDROID",
    val deviceType: String = "CHILD_PHONE",
    val lastSeenAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Device {
        return Device(
            id = id,
            familyId = familyId,
            userId = userId,
            deviceName = deviceName,
            platform = platform,
            deviceType = deviceType,
            lastSeenAt = lastSeenAt,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
