package com.onefera.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.push.DeepLinks
import com.onefera.app.navigation.OneFeraNavHost
import com.onefera.app.navigation.RootUiState
import com.onefera.app.navigation.RootViewModel
import com.onefera.app.data.payments.RazorpayBridge
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity(), PaymentResultWithDataListener {

    private val rootViewModel: RootViewModel by viewModels()

    @Inject lateinit var deepLinks: DeepLinks

    @Inject lateinit var razorpay: RazorpayBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hold the splash until the saved session and theme are restored, so there is no flicker.
        splash.setKeepOnScreenCondition { rootViewModel.uiState.value is RootUiState.Loading }
        enableEdgeToEdge()
        if (savedInstanceState == null) deepLinks.post(intent?.getStringExtra(DeepLinks.EXTRA))

        setContent {
            val state by rootViewModel.uiState.collectAsStateWithLifecycle()
            val ready = state as? RootUiState.Ready
            OneFeraTheme(
                skin = ready?.settings?.skin ?: com.onefera.app.core.designsystem.theme.ThemeSkin.Default,
                mode = ready?.settings?.themeMode ?: com.onefera.app.core.designsystem.theme.ThemeMode.Default,
            ) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (ready != null) {
                        OneFeraNavHost(
                            startDestination = ready.startDestination,
                            isSignedIn = ready.isSignedIn,
                            isDemoMode = ready.isDemoMode,
                            deepLinks = deepLinks,
                        )
                    }
                }
            }
        }
    }

    // Razorpay Checkout reports results to the hosting Activity.
    override fun onPaymentSuccess(paymentId: String?, data: PaymentData?) = razorpay.onSuccess(paymentId, data)

    override fun onPaymentError(code: Int, description: String?, data: PaymentData?) = razorpay.onError(code, description)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLinks.post(intent.getStringExtra(DeepLinks.EXTRA))
    }
}
