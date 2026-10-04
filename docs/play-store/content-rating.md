# Content rating (IARC questionnaire) notes

- **Category:** Social networking / communication, with shopping.
- **User-generated content:** Yes. Users share posts, reels, stories and messages, and can interact with each other.
- **Users can communicate:** Yes (DMs, comments). Private accounts, follow requests and unsend are supported.
- **Shares location:** Optional, approximate only (Near), never for under-18s.
- **Digital purchases:** Yes (memberships via Google Play); physical goods via Razorpay.
- **Violence, sex, drugs, gambling:** None in app content. The Mystery Box is a free daily reward (no purchase, no real-money value), so it isn't gambling, but describe it as "free daily reward" in the questionnaire.
- **Target audience (Play → Target audience and content):** 13+. Before launch, decide on the open item: **parental consent for under-18 users under India's DPDP Act 2023** (verifiable parental consent for children's data). Until then, consider launching as 18+ or adding a guardian-consent step at sign-up.

Moderation tools Play expects for UGC apps are built in:

- **Report:** posts (⋯ menu), comments (long-press), accounts and chats (⋯ menu). Reports are anonymous and stored write-only in `reports/`.
- **Block:** from a post, profile or chat. Blocked people can't follow, request, comment or message (enforced by Firestore rules), and their content is hidden everywhere in the app. Manage blocks in Settings → Blocked accounts.
- **Automatic action:** posts reported by 3 or more different people, or once for self-harm, are hidden from feeds until reviewed (`onReportCreated`).
- **Still to set up:** review open reports in the Firestore console (or an admin tool) and act within 24 h, and publish a contact address for urgent safety issues.
