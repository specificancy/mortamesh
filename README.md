# Morta Mesh for Android

Morta Mesh is an offline-first Android nearby messenger prototype. Current development branch: **0.6.0 / protocol v2**. It includes Wi-Fi Direct, Bluetooth LE, an app-private photo/video capture flow, and an encrypted Private Vault. It has no sign-up, no central messaging server, and no wallet/crypto UI.

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
- Each application message, including media transfer controls and chunks, uses an ephemeral P-256 ECDH key, HKDF-SHA256, AES-256-GCM, a sender ECDSA signature, and a signed creation timestamp. The sender node ID must be derived from the embedded public key before a relay will forward an envelope. A durable app-private replay ledger suppresses duplicate IDs across process restarts (up to 50,000 recent IDs / seven days). The recipient still requires a pinned sender key before decryption. Intermediate relays can see routing identifiers and sizes but cannot decrypt the payload.
- **Peer info** shows a SHA-256 identity fingerprint. Compare the full fingerprint with your contact through a separate trusted channel to reduce first-contact man-in-the-middle risk.
- The cryptographic code is a prototype implementation and has not received an independent audit. It provides no Double Ratchet, no forward secrecy against later compromise of the recipient's long-term identity key, and no formal security proof. Do not treat it as a production-certified secure messenger until it has been audited and hardened.

### Protocol behavior

- `@MORTAMESH/2/ID` frames distribute signed identity records whose UUIDv8 node IDs derive from public keys.
- `@MORTAMESH/2/PACKET` frames contain an encrypted, signed envelope with a signed timestamp plus a hop TTL. Relays verify the signature, key-derived sender ID, and timestamp before updating the durable replay ledger. They route toward a destination when a fresh next hop is known and otherwise flood to connected peers other than the inbound link.
- Protocol v2 intentionally rejects protocol v1 frames; all devices in a mesh must upgrade together.
- Each chat message and media chunk is a separate envelope. For large media, repeated ECDH/signature work may affect battery and throughput; this should be optimized with a reviewed per-transfer session-key protocol before production use.
- Routing metadata (node IDs, message size/timing, and direct-neighbor information) is not hidden from participating relays.

## Public World Chat (experimental)

World Chat is a separate public broadcast room inside **Conversations**; it is not part of one-to-one encrypted chats.

- Each post is signed with the author's persistent P-256 identity key. A receiver verifies the signature, key-derived node ID, timestamp, UUID, and text bound before saving or forwarding it.
- A post is intentionally **plaintext and public**. Every participating device and relay can read it. It is not end-to-end encrypted; do not post private or sensitive information there. Private conversations continue to use the existing E2E envelope path.
- A new post is gossiped to every connected direct neighbor. Each receiver relays it to its other neighbors with a maximum eight-hop TTL; a durable ID ledger suppresses duplicates across app restarts and alternate paths.
- The composer enforces **500 Unicode code points** (so a common emoji counts as one character). Each author is limited to five accepted posts per minute on a receiving device.
- Local storage keeps up to 1,000 recent posts for seven days and up to 50,000 seen post IDs for seven days. When a direct link comes up, devices exchange a small catch-up burst of their latest five posts to help newly connected mesh islands converge without flooding BLE.
- “World” means the reachable Morta Mesh network, not every Morta Mesh installation worldwide. Disconnected mesh islands cannot exchange posts until a radio path joins them. There is no GPS-based room or central internet relay.
- Background delivery is subject to Android process and radio restrictions. Keep Morta Mesh running for reliable relaying; this is not an always-on background daemon.

World Chat frames use `@MORTAMESH/2/WORLD` and remain distinct from `@MORTAMESH/2/PACKET` private encrypted envelopes.

## Private camera and media routing

The conversation composer includes **Take photo** and **Record video**. Capture happens in Morta Mesh, not the public camera app. CameraX temporarily writes into the app-private cache; the plaintext temporary file is encrypted into the vault and removed before transfer. The app does not add captured/received media to Android MediaStore, Downloads, or shared storage.

Transfer policy:

- **Up to and including 1 MiB:** Wi-Fi Direct only. If a valid Wi-Fi Direct route is unavailable, the encrypted copy stays local.
- **Over 1 MiB:** Wi-Fi Direct is preferred. Bluetooth LE may be used as fallback for items up to the current 100 MiB prototype limit.
- Video capture is capped at 60 seconds.

The recipient stores received bytes encrypted in its own Private Vault. Local vault encryption uses AES-GCM with Android Keystore, while the media bytes and transfer controls are also protected by end-to-end message encryption in transit.

## Private Vault

- Media files live in `filesDir/private_media_vault` as app-private `.mmv` files; they are not exported to public gallery directories.
- File contents and the vault index use AES-GCM with an Android Keystore key.
- A PIN is stored as a salted PBKDF2-HMAC-SHA256 verifier; the PIN is not stored as plaintext.
- The viewer is PIN-gated and locks when the app goes into the background.
- A recipient's local media copy is encrypted with that recipient device's own vault key.

## Build

Requirements: Android Studio with Android SDK Platform 35, JDK 17, Kotlin 2.0.21, and Android Gradle Plugin 8.7.3. Dependencies are declared in `app/build.gradle.kts` (AndroidX Core 1.15.0, CameraX 1.4.2, Activity KTX 1.10.1).

1. Open the repository root (the directory containing `settings.gradle.kts` and `app/`) in Android Studio. Use JDK 17, Android SDK Platform 35, Kotlin 2.0.21, Android Gradle Plugin 8.7.3, and Gradle 8.9. GitHub Actions installs Gradle 8.9 because a Gradle wrapper is not yet committed.
2. Install on at least three physical Android phones for mesh tests; two phones only validate direct messaging.
3. Grant Nearby devices/Bluetooth, Nearby Wi-Fi, Camera and (for video) Microphone permissions. Enable Wi-Fi and Bluetooth and keep Morta Mesh open on each device.
4. Connect phone A to B and phone B to C through Nearby. Once each physical link has exchanged identities, A should eventually see C under **Reachable through Morta Mesh**. Open A↔C and send a text/photo.
5. On the receiver, open **Peer info** and compare the identity fingerprint out of band before exchanging sensitive content.

## Release/readiness status

This is an implementation-stage prototype, **not a verified production release**. GitHub Actions has successfully completed `clean assembleDebug testDebugUnitTest` on the stabilization branch and uploaded a debug APK artifact. This verifies compilation and the JVM test task, not runtime behavior. BLE/Wi-Fi Direct tests, three-device routing, encryption interoperability, real-device testing, and independent cryptographic review remain required before release.

Media policy is based on type, never file size: text uses an available authenticated route, photos use Bluetooth LE, and videos use Wi-Fi Direct only. Photos are losslessly re-encoded to WebP/PNG when that produces a smaller file; otherwise the original is retained. Outbound media remains encrypted in the Private Vault queue, bound to its recipient, until an end-to-end receipt confirms the recipient's encrypted copy and vault index were saved. Prior unsent media is recovered only when chat history uniquely identifies its intended conversation. Vault media decryption and image decoding run off the main thread; photo previews are downsampled to limit memory pressure.

Known limitations: background radio behavior varies by OEM/API; videos wait while no Wi-Fi Direct route exists; large photos can be very slow over Bluetooth LE; transfers are bounded to 2 GiB and available disk space; replay protection is bounded to 50,000 IDs / seven days; no ratcheting/forward secrecy; TOFU still requires fingerprint verification; route hop claims remain manipulable by malicious neighbors; media consent/type/quota and shared-state threading need hardening; routing metadata remains visible; multi-device routing needs field testing.
