package com.onefera.app.di

import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.FirebaseAuthRepository
import com.onefera.app.data.backend.ApplicationScope
import com.onefera.app.data.backend.BackendConfig
import com.onefera.app.data.demo.DemoAuthRepository
import com.onefera.app.data.demo.DemoUserRepository
import com.onefera.app.data.user.FirestoreUserRepository
import com.onefera.app.data.user.UserRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Firebase when configured, otherwise the on-device demo backend. Only one is ever created. */
    @Provides
    @Singleton
    fun provideAuthRepository(
        config: BackendConfig,
        firebase: Provider<FirebaseAuthRepository>,
        demo: Provider<DemoAuthRepository>,
    ): AuthRepository = if (config.isFirebaseEnabled) firebase.get() else demo.get()

    @Provides
    @Singleton
    fun provideUserRepository(
        config: BackendConfig,
        firestore: Provider<FirestoreUserRepository>,
        demo: Provider<DemoUserRepository>,
    ): UserRepository = if (config.isFirebaseEnabled) firestore.get() else demo.get()
}
