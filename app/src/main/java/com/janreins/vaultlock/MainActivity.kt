package com.janreins.vaultlock

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.janreins.vaultlock.ui.VaultViewModel
import com.janreins.vaultlock.ui.screens.VaultNavHost
import com.janreins.vaultlock.ui.theme.VaultLockTheme

class MainActivity : FragmentActivity() {

    // ============================================================
    // SECURITY TOGGLE – FLAG_SECURE is ON by default for maximum security
    // ============================================================
    private val ENABLE_FLAG_SECURE = true
    // ============================================================

    private val viewModel: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mandatory security: prevent screenshots and recents screen previews
        if (ENABLE_FLAG_SECURE) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsState()

            VaultLockTheme(themeMode = uiState.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    VaultNavHost(
                        viewModel = viewModel,
                        onWipeApp = {
                            finishAffinity()
                        }
                    )
                }
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        viewModel.onUserActivity()
    }

    override fun onStop() {
        super.onStop()
        // The ViewModel distinguishes recreation from a real background stop.
        viewModel.onActivityStopped(isChangingConfigurations)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            viewModel.onWindowFocused()
        }
    }
}
