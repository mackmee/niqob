package com.niqob.app.domain.model

data class User(
    val id: String,
    val email: String,
    val role: UserRole,
    val displayName: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
