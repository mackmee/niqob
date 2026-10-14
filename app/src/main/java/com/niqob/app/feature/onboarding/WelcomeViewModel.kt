package com.niqob.app.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niqob.app.data.repository.SupabasePairingRepository
import com.niqob.app.domain.model.UserMode
import com.niqob.app.domain.usecase.GetUserRoleUseCase
import com.niqob.app.domain.usecase.SetUserRoleUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WelcomeViewModel(
    private val setUserRoleUseCase: SetUserRoleUseCase = SetUserRoleUseCase(SupabasePairingRepository.getInstance()),
    private val getUserRoleUseCase: GetUserRoleUseCase = GetUserRoleUseCase(SupabasePairingRepository.getInstance())
) : ViewModel() {

    val selectedRole: StateFlow<UserMode> = getUserRoleUseCase().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserMode.UNSELECTED
    )

    fun selectRole(role: UserMode, onComplete: () -> Unit) {
        viewModelScope.launch {
            setUserRoleUseCase(role)
            onComplete()
        }
    }
}
