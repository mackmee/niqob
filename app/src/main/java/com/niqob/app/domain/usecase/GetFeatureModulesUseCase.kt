package com.niqob.app.domain.usecase

import com.niqob.app.domain.model.FeatureModule
import com.niqob.app.domain.repository.NiqobRepository

class GetFeatureModulesUseCase(
    private val repository: NiqobRepository
) {
    operator fun invoke(): List<FeatureModule> {
        return repository.getFeatureModules()
    }
}
