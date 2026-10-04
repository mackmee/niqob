package com.niqob.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.niqob.app.core.navigation.NiqobNavHost
import com.niqob.app.core.ui.theme.NiqobTheme
import com.niqob.app.data.repository.SupabasePairingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SupabasePairingRepository.getInstance(applicationContext)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            NiqobTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NiqobNavHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_NAVIGATE_TO_LIMIT_GATE, false) == true) {
            _navigateToLimitGateEvent.value = System.currentTimeMillis()
        }
    }

    companion object {
        const val EXTRA_NAVIGATE_TO_LIMIT_GATE = "com.niqob.app.NAVIGATE_TO_LIMIT_GATE"
        const val EXTRA_BLOCKED_TARGET_PACKAGE = "com.niqob.app.BLOCKED_TARGET_PACKAGE"

        private val _navigateToLimitGateEvent = MutableStateFlow<Long?>(null)
        val navigateToLimitGateEvent: StateFlow<Long?> = _navigateToLimitGateEvent.asStateFlow()

        fun clearNavigationEvent() {
            _navigateToLimitGateEvent.value = null
        }
    }
}
