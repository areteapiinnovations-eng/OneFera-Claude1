package com.onefera.app.di

import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.FirebaseAuthRepository
import com.onefera.app.data.backend.ApplicationScope
import com.onefera.app.data.backend.BackendConfig
import com.onefera.app.data.demo.DemoAuthRepository
import com.onefera.app.data.demo.DemoUserRepository
import com.onefera.app.data.chat.ChatRepository
import com.onefera.app.data.chat.FirestoreChatRepository
import com.onefera.app.data.demo.DemoChatRepository
import com.onefera.app.data.demo.DemoNotificationRepository
import com.onefera.app.data.demo.DemoPostRepository
import com.onefera.app.data.demo.DemoShopRepository
import com.onefera.app.data.demo.DemoSocialRepository
import com.onefera.app.data.demo.DemoStoryRepository
import com.onefera.app.data.firebase.FirestoreNotificationRepository
import com.onefera.app.data.firebase.FirestorePostRepository
import com.onefera.app.data.firebase.FirestoreShopRepository
import com.onefera.app.data.firebase.FirestoreSocialRepository
import com.onefera.app.data.firebase.FirestoreStoryRepository
import com.onefera.app.data.social.NotificationRepository
import com.onefera.app.data.social.PostRepository
import com.onefera.app.data.social.SocialRepository
import com.onefera.app.data.social.StoryRepository
import com.onefera.app.data.shop.ShopRepository
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

    @Provides
    @Singleton
    fun providePostRepository(
        config: BackendConfig,
        firestore: Provider<FirestorePostRepository>,
        demo: Provider<DemoPostRepository>,
    ): PostRepository = if (config.isFirebaseEnabled) firestore.get() else demo.get()

    @Provides
    @Singleton
    fun provideStoryRepository(
        config: BackendConfig,
        firestore: Provider<FirestoreStoryRepository>,
        demo: Provider<DemoStoryRepository>,
    ): StoryRepository = if (config.isFirebaseEnabled) firestore.get() else demo.get()

    @Provides
    @Singleton
    fun provideSocialRepository(
        config: BackendConfig,
        firestore: Provider<FirestoreSocialRepository>,
        demo: Provider<DemoSocialRepository>,
    ): SocialRepository = if (config.isFirebaseEnabled) firestore.get() else demo.get()

    @Provides
    @Singleton
    fun provideNotificationRepository(
        config: BackendConfig,
        firestore: Provider<FirestoreNotificationRepository>,
        demo: Provider<DemoNotificationRepository>,
    ): NotificationRepository = if (config.isFirebaseEnabled) firestore.get() else demo.get()

    @Provides
    @Singleton
    fun provideChatRepository(
        config: BackendConfig,
        firestore: Provider<FirestoreChatRepository>,
        demo: Provider<DemoChatRepository>,
    ): ChatRepository = if (config.isFirebaseEnabled) firestore.get() else demo.get()

    @Provides
    @Singleton
    fun provideShopRepository(
        config: BackendConfig,
        firestore: Provider<FirestoreShopRepository>,
        demo: Provider<DemoShopRepository>,
    ): ShopRepository = if (config.isFirebaseEnabled) firestore.get() else demo.get()
}
