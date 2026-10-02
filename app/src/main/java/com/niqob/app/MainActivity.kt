package com.niqob.app

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SupabasePairingRepository.getInstance(applicationContext)
        enableEdgeToEdge()
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
}
