package com.niqob.app.domain.repository

import com.niqob.app.domain.model.ChildDeviceStatus
import com.niqob.app.domain.model.FeatureModule
import com.niqob.app.domain.model.UserMode
import kotlinx.coroutines.flow.Flow

interface NiqobRepository {
    fun getChildDeviceStatus(): Flow<ChildDeviceStatus>
    fun getUserMode(): Flow<UserMode>
    suspend fun setUserMode(mode: UserMode)
    fun getFeatureModules(): List<FeatureModule>
}
