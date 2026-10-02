package com.niqob.app

import com.niqob.app.data.repository.NiqobRepositoryImpl
import com.niqob.app.domain.usecase.GetChildDeviceStatusUseCase
import com.niqob.app.domain.usecase.GetFeatureModulesUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.niqob.app.data.local.LocalDataSourcePlaceholder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class NiqobRepositoryTest {

    @Before
    fun setup() {
        LocalDataSourcePlaceholder.resetInstanceForTests()
    }

    @After
    fun tearDown() {
        LocalDataSourcePlaceholder.resetInstanceForTests()
    }

    @Test

    fun defaultChildStatus_matchesInitialSpecification() = runBlocking {
        val repository = NiqobRepositoryImpl()
        val useCase = GetChildDeviceStatusUseCase(repository)

        val status = useCase().first()

        assertFalse(status.isConnected)
        assertEquals("Not connected", status.deviceName)
        assertEquals(0, status.screenTimeMinutes)
        assertEquals(0, status.allowedAppsCount)
        assertEquals(0, status.safetyAlertsCount)
    }

    @Test
    fun featureModules_containsTenCoreModules() {
        val repository = NiqobRepositoryImpl()
        val useCase = GetFeatureModulesUseCase(repository)

        val modules = useCase()

        assertEquals(10, modules.size)
        assertNotNull(modules.find { it.title == "Child Mode" })
        assertNotNull(modules.find { it.title == "Parent Mode" })
        assertNotNull(modules.find { it.title == "Parent-Child Device Pairing" })
        assertNotNull(modules.find { it.title == "App Permission Management" })
        assertNotNull(modules.find { it.title == "Screen Time Management" })
        assertNotNull(modules.find { it.title == "Parent Authentication" })
        assertNotNull(modules.find { it.title == "Safety Monitoring" })
        assertNotNull(modules.find { it.title == "NIQOB AI Tutor" })
        assertNotNull(modules.find { it.title == "Age-Based Permissions" })
        assertNotNull(modules.find { it.title == "Settings" })
    }
}
