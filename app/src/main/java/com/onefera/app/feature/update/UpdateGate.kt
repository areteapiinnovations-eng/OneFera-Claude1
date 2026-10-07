package com.onefera.app.feature.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.onefera.app.BuildConfig
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.OneFeraWordmark
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.update.UpdatePolicy
import com.onefera.app.data.update.UpdatePolicyRepository
import com.onefera.app.data.update.UpdateRules
import com.onefera.app.data.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/** Where a Google Play download started from the app stands. */
enum class PlayDownload { Idle, Downloading, Ready }

data class UpdateUiState(
    val state: UpdateState = UpdateState.None,
    val policy: UpdatePolicy? = null,
    /** Version Google Play can install now; 0 when none (or not installed from Play). */
    val playVersionCode: Int = 0,
    val canFlexible: Boolean = false,
    val canImmediate: Boolean = false,
    val download: PlayDownload = PlayDownload.Idle,
    val progress: Float = 0f,
)

private data class PlayInfo(
    val info: AppUpdateInfo? = null,
    val download: PlayDownload = PlayDownload.Idle,
    val progress: Float = 0f,
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    policies: UpdatePolicyRepository,
    @ApplicationContext context: Context,
) : ViewModel() {
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(context)
    private val play = MutableStateFlow(PlayInfo())
    /** "Later" hides the offer for that version until the app is restarted. */
    private val dismissed = MutableStateFlow(0)

    private val listener = InstallStateUpdatedListener { s ->
        when (s.installStatus()) {
            InstallStatus.DOWNLOADING -> play.update {
                val total = s.totalBytesToDownload().coerceAtLeast(1)
                it.copy(download = PlayDownload.Downloading, progress = s.bytesDownloaded().toFloat() / total)
            }
            InstallStatus.DOWNLOADED -> play.update { it.copy(download = PlayDownload.Ready, progress = 1f) }
            InstallStatus.FAILED, InstallStatus.CANCELED -> play.update { it.copy(download = PlayDownload.Idle, progress = 0f) }
            else -> Unit
        }
    }

    val state: StateFlow<UpdateUiState> = combine(policies.policy(), play, dismissed) { policy, p, dismissedCode ->
        val info = p.info
        val available = info?.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
        val playCode = if (available) info.availableVersionCode() else 0
        UpdateUiState(
            state = UpdateRules.evaluate(BuildConfig.VERSION_CODE, policy, playCode, dismissedCode),
            policy = policy,
            playVersionCode = playCode,
            canFlexible = available && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
            canImmediate = available && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
            download = p.download,
            progress = p.progress,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdateUiState())

    init {
        manager.registerListener(listener)
    }

    /** Asks Google Play what it can install. Fails quietly for APK installs, which Play can't update. */
    fun refresh() = viewModelScope.launch {
        val info = runCatching { manager.appUpdateInfo.await() }
            .onFailure { Log.i(TAG, "Play in-app updates unavailable (not installed from Play?): ${it.message}") }
            .getOrNull()
        play.update {
            it.copy(
                info = info,
                download = if (info?.installStatus() == InstallStatus.DOWNLOADED) PlayDownload.Ready else it.download,
            )
        }
    }

    fun dismiss() {
        val s = state.value
        dismissed.value = maxOf(s.policy?.latestVersionCode ?: 0, s.playVersionCode)
    }

    /** Starts a Google Play update; returns false when Play can't, so the caller opens the store link. */
    fun startPlayUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>, immediate: Boolean): Boolean {
        val info = play.value.info ?: return false
        val type = if (immediate) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
        if (!info.isUpdateTypeAllowed(type)) return false
        return runCatching { manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(type).build()) }
            .onSuccess { if (!immediate) play.update { it.copy(download = PlayDownload.Downloading) } }
            .isSuccess
    }

    /** Resumes an update that was interrupted (e.g. the app was closed mid-install). */
    fun resumeIfNeeded(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val info = play.value.info ?: return
        if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
            runCatching { manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()) }
        }
    }

    /** Installs a downloaded flexible update; the app restarts. */
    fun restartToInstall() {
        manager.completeUpdate()
    }

    override fun onCleared() {
        manager.unregisterListener(listener)
    }

    private companion object {
        const val TAG = "OneFeraUpdate"
    }
}

/**
 * Wraps the app: blocks it behind an "Update required" screen when this build is below
 * `config/app.minVersionCode`, and otherwise shows a dismissible "Update available" card. Google
 * Play installs update in the app; other installs open `updateUrl` (or the Play listing).
 */
@Composable
fun UpdateGate(viewModel: UpdateViewModel = hiltViewModel(), content: @Composable BoxScope.() -> Unit) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { viewModel.refresh() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
                viewModel.resumeIfNeeded(launcher)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun openStore() {
        val url = ui.policy?.updateUrl?.takeIf { it.isNotBlank() } ?: UpdateRules.storeUrl(context.packageName)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = ui.state) {
            is UpdateState.Required -> RequiredScreen(s.message, onUpdate = {
                if (!viewModel.startPlayUpdate(launcher, immediate = true)) openStore()
            })
            else -> {
                content()
                AnimatedVisibility(
                    visible = s is UpdateState.Available || ui.download != PlayDownload.Idle,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    AvailableCard(
                        message = (s as? UpdateState.Available)?.message ?: UpdateRules.DEFAULT_AVAILABLE,
                        download = ui.download,
                        progress = ui.progress,
                        onLater = viewModel::dismiss,
                        onUpdate = { if (!viewModel.startPlayUpdate(launcher, immediate = false)) openStore() },
                        onRestart = viewModel::restartToInstall,
                    )
                }
            }
        }
    }
}

@Composable
private fun RequiredScreen(message: String, onUpdate: () -> Unit) {
    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.7f) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OneFeraWordmark(height = 32.dp)
            Spacer(Modifier.height(28.dp))
            Text("Time to update ✨", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = OneFeraTheme.extras.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            GradientButton("Update now", onClick = onUpdate, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Text("You're on version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = OneFeraTheme.extras.muted)
        }
    }
}

@Composable
private fun AvailableCard(
    message: String,
    download: PlayDownload,
    progress: Float,
    onLater: () -> Unit,
    onUpdate: () -> Unit,
    onRestart: () -> Unit,
) {
    GlassCard(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 84.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraLarge),
    ) {
        when (download) {
            PlayDownload.Ready -> {
                Text("Update downloaded", style = MaterialTheme.typography.titleMedium)
                Text("Restart OneFera to finish installing.", style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted)
                Spacer(Modifier.height(10.dp))
                GradientButton("Restart now", onClick = onRestart, modifier = Modifier.fillMaxWidth())
            }
            PlayDownload.Downloading -> {
                Text("Downloading the update…", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("Keep using the app, we'll tell you when it's ready.", style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted)
            }
            PlayDownload.Idle -> {
                Text("Update available", style = MaterialTheme.typography.titleMedium)
                Text(message, style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onLater) { Text("Later") }
                    Spacer(Modifier.width(8.dp))
                    GradientButton("Update", onClick = onUpdate, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
