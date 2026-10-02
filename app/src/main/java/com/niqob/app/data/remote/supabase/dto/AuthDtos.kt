package com.niqob.app.data.remote.supabase.dto

import com.niqob.app.domain.model.User
import com.niqob.app.domain.model.UserRole

data class AuthUserDto(
    val id: String,
    val email: String,
    val role: String = "PARENT",
    val displayName: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): User {
        val userRole = if (role.equals("CHILD", ignoreCase = true)) {
            UserRole.CHILD
        } else {
            UserRole.PARENT
        }
        return User(
            id = id,
            email = email,
            role = userRole,
            displayName = displayName,
            createdAt = createdAt
        )
    }
}

data class AuthSessionDto(
    val accessToken: String,
    val tokenType: String = "bearer",
    val expiresIn: Long = 3600,
    val refreshToken: String? = null,
    val user: AuthUserDto
)

data class AuthResponseDto(
    val user: AuthUserDto,
    val session: AuthSessionDto?
)
