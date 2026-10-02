package com.niqob.app.domain.model

data class Family(
    val id: String,
    val parentId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
