package com.onefera.app.data.push

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.backend.ApplicationScope
import com.onefera.app.data.user.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Refreshes the user's `lastActiveAt` every minute while the app is in the foreground. */
@Singleton
class PresenceTracker @Inject constructor(
    private val auth: AuthRepository,
    private val users: UserRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    private var job: Job? = null

    fun start() = ProcessLifecycleOwner.get().lifecycle.addObserver(this)

    override fun onStart(owner: LifecycleOwner) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                (auth.session.value as? SessionState.SignedIn)?.let { users.touchLastActive(it.uid) }
                delay(60_000)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        job?.cancel()
    }
}
