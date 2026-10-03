# OneFera: One Future Era

**Connect. Shop. Sell.** OneFera is a social-commerce app built for Gen Z and Gen Alpha. You can post moments and reels, chat with your circle, shop products tagged in posts, and switch to a seller account to sell your own. An Aura score and daily streaks reward people for taking part.

This repository is the native Android app (Kotlin + Jetpack Compose).

---

## Open in Android Studio

1. **File → Open** and choose this folder (the one with `settings.gradle.kts`).
2. Let Gradle sync. Android Studio downloads everything it needs; no manual setup.
3. Pick the `app` configuration and press **Run ▶**.

Requirements: a current Android Studio with its bundled JDK 17+. Devices need Android 8.0 (API 26) or newer.

> **No Firebase yet? No problem.** Without `app/google-services.json` the app runs in **demo mode**. Accounts and profiles are stored on the phone only. Demo login: `demo@onefera.app` / `onefera123`, or create a new account.

## Connect the live backend (Firebase)

1. Create a project at <https://console.firebase.google.com>.
2. Add an **Android app** with package name `com.onefera.app`. For debug builds, also add `com.onefera.app.debug`.
3. Download `google-services.json` into `app/`. The Gradle build detects it automatically.
4. In the Firebase console, turn on:
   - **Authentication → Email/Password**
   - **Firestore Database**, in production mode
   - **Storage**
   - **Cloud Messaging**
5. Deploy the rules, indexes and Cloud Functions from this repo using the Firebase CLI. Cloud Functions need the Blaze (pay-as-you-go) plan; the free monthly quota covers early usage.
   ```bash
   npm i -g firebase-tools
   firebase login
   firebase use <your-project-id>
   firebase deploy --only firestore:rules,firestore:indexes,storage,functions
   ```
6. Rebuild. The demo-mode banner disappears and data is stored live in Firestore.

### What the backend does

| Piece | Where | Purpose |
|---|---|---|
| Firestore rules | `firebase/firestore.rules` | Who can read and write what. Clients can't change counters, Aura or other people's notifications. Followers-only posts are hidden from non-followers. |
| Storage rules | `firebase/storage.rules` | Avatars (≤ 5 MB), post photos (≤ 15 MB) and videos (≤ 100 MB), stories |
| Indexes | `firebase/firestore.indexes.json` | Feed, reels, profile grid, hashtag and notification queries |
| Cloud Functions | `firebase/functions` (TypeScript) | Like, comment, follower and post counters; Aura points; notifications plus push; hashtag counts; media and expired-story cleanup |
| Rules tests | `firebase/rules-tests` | Run with `npm ci && npm test` (starts the Firestore emulator). These also run in CI. |

## Build an APK / App Bundle (AAB)

**Debug APK** (for testing): **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, or run `./gradlew assembleDebug`.
The output is `app/build/outputs/apk/debug/app-debug.apk`.

**Signed release for the Play Store:**
1. **Build → Generate Signed App Bundle / APK…** and create a new keystore. Keep it safe and backed up; you need the same key for every future update.
2. Optional, for command-line and CI builds: copy `keystore.properties.example` to `keystore.properties` and fill in your values. That file and `*.jks` files are git-ignored. Then run:
   ```bash
   ./gradlew bundleRelease     # app/build/outputs/bundle/release/app-release.aab
   ./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
   ```
3. Release builds are shrunk and optimised with R8.
4. Upload the `.aab` to Google Play Console.

Every push to GitHub runs the **Android CI** workflow (`.github/workflows/android.yml`). It builds the debug APK and release AAB, runs the unit tests and lint, and attaches the APK and AAB to the run as downloadable artifacts.

## Project structure

```
app/src/main/java/com/onefera/app/
├── OneFeraApplication.kt, MainActivity.kt   app entry, splash, edge-to-edge
├── core/
│   ├── designsystem/theme/        colours, 5 theme skins, Sora + Outfit type, dark/light
│   ├── designsystem/component/    logo, aurora background, buttons, fields, cards, avatar, chips
│   └── common/                    validators, links
├── data/
│   ├── model/                     UserProfile, AuraGrade, …
│   ├── auth/  user/               repository interfaces + Firebase implementations
│   ├── demo/                      offline demo backend (used when Firebase isn't configured)
│   ├── settings/                  DataStore preferences (theme, onboarding, notifications)
│   └── backend/                   decides Firebase vs. demo at runtime
├── di/                            Hilt modules
├── navigation/                    type-safe routes, NavHost, root (start-destination) logic
└── feature/
    ├── onboarding/  auth/          intro carousel, log in, sign up, forgot password
    ├── main/                       7-tab shell (Home, Shop, Search, You, Chats, Near, Reels)
    ├── feed/  story/               home feed, stories row, story viewer
    ├── post/                       post card, comments, grid, post detail
    ├── create/                     new post / reel (photos up to 10, video up to 90 s)
    ├── reels/                      full-screen vertical video player
    ├── search/                     people, hashtags, recent and trending searches, tag pages
    ├── notifications/              activity, follow requests
    ├── user/                       other people's profiles, follower lists
    ├── profile/                    your profile, menu, edit profile + photo upload
    └── settings/                   skins, appearance, privacy, notifications
firebase/                           rules, indexes, Cloud Functions, rules tests
branding/                           source logo and icon files (Play Store icon: playstore-icon-512.png)
```

**Architecture:** MVVM with a unidirectional data flow. Compose screens observe `StateFlow`s from Hilt ViewModels. ViewModels talk to repository interfaces, and each repository has a Firebase implementation and a demo implementation. Library versions are pinned in `gradle/libs.versions.toml`.

## Brand

- **Logo / icon:** a vector recreation of the OneFera mark (`OneFeraMark` composable, `drawable/onefera_mark.xml`), an adaptive and themed (monochrome) launcher icon, and the Android 12+ splash icon.
- **Type:** *Sora* for headlines and *Outfit* for UI text. Both are SIL Open Font License and bundled in `res/font`.
- **Theme skins:** Future Era (the brand default), Cyber Neon, Sunset Pop, Matrix Lime and Aurora Ice. Each works in dark and light mode.
- **Optional auth background video:** drop a short, muted clip at `app/src/main/res/raw/auth_background.mp4` (720p, under 2 MB) and it plays softly behind the log-in and sign-up screens. Without it, the screens use a lightweight animated aurora. The video is skipped automatically when the user has turned system animations off.

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 1 | Project, CI, branding, themes, onboarding, auth, app shell, profile, settings | ✅ |
| 2 | Feed & stories, create post, reels player, search, notifications, follow | ✅ |
| 3 | Real-time chat (replies, attachments, quick vibes), push notifications | next |
| 4 | Shop: catalogue, filters, product page, wishlist, cart, checkout (Razorpay), orders | |
| 5 | Seller mode: dashboard, listings, inventory, orders, analytics | |
| 6 | Aura engine, streaks, leaderboard, Mystery Box, memberships | |
| 7 | Near (people + stores), release hardening, Play Store listing | |
