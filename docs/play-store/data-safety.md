# Data safety form answers

Answers for Play Console → App content → Data safety, based on what the code actually does.

**Does your app collect or share any of the required user data types?** Yes.
**Is all of the user data collected by your app encrypted in transit?** Yes (HTTPS only; `network_security_config.xml` blocks cleartext).
**Do you provide a way for users to request that their data is deleted?** Yes. In-app: Settings → Delete account. Web: https://onefera.app/delete-account (set this page up and link it in the form).

| Data type | Collected | Shared | Optional? | Purpose |
|---|---|---|---|---|
| Name, username | ✅ | ❌ | Required | Account management, app functionality |
| Email address | ✅ | ❌ | Required | Account management, communications (password reset) |
| Date of birth (for age) | ✅ | ❌ | Required | Account management (age checks, under-18 safety) |
| Phone number, address | ✅ | ✅ with the seller and the payment processor for an order | Optional (only when buying) | App functionality (delivery) |
| Approximate location | ✅ | ❌ | Optional (Near, off by default) | App functionality (nearby people/stores) |
| Photos and videos | ✅ | ❌ | Optional | App functionality (posts, stories, chat, listings) |
| Messages (in-app) | ✅ | ❌ | Optional | App functionality |
| Files and docs (chat attachments) | ✅ | ❌ | Optional | App functionality |
| Purchase history | ✅ | ❌ | Optional | App functionality (orders, refunds) |
| Payment info | ❌ collected by OneFera; handled by Razorpay / Google Play | — | — | — |
| App interactions (likes, follows) | ✅ | ❌ | Required | App functionality, personalisation (feed, Aura) |
| Device or other IDs (FCM token) | ✅ | ❌ | Optional (push) | App functionality (notifications) |

Not collected: precise location, contacts, calendar, health, financial account numbers, web history, crash logs (no Crashlytics yet; add it here if you enable it).
