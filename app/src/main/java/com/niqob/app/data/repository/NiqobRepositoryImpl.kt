package com.niqob.app.data.repository

import com.niqob.app.data.local.LocalDataSourcePlaceholder
import com.niqob.app.data.remote.RemoteDataSourcePlaceholder
import com.niqob.app.domain.model.ChildDeviceStatus
import com.niqob.app.domain.model.FeatureModule
import com.niqob.app.domain.model.ModuleType
import com.niqob.app.domain.model.UserMode
import com.niqob.app.domain.repository.NiqobRepository
import kotlinx.coroutines.flow.Flow

class NiqobRepositoryImpl(
    private val localDataSource: LocalDataSourcePlaceholder = LocalDataSourcePlaceholder.getInstance(),
    private val remoteDataSource: RemoteDataSourcePlaceholder = RemoteDataSourcePlaceholder()
) : NiqobRepository {

    override fun getChildDeviceStatus(): Flow<ChildDeviceStatus> {
        return localDataSource.childDeviceStatus
    }

    override fun getUserMode(): Flow<UserMode> {
        return localDataSource.userMode
    }

    override fun getFeatureModules(): List<FeatureModule> {
        return listOf(
            FeatureModule(
                type = ModuleType.CHILD_MODE,
                title = "Child Mode",
                description = "Safe digital sandbox for children with restricted surface and curated apps."
            ),
            FeatureModule(
                type = ModuleType.PARENT_MODE,
                title = "Parent Mode",
                description = "Parental oversight dashboard with real-time controls and metrics."
            ),
            FeatureModule(
                type = ModuleType.DEVICE_PAIRING,
                title = "Parent-Child Device Pairing",
                description = "Secure peer-to-peer or cloud device connection via QR code or pairing key."
            ),
            FeatureModule(
                type = ModuleType.APP_PERMISSIONS,
                title = "App Permission Management",
                description = "Granular control over which applications are visible, blocked, or limited."
            ),
            FeatureModule(
                type = ModuleType.SCREEN_TIME,
                title = "Screen Time Management",
                description = "Daily limits, scheduled downtimes, and usage budgets by category."
            ),
            FeatureModule(
                type = ModuleType.PARENT_AUTHENTICATION,
                title = "Parent Authentication",
                description = "PIN and biometric protection preventing unauthorized bypass."
            ),
            FeatureModule(
                type = ModuleType.SAFETY_MONITORING,
                title = "Safety Monitoring",
                description = "Non-intrusive safety signals, emergency SOS, and environment health checks."
            ),
            FeatureModule(
                type = ModuleType.AI_TUTOR,
                title = "NIQOB AI Tutor",
                description = "Safe, age-tailored conversational learning assistant for children."
            ),
            FeatureModule(
                type = ModuleType.AGE_BASED_PERMISSIONS,
                title = "Age-Based Permissions",
                description = "Adaptive permission presets that evolve as the child matures."
            ),
            FeatureModule(
                type = ModuleType.SETTINGS,
                title = "Settings",
                description = "Global system preferences, account details, and diagnostic tools."
            )
        )
    }

    override suspend fun setUserMode(mode: UserMode) {
        localDataSource.setUserMode(mode)
    }
}
