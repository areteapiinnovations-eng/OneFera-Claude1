# Audio and video calling: feasibility

OneFera has **no calling feature today**, on purpose. The earlier "call" buttons were removed because nothing was behind them. This note explains what real calling needs, what it costs, and what we recommend. Prices and free tiers are as published by each provider at the time of writing. Check the provider's pricing page before committing.

## Can Firebase do it alone?

**No.** Firebase has no way to carry live audio or video between phones:

| Firebase product | Role in calling |
|---|---|
| Firestore / Realtime Database | ✅ **Signalling only**: exchanging "offer/answer" messages and network candidates, plus call state (ringing, accepted, ended) |
| Cloud Messaging (FCM) | ✅ The "incoming call" push that wakes the other phone |
| Cloud Functions | ✅ Issuing short-lived call tokens and writing call history |
| Storage / Hosting | ❌ Not usable for live media |

The live audio and video stream needs **WebRTC**, either run by us or by a managed provider.

## Options

### A. WebRTC + Firebase signalling + TURN (self-built)

- **Media:** WebRTC (open source, free), for example the `io.getstream:stream-webrtc-android` build of Google's library.
- **Signalling:** Firestore documents such as `calls/{id}` with offer, answer and candidates, secured by rules so that only the two members have access.
- **NAT traversal:**
  - Google's public STUN servers are free.
  - About 10–20% of mobile connections, typically on Indian mobile carriers, also need a **TURN relay**. A TURN relay passes the media itself, so it is billed by traffic. You can:
    - **Self-host coturn** on a small VM: roughly US$5–20/month, plus bandwidth at your cloud's egress price.
    - **Use a managed TURN service** (Cloudflare, Twilio Network Traversal, Metered), billed per GB relayed. A one-to-one HD video call uses roughly 0.5–1.5 GB per hour.
- **Effort:** high, about 4–6 weeks for production quality. That covers reconnection, Bluetooth and speaker routing, call screens, missed-call notifications and telemetry.
- **Best for:** maximum control and lowest cost at scale.

### B. Managed real-time SDK (recommended for the MVP)

| Provider | Free tier (published) | After the free tier (approx.) | Notes |
|---|---|---|---|
| **Agora** | 10,000 min/month | ~US$0.99 per 1,000 audio min; ~US$3.99 per 1,000 HD video min | Mature Android SDK, India data centres |
| **ZEGOCLOUD** | 10,000 min/month (first months) | Similar per-minute pricing | Ready-made call UI kits |
| **LiveKit** | Cloud free tier; or self-host (open source) | Usage-based on Cloud; server costs when self-hosted | Open source, so no lock-in |
| **Stream Video** | Free for small apps (with conditions) | Plan-based | Kotlin and Compose SDK |

- **Effort:** about 1–2 weeks for one-to-one audio and video with the provider's SDK.
- **Firebase still provides:** the ring push (FCM), call tokens (a Cloud Function that holds the provider's secret, which never goes in the app), call history, and block and privacy checks.

## What every option needs in the app

- **Permissions:**
  - `RECORD_AUDIO` (already added for voice notes)
  - `CAMERA`
  - `BLUETOOTH_CONNECT` (headsets, Android 12+)
  - `FOREGROUND_SERVICE` with types `phoneCall`, `microphone` and `camera` (Android 14+ requires these types)
  - `POST_NOTIFICATIONS`
  - `USE_FULL_SCREEN_INTENT`, for the ringing screen. On Android 14+, Google Play only grants it automatically to apps whose core function is calling. Otherwise use a high-priority notification.
- **Android telephony integration:** a `ConnectionService` / `Core-Telecom` integration so calls behave like phone calls (audio focus, lock-screen answer, Bluetooth buttons).
- **Play Console:** declare the foreground-service types and full-screen intent use. Update Data safety: audio and video are processed in real time; state whether calls are recorded (we would not record them).
- **Safety:**
  - Only mutual follows, or people you have messaged, can call you.
  - Blocking also blocks calls.
  - Minors get no calls from adults they don't follow.
  - Add a report flow.
- **APK size:** a WebRTC-based SDK adds about 8–15 MB per architecture. Use App Bundles so each phone downloads only its own.

## Recommendation

1. **MVP:** one-to-one **audio** calls first, with Agora or LiveKit Cloud, using Firebase for ringing, tokens and history. This is low effort and the free minutes cover beta testing.
2. **Next:** add one-to-one video on the same SDK.
3. **At scale (more than ~100k call minutes/month):** compare against self-hosted LiveKit or WebRTC + coturn.

The decision needs an owner for the provider account, and a budget alert for minutes and bandwidth.
