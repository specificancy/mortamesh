# Morta Mesh for Android

Morta Mesh is an offline-first Android nearby messenger prototype. Current development branch: **0.8.0 / core protocol v2 with anonymous submissions and an isolated wallet foundation**. It includes Wi-Fi Direct, Bluetooth LE, an app-private photo/video capture flow, an encrypted Private Vault, and a separate wallet entry. The first wallet milestone is Bitcoin Testnet4-only; Lama Coin and native NFTs are clearly marked as not yet connected. It has no sign-up and no central messaging server for its mesh messaging.

The Android manifest includes `INTERNET` permission for the app's **local Wi-Fi Direct TCP sockets**. This allows directly connected phones to exchange data over their local link; it is not used to connect to a central messaging server.

## Implemented networking

- **Wi-Fi Direct:** discovery plus a local TCP transport for direct peers. It is preferred for large media when a direct recipient route is available.
- **Bluetooth LE:** GATT discovery and direct text/media framing.
- **Mesh identity gossip and relaying:** connected phones exchange signed identity records, periodically refresh the local identity advertisement, age out stale routes, and forward encrypted packets. Route advertisements can improve a route when the same announcement arrives along a shorter path. A packet ID cache suppresses duplicates; packets have a maximum eight-hop TTL and fall back to bounded flooding when a route is missing.
- **Logical identities:** a stable UUIDv8 node ID is derived from SHA-256 of the install's P-256 public key, binding the logical identity to the signing key separately from ephemeral radio addresses. Relayed peers appear in **Nearby → Reachable through Morta Mesh** and can be opened like a normal chat.
- **Plaintext compatibility is intentionally disabled:** messages from older builds that arrive as raw text are rejected instead of being shown as if protected.

Mesh relaying requires each intermediate phone to have working links to both sides of a path and Morta Mesh running. Android Wi-Fi Direct group rules and Bluetooth connection limits differ across devices. This implementation has not been validated against a multi-phone test network yet, and it is not an always-on/background mesh daemon.

## End-to-end message and media protection

- Local conversation history is stored as an AES-GCM encrypted file under app-private storage. Its AES key is held by Android Keystore; an older plaintext SharedPreferences history is migrated and removed after an encrypted copy is committed.
- Each install creates a P-256 identity key pair. The private key is AES-GCM encrypted with an Android Keystore wrapping key and stored in app-private `filesDir`.
- Identity announcements are signed with ECDSA and use **trust-on-first-use (TOFU)** public-key pinning. A later different key for the same node ID is blocked.
- Each application message uses an ephemeral P-256 ECDH key, HKDF-SHA256, AES-256-GCM, a sender ECDSA signature, and a signed creation timestamp. The sender node ID must be derived from the embedded public key before a relay will forward an envelope. The recipient requires a pinned sender key and successful AES-GCM authentication before committing a chat message ID to the durable replay ledger. That sender-scoped ledger retains up to 700,000 authenticated chat IDs for seven days, enforces a persisted 60-message-per-minute device-wide acceptance budget, and never evicts a still-live ID to make room. Existing pre-upgrade IDs are retained as conservative global entries until their original expiry. Relays use a bounded in-memory forwarding cache keyed by sender and message ID rather than writing untrusted relay IDs into the recipient's durable ledger. Media chunks use their authenticated transfer ID, sender binding, ordered chunk index, and bounded transfer session rather than filling the chat replay database with every chunk. Intermediate relays can see routing identifiers and sizes but cannot decrypt the payload.
- **Peer info** shows a SHA-256 identity fingerprint. Compare the full fingerprint with your contact through a separate trusted channel to reduce first-contact man-in-the-middle risk.
- The cryptographic code is a prototype implementation and has not received an independent audit. It provides no Double Ratchet, no forward secrecy against later compromise of the recipient's long-term identity key, and no formal security proof. Do not treat it as a production-certified secure messenger until it has been audited and hardened.

### Protocol behavior

- `@MORTAMESH/2/ID` frames distribute signed identity records whose UUIDv8 node IDs derive from public keys.
- `@MORTAMESH/2/PACKET` frames contain an encrypted, signed envelope with a signed timestamp plus a hop TTL. Relays verify the signature, key-derived sender ID, and timestamp, then use a bounded in-memory duplicate cache for forwarding. A destination first verifies its pinned sender and decrypts successfully, then commits non-media chat IDs to its durable replay ledger. Relays route toward a destination when a fresh next hop is known and otherwise flood to connected peers other than the inbound link.
- Protocol v2 intentionally rejects protocol v1 frames; all devices in a mesh must upgrade together.
- Each chat message and media chunk is a separate envelope. For large media, repeated ECDH/signature work may affect battery and throughput; this should be optimized with a reviewed per-transfer session-key protocol before production use.
- Routing metadata (node IDs, message size/timing, and direct-neighbor information) is not hidden from participating relays.

## Public World Chat (experimental)

World Chat is a separate public broadcast room inside **Conversations**; it is not part of one-to-one encrypted chats.

- Each post is signed with the author's persistent P-256 identity key. A receiver verifies the signature, key-derived node ID, timestamp, UUID, and text bound before saving or forwarding it.
- A post is intentionally **plaintext and public**. Every participating device and relay can read it. It is not end-to-end encrypted; do not post private or sensitive information there. Private conversations continue to use the existing E2E envelope path.
- A new post is gossiped to every connected direct neighbor. Each receiver relays it to its other neighbors with a maximum eight-hop TTL; a durable ID ledger suppresses duplicates across app restarts and alternate paths.
- The composer enforces **500 Unicode code points** (so a common emoji counts as one character). Each author is limited to five accepted posts per minute on a receiving device.
- Local storage keeps up to 1,000 recent posts for seven days and up to 275,000 seen post IDs for seven days. A persisted device-wide budget accepts at most 20 distinct signed post IDs per minute, in addition to the five-per-author-per-minute limit. Live IDs are not evicted to make room. Pre-verification ingress budgets also limit work requested by each physical neighbor and across the device. When a direct link comes up, devices exchange a small catch-up burst of their latest five posts to help newly connected mesh islands converge without flooding BLE.
- “World” means the reachable Morta Mesh network, not every Morta Mesh installation worldwide. Disconnected mesh islands cannot exchange posts until a radio path joins them. There is no GPS-based room or central internet relay.
- Background delivery is subject to Android process and radio restrictions. Keep Morta Mesh running for reliable relaying; this is not an always-on background daemon.

World Chat frames use `@MORTAMESH/2/WORLD` and remain distinct from `@MORTAMESH/2/PACKET` private encrypted envelopes.

## Private camera and media routing

The conversation composer includes **Take photo** and **Record video**. Capture happens in Morta Mesh, not the public camera app. CameraX temporarily writes into the app-private cache; the plaintext temporary file is encrypted into the vault and removed before transfer. The app does not add captured/received media to Android MediaStore, Downloads, or shared storage.

Transfer policy:

- **Photos:** Bluetooth LE only. If the recipient is not directly reachable over an authenticated BLE link, the encrypted copy stays queued.
- **Videos:** Wi-Fi Direct only. If the recipient is not directly reachable over an authenticated Wi-Fi Direct link, the encrypted copy stays queued.
- Media is never relayed through intermediate phones. The current per-item limit is 2 GiB; available disk space and vault quotas can impose a lower practical limit.
- Video capture is capped at 60 seconds.

The recipient stores received bytes encrypted in its own Private Vault. Local vault encryption uses AES-GCM with Android Keystore, while the media bytes and transfer controls are also protected by end-to-end message encryption in transit.

## Anonymous Mortalive group submissions (Mesh-side foundation; backend pending)

The Conversations screen now includes **Anonymous group submission**. A Morta Mesh user can enter a destination Mortalive group ID/submission code, add up to 5,000 Unicode code points of text and optionally choose a supported photo, video, or audio file (maximum 100 MiB). Drafts are encrypted at rest with Android Keystore AES-GCM, capped at 20 items / 250 MiB total attachment bytes, and expire after 72 hours.

The branch also adds a separate sender-to-ingestion package builder and a separate `@MORTAMESH/2/ANON` relay protocol. It wraps a fresh per-submission AES-256-GCM key to a supplied P-256 Mortalive ingestion public key, signs a manifest with a per-submission ephemeral P-256 key, and includes signed per-chunk and whole-file SHA-256 hashes. The group destination is inside the encrypted payload, not relay-visible metadata. Opted-in peers can store and forward ciphertext over Wi-Fi Direct with bounded queue size, expiry and an eight-hop TTL. A per-neighbor limiter controls new relay admissions. **Settings → Peer Mode · encrypted carry** is an explicit, revocable opt-in for carrying other people's jobs; sending your own submission does not require it. Settings also shows and can clear the relay cache.

This feature is deliberately independent of private chat media and public mesh World Chat. Existing private photos/videos remain direct-recipient-only and are never routed through the new anonymous relay path. World Chat remains a separate public, plaintext mesh text room.

**Current limit:** this is not yet a working Mortalive upload/publish integration. Mortalive's ingestion endpoint, authenticated key-discovery/rotation contract, group submission capability, internet gateway API, moderation result API and group-message encryption/publishing contract have not been supplied. The app currently saves encrypted local drafts. The packager/relay staging hook requires Mortalive's real pinned ingestion key and is not called by the composer UI. No submission is uploaded, decrypted for moderation, approved, or delivered into a Mortalive group by this build.

Mortalive is a separate social/chat product with profiles/posts, messaging/talk, creator/developer surfaces and AI companion personas. The integration is a draft for the engineers working on Mortalive; **no Mortalive code or configuration has been changed**. See [MORTALIVE_INTEGRATION_DRAFT.md](MORTALIVE_INTEGRATION_DRAFT.md) for the proposed group-scoped anonymous permission, key contract, resumable upload API, moderation boundary, approved group encryption, status flow and abuse controls. The complete app workflow and change handoff are in [LATEST_APP_WORKFLOW_AND_CHANGES.txt](LATEST_APP_WORKFLOW_AND_CHANGES.txt).

## Private Vault

- Media files live in `filesDir/private_media_vault` as app-private `.mmv` files; they are not exported to public gallery directories.
- File contents and the vault index use AES-GCM with an Android Keystore key.
- A PIN is stored as a salted PBKDF2-HMAC-SHA256 verifier; the PIN is not stored as plaintext.
- The viewer is PIN-gated and locks when the app goes into the background.
- A recipient's local media copy is encrypted with that recipient device's own vault key.
- Incoming media is limited to four active sessions globally and two per authenticated sender, with a 120-second idle timeout and a 30-minute absolute timeout. The vault reserves remaining disk space across all active incoming writes, preserves a free-space floor, limits received media to 8 GiB total / 4 GiB per sender, and does not silently delete existing vault items to enforce those quotas.
- Encrypted media bytes are finalized and synced before the vault index is committed and a delivery receipt is sent. The encrypted index is atomically replaced and the containing directory is synced.
- If index metadata is missing or unreadable while encrypted files exist, the vault enters a read-only unavailable state instead of creating an empty replacement index. Retry rechecks the preserved or quarantined snapshot; orphaned encrypted files are not automatically removed while an unreadable index archive exists.

## Build

Requirements: Android Studio with Android SDK Platform 35, JDK 17, Kotlin 2.0.21, and Android Gradle Plugin 8.7.3. Dependencies are declared in `app/build.gradle.kts` (AndroidX Core 1.15.0, CameraX 1.4.2, Activity KTX 1.10.1).

1. Open the repository root (the directory containing `settings.gradle.kts` and `app/`) in Android Studio. Use JDK 17, Android SDK Platform 35, Kotlin 2.0.21, Android Gradle Plugin 8.7.3, and Gradle 8.9. This repository does not include a Gradle wrapper, so use an installed Gradle 8.9 or Android Studio's compatible Gradle installation. The checked-in `.github/workflows/android-build.yml` workflow automatically runs `clean testDebugUnitTest assembleDebug` on pushes and pull requests to `main`, and uploads the debug APK when the build succeeds.
2. Install on at least three physical Android phones for mesh tests; two phones only validate direct messaging.
3. Grant Nearby devices/Bluetooth, Nearby Wi-Fi, Camera and (for video) Microphone permissions. Enable Wi-Fi and Bluetooth and keep Morta Mesh open on each device.
4. Connect phone A to B and phone B to C through Nearby. Once each physical link has exchanged identities, A should eventually see C under **Reachable through Morta Mesh**. Open A↔C and send a text/photo.
5. On the receiver, open **Peer info** and compare the identity fingerprint out of band before exchanging sensitive content.

## Release/readiness status

This is an implementation-stage prototype, **not a verified production release**. The latest security-patched `main` commit (`b2a12c8`) passed the Android CI workflow (run #41232413980), including `clean testDebugUnitTest assembleDebug` and APK artifact upload. That verifies compilation, the configured JVM unit-test task, and debug APK assembly; it does not replace physical-device or security testing. BLE/Wi-Fi Direct tests, three-device routing, encryption interoperability, storage-recovery checks, and independent cryptographic review remain required before release. Global Media Relay is only a documented proposal and is not part of this build.

Media policy is based on type, never file size: text uses an available authenticated route, photos use Bluetooth LE, and videos use Wi-Fi Direct only. Media is direct-link-only and remains encrypted in the Private Vault queue until an end-to-end receipt from the saved logical recipient confirms that the recipient's encrypted file and vault index were committed. A receipt from a different pinned peer cannot mark the transfer delivered. Prior unsent media is recovered only when chat history uniquely identifies its intended conversation. Vault media decryption and image decoding run off the main thread; photo previews are downsampled to limit memory pressure.

Known limitations: background radio behavior varies by OEM/API; media (photos over BLE, videos over Wi-Fi Direct) is sent only over a direct link to the recipient and is never relayed, so media waits in the queue until the recipient is directly connected on the required link type; large photos can be very slow over Bluetooth LE; media storage is capped by per-item, per-sender, total-vault, and free-space limits; there is no ratcheting/forward secrecy; TOFU still requires fingerprint verification; route hop claims remain manipulable by malicious neighbors; routing metadata remains visible; protocol behavior, storage recovery, and three-device routing still need field testing.


## Wallet foundation (0.8.0 · experimental)

Morta Mesh adds a separate Wallet screen. The first Bitcoin integration is limited to testnet: create or restore a 12-word wallet, derive BIP-84 receive addresses, view the Testnet4 balance, and sync using Bitcoin Dev Kit. Recovery material is encrypted with an Android Keystore AES-256-GCM key and stored under no-backup app-private storage. This milestone does not expose Bitcoin mainnet, send, signing or broadcast.

The Lama Coin section is intentionally a separate, not-yet-connected foundation. The uploaded Lama Coin source is a desktop Rust node/wallet rather than an Android library; it also needs its genesis, network identity and Android integration contract resolved before the app can generate Lama addresses or manage funds. Native NFTs and marketplace settlement are likewise pending native asset transaction support in the chain.

Wallet activity is independent of Mortalive authentication and does not share wallet addresses, keys, balances, holdings or transaction history with Mortalive or anonymous group submissions. Any future NFT display or membership proof must be an explicit, separate opt-in.

See [WALLET_ARCHITECTURE.md](WALLET_ARCHITECTURE.md) for the implemented scope, explicit limitations and follow-up engineering requirements. This is an experimental testnet foundation, not a production wallet.
