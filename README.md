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
7. **Push notifications** work once `google-services.json` is in place: the app registers each device's FCM token and asks for the notification permission on Android 13+. Tapping a notification opens the matching chat, post, profile or order.
8. **Shop catalogue:** load the sample products (the same 100 items demo mode uses) into Firestore. Use a service-account key from *Project settings → Service accounts*:
   ```bash
   cd firebase/functions && npm ci
   GOOGLE_APPLICATION_CREDENTIALS=/path/to/key.json npm run seed -- --project <your-project-id>
   ```
   Re-running it refreshes titles, prices and images, and keeps live stock and sales counts. The sample product photos are hosted on the free DummyJSON test-data CDN. Replace them with real listings before launch.

### Seller mode

Switch any account to Seller from the profile menu, then open the **Seller hub**:

- **Overview:** total sales, orders, average order value, a 7-day sales chart with the week-over-week change, best sellers, and low-stock alerts with one-tap restock.
- **Listings:** add or edit products with up to 5 photos, category, price and MRP, stock, sizes or colours, and highlights. Listings appear in the Shop and can be tagged in posts. Stock can be adjusted inline.
- **Orders:** orders that contain your items, filterable by *to ship*, *in transit*, *delivered* and *cancelled*. *Mark as packed → shipped → out for delivery → delivered* updates the buyer's tracking and sends them a push.

Sellers get a push for each new sale. In demo mode, a demo shopper buys a new listing about 20 seconds after it goes live, so the whole flow can be tried on one phone.

### Near (people and stores)

The Near tab shows creators and seller stores around you, privacy first:

- Only **approximate location** is used: `ACCESS_COARSE_LOCATION`, rounded again to ~1 km before upload. Distances are shown as "~3 km away", never as a pin on a map.
- **Off by default.** You can look around without being visible. "Show me on Near" adds you, and switching your account to private removes you instantly.
- **Under-18s are never listed** and can't turn visibility on. Private accounts are excluded too. Firestore rules enforce all of this, not just the app.
- Sellers can list their store; store pages show all their products, with Message and Profile buttons.
- Entries that haven't been refreshed in 24 h disappear (scheduled `cleanUpNear`).
- In demo mode, demo creators and stores are placed around your real location, or around Mumbai on emulators.

### Safety

- **Report** posts, comments (long-press), accounts and chats. Reports are anonymous and stored write-only in `reports/`.
- **Block** from a post, profile or chat. Firestore rules stop blocked people from following, requesting, commenting or messaging, and the app hides their posts, reels, stories, comments, chats and Near presence. Manage blocks in Settings → Blocked accounts.
- Posts reported by 3+ people (or once for self-harm) are hidden from feeds automatically until a moderator reviews them.
- **Delete account** in Settings permanently removes the account and its content (Google Play requirement).

### Aura, streaks and rewards

- **Aura** (0–1000; grades C, B, A, S and SSS) is earned for posting and for receiving likes, comments and followers. All Aura is granted server-side.
- **Daily streak:** the first time the app opens each day (India time), the check-in adds +5 Aura, with a +25 bonus every 7th day. OneFera+ members get double. The 🔥 pill in the top bar opens Rewards.
- **Mystery Box:** one a day after checking in (two with OneFera+). It contains Aura (10, 25 or 50) or a coupon: ₹50 off ₹499+, 10% off ₹999+ capped at ₹200, or free delivery. Coupons last 14 days and appear at checkout.
- **Leaderboard:** global, friends and city rankings, with a podium for the top 3. The ⚡ pill in the top bar opens it.
- **Memberships:** OneFera+ (₹199/mo) gives 2 boxes a day, double streak Aura, free delivery and a badge. Seller Pro (₹799/mo) adds unlimited listings (free sellers get 25), 30-day analytics and a Pro badge.
  - **Google Play Billing** (library 8.0) is used whenever the products exist in Play Console: `onefera_plus_monthly` and `onefera_seller_pro_monthly`, monthly base plans priced ₹199 and ₹799.
  - Each purchase is bound to the account (obfuscated account id) and verified server-side by `verifyPlaySubscription`. That function calls the Play Developer API, so in Play Console → Users and permissions, grant the Cloud Functions service account access to view financial data and manage orders.
  - Renewals and cancellations arrive through Real-time Developer Notifications: create the Pub/Sub topic `play-billing` and point Play Console → Monetization setup at it.
  - In demo mode, or before the products exist, purchases are simulated.

### Payments (Razorpay)

Checkout runs in **simulated mode** by default. The full flow works end to end: the server re-prices the cart, the payment sheet appears, the order is confirmed and stock is reduced. No real money moves.

- The server decides prices, stock and payment verification. The app never sends a price.
- Simulated orders move through *Packed → Shipped → Out for delivery → Delivered* on their own, with a push at each step, so tracking can be tried. In demo mode this takes about 10 minutes; on Firebase, about a day.
- Live mode is already supported on the server. To switch it on, add `firebase/functions/.env`:
  ```
  PAYMENTS_MODE=live
  RAZORPAY_KEY_ID=rzp_live_xxx
  RAZORPAY_KEY_SECRET=xxx
  ```
  The server then creates Razorpay orders, the app opens **Razorpay Checkout** (SDK 1.6.41) for UPI, cards and net banking, and `confirmPayment` verifies each payment's HMAC signature before the order is placed.

### What the backend does

| Piece | Where | Purpose |
|---|---|---|
| Firestore rules | `firebase/firestore.rules` | Who can read and write what. Clients can't change counters, Aura or other people's notifications. Followers-only posts are hidden from non-followers. |
| Storage rules | `firebase/storage.rules` | Avatars (≤ 5 MB), post photos (≤ 15 MB) and videos (≤ 100 MB), stories |
| Indexes | `firebase/firestore.indexes.json` | Feed, reels, profile grid, hashtag, notification and order queries |
| Cloud Functions | `firebase/functions` (TypeScript) | Like, comment, follower and post counters; Aura points; notifications plus push; chat previews, unread counts and message push; hashtag counts; media and expired-story cleanup; checkout (`startCheckout` with coupons, `confirmPayment`, `cancelOrder`) and the simulated courier; rewards (`dailyCheckIn`, `openMysteryBox`, `startMembership`, `cancelMembership`) |
| Shop data | `products`, `orders`, `users/{uid}/cart, wishlist, addresses` | Anyone signed in can read the catalogue. Seller accounts create and edit only their own listings, and can't touch sales counters or ratings. Carts, wishlists and addresses are private. Orders are readable by the buyer and the sellers in them, and written only by functions (`updateOrderStatus` for sellers). |
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
│   ├── model/                     UserProfile, AuraGrade, Post, Product, Order, …
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
    ├── search/                     people, hashtags, products, recent and trending searches, tag pages
    ├── rewards/                    Rewards (streak, Mystery Box, coupons), leaderboard, memberships
    ├── seller/                     Seller hub (sales, 7-day chart, best sellers, low stock), listing editor
    │                               with photos, inventory, orders to fulfil
    ├── shop/                       shop tab (categories, Drop of the Day, filters, sort), product page,
    │                               wishlist, cart, checkout + simulated Razorpay, orders + tracking, product tags
    ├── chat/                       chats list, new message, conversation (replies, attachments, quick replies)
    ├── notifications/              activity, follow requests
    ├── user/                       other people's profiles, follower lists
    ├── profile/                    your profile, menu, edit profile + photo upload
    └── settings/                   skins, appearance, privacy, notifications
firebase/                           rules, indexes, Cloud Functions, rules tests
branding/                           source logo and icon files (Play Store icon: playstore-icon-512.png)
```

**Architecture:** MVVM with a unidirectional data flow. Compose screens observe `StateFlow`s from Hilt ViewModels. ViewModels talk to repository interfaces, and each repository has a Firebase implementation and a demo implementation. Library versions are pinned in `gradle/libs.versions.toml`.

## Release checklist

See `docs/play-store/` for the store listing text, Data safety answers, content-rating notes and a privacy policy draft. Before the first production release:

1. Create the Firebase project and deploy (see above). Load the catalogue with `npm run seed`.
2. Create an upload keystore and `keystore.properties` (see *Build an APK / App Bundle*), and enrol in **Play App Signing**.
3. Payments: set Razorpay live keys in `firebase/functions/.env`. Create the two subscription products and the `play-billing` topic.
4. Host `https://onefera.app/privacy`, `/terms`, `/guidelines` and `/delete-account` (web deletion request). The privacy draft is in `docs/play-store/privacy-policy.md`.
5. Decide **DPDP parental consent** for under-18s (see `docs/play-store/content-rating.md`).
6. Play Console: Data safety, content rating, target audience, and an internal testing track first.
7. Release hardening already in place:
   - R8/shrinking with keep rules for Firestore models and Razorpay.
   - HTTPS-only network config.
   - No local data in backups.
   - In-app account deletion: Settings → Delete account removes Firestore data, Storage files and the Auth user.
   - Server-side checks for every price, payment, reward and membership.

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
| 3 | Real-time chat (replies, attachments, quick vibes), push notifications | ✅ |
| 4 | Shop: catalogue, filters, product page, wishlist, cart, checkout (Razorpay, simulated), orders, product tags | ✅ |
| 5 | Seller mode: dashboard, listings, inventory, orders, analytics | ✅ |
| 6 | Aura engine, streaks, leaderboard, Mystery Box, memberships | ✅ |
| 7 | Near (people + stores), release hardening, Play Store listing | ✅ |
