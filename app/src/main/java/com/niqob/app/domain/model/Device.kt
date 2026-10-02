package com.niqob.app.domain.model

data class Device(
    val id: String,
    val familyId: String,
    val userId: String? = null,
    val deviceName: String,
    val platform: String = "ANDROID",
    val deviceType: String = "CHILD_PHONE",
    val lastSeenAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
