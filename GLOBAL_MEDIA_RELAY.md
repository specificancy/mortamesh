# Anonymous Mesh → Mortalive Group Delivery

**Status:** Mesh-side draft, encrypted local staging and ciphertext relay foundation are on branch `feature/anonymous-mesh-group-publishing`. Mortalive API, moderation and group delivery are not implemented. This document is a draft for the Mortalive engineers; no Mortalive code has been changed.

## Product behavior

A user creates an anonymous group submission inside Morta Mesh without a Mortalive account. The user provides a destination group ID or group-scoped submission code, optional text, and optional photo/video/audio. The app saves an encrypted local draft. When Mortalive supplies a real ingestion key and API contract, the client can seal the content, hide the destination inside the encrypted payload, and offer the ciphertext to opted-in nearby relays.

The relay path is store-carry-forward, not live streaming:
1. The origin seals the submission for Mortalive's ingestion public key.
2. If the origin is offline, opted-in devices store and forward opaque encrypted chunks over Wi-Fi Direct.
3. A future opted-in internet gateway uploads the encrypted package to Mortalive. The gateway component is not implemented because the Mortalive upload API is not defined.
4. Mortalive validates the capability/manifest and decrypts in a restricted moderation boundary.
5. Mortalive checks violence/graphic content. Uncertain cases can be held for human review.
6. Only approved submissions are re-encrypted and inserted into the selected Mortalive group conversation.
7. Rejected/held items do not appear in the group.

## Encryption boundary

The origin uses a fresh AES-256-GCM data-encryption key. The key is wrapped to a supplied P-256 ingestion key using ephemeral P-256 ECDH, HKDF-SHA256 and AES-GCM. A fresh per-submission P-256 ECDSA key signs the manifest. The manifest includes expiry, content type, byte count, ingestion key ID, whole-ciphertext SHA-256 and per-chunk SHA-256 hashes.

Relays cannot decrypt or preview content. The group ID and submission content live inside the encrypted payload, so the relay-visible manifest does not reveal the destination group.

Mortalive must decrypt to run moderation. Consequently, this is sender-to-ingestion encrypted publishing with opaque peer relays, **not** origin-to-group-member E2E. After approval, the approved attachment must be encrypted using Mortalive's group message/key-management mechanism. The group-chat security owners must design the authorized publishing principal so that moderation does not weaken group-member key isolation.

## Current Mesh implementation

- Conversations → Anonymous group submission composer.
- Text maximum: 5,000 Unicode code points.
- Allowed media: JPEG, PNG, WebP, MP4, 3GPP video, and bounded audio MIME types.
- Per-attachment maximum: 100 MiB.
- Local encrypted draft queue: up to 20 drafts / 250 MiB total attachment bytes.
- Draft expiry: 72 hours.
- Separate wire namespace: `@MORTAMESH/2/ANON`.
- Chunk size: 96 KiB; maximum 1,200 chunks; maximum frame 380 KiB; maximum hop TTL 8.
- Per-chunk hashes are covered by the signed manifest; completed ciphertext is checked against signed total size and full SHA-256.
- Relay cache: at most 20 transfers / 250 MiB. Entries expire with the package.
- Draft encryption/import and large relay forwarding run on background executors.
- **Settings → Peer Mode · encrypted carry** explicitly enables carrying other people's submissions; sending your own submission does not require this setting. Consent is signed and bound to the transport's authenticated Mesh node identity.
- A per-neighbor admission limiter allows up to eight distinct incoming transfer IDs / 250 MiB per hour by default; subsequent chunks for an admitted transfer continue through.
- Anonymous body chunks are carried over Wi-Fi Direct. BLE remains for short control/discovery traffic.
- Settings shows relay cache usage and provides a clear-cache option.

Existing private photos/videos remain direct-recipient-only (photos BLE; videos Wi-Fi Direct) and never fall back to this anonymous relay path. Public World Chat is a separate intentionally public, plaintext mesh text room and is not the Mortalive submission channel.

## Not implemented yet

The app does not currently upload or publish a submission. There is no Mortalive ingestion URL, key discovery/rotation, group-scoped submission capability, resumable server upload, gateway upload, moderation status API, group encryption adapter, or final group message event. The composer saves a private encrypted draft only. A future staging hook requires a real Mortalive P-256 ingestion key and key ID.

## Mortalive engineering proposal

Mortalive owners need to define:

1. A revocable, expiring group-scoped anonymous submission capability. A guessable group ID alone must not authorize posting to a private group.
2. An authenticated ingestion key publication and rotation contract.
3. Resumable create-session / upload-chunk / finalize / status operations for both origin upload and a separately opted-in gateway carrier.
4. Server verification of manifest, expiry, replay, hashes, MIME, size and capability quotas.
5. Isolated decryption, safe media decoding, violence/graphic-content checks, human review and explicit moderation result states.
6. An approved-only publication mechanism that encrypts the attachment using the group's normal key-management protocol without giving the moderation worker uncontrolled access to group secrets.
7. Submission receipts/status lookup that does not require a Mortalive account.
8. Anonymous abuse controls and retention: group-scoped quotas, revocation, duplicate checks, reporting, deletion and potentially privacy-preserving quota tokens.

See [MORTALIVE_INTEGRATION_DRAFT.md](MORTALIVE_INTEGRATION_DRAFT.md) for the full proposal and [LATEST_APP_WORKFLOW_AND_CHANGES.txt](LATEST_APP_WORKFLOW_AND_CHANGES.txt) for the complete current app workflow and changes.
