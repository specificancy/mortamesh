# Global Media Relay — Proposed Architecture

**Status:** Design specification; not implemented or connected to a Mortalive backend yet.  
**Scope:** An optional public-video publishing route from Morta Mesh into Mortalive Global Chat. It is separate from private chat attachments and the existing public text World Chat protocol.

## 1. Product behavior

A user records or selects a video and chooses **Send to Global Chat**. The app should deliver it to Mortalive even when the originating phone temporarily has no internet, by carrying an opaque encrypted upload package across nearby, opted-in devices until one reaches the internet.

The path is:

1. Create and validate the upload package on the originating phone.
2. If the origin has a validated internet connection, upload directly to Mortalive.
3. Otherwise discover nearby devices that explicitly support and have enabled Global Relay.
4. Send the encrypted package to one eligible relay. That relay stores it temporarily and carries it while it moves.
5. When a relay has validated internet, it uploads the package to Mortalive as a carrier. If it is still offline, it can pass the package to another eligible peer when one is encountered.
6. Mortalive verifies the origin signature and upload metadata, scans and moderates the video, and only then publishes an approved video as a Global Chat attachment.
7. The origin receives the final moderation/publication state the next time it reconnects to the mesh or internet.

A relay hop is **store, carry, forward**. It is not a live multi-hop video stream. The video is copied as encrypted chunks and can survive the sender leaving range or closing the chat, subject to Android process limits and the queue expiry policy.

## 2. Two distinct encryption boundaries

The phrase “encrypted video” needs a precise meaning in this feature:

- The video is encrypted on the originating device with a fresh per-transfer data-encryption key (DEK). Each chunk uses authenticated encryption (for example AES-256-GCM with a unique nonce and transfer/chunk metadata as associated data).
- The DEK is wrapped to a pinned Mortalive media-ingestion public key using a reviewed public-key envelope scheme such as HPKE. The key identifier is included in the signed manifest.
- Every relay stores and forwards only ciphertext. A relay cannot preview, decode, transcode, or moderate the video.
- Mortalive can unwrap the DEK in its restricted ingestion/moderation environment, because moderation must inspect the actual media before it can be published. The upload uses TLS in transit and encrypted server-side storage.

**Important privacy disclosure:** this is *sender-to-Mortalive encrypted publishing with opaque peer relays*, not end-to-end encryption between the sender and a private recipient. Mortalive's moderation service can access the submitted video. Private one-to-one media must continue using the existing recipient-keyed E2E path and must never be silently routed through this public publishing pipeline.

## 3. Connectivity and route selection

Do not use a weak “Wi-Fi icon is visible” check. The app should:

1. Check Android's active network capabilities for INTERNET and VALIDATED.
2. Make a short authenticated reachability check against the configured Mortalive ingestion endpoint. Captive portals, DNS failures, TLS errors, and unreachable API hosts count as offline for this upload.
3. If the service is reachable, try direct upload first and resume by chunk. If the attempt fails before the server has accepted the complete package, preserve the encrypted queue item and move to relay discovery.
4. If the origin is offline, discover nearby peers advertising the Global Relay capability.
5. Select an eligible relay using link quality, available storage, recent transfer success, and policy constraints. RSSI is only a rough proximity signal; it is not proof of distance or trust.

For large video payloads, use Wi-Fi Direct for each physical hop. BLE may be used for discovery, capability negotiation, and small control messages, but should not carry the video body in the first release.

## 4. Peer Mode: explicit opt-in

A device must not relay other people's uploads merely because it has the app installed. Add two separate, revocable settings:

- **Carry encrypted uploads:** allow the device to temporarily store and forward opaque Global Chat upload packages over nearby links.
- **Use my internet as a gateway:** allow the device to upload a carried package to Mortalive when its own internet is available.

The second permission is separate because it may consume mobile data and use the device owner's internet connection. The user should be able to choose Wi-Fi only or Wi-Fi plus mobile data, and optionally require charging / a minimum battery level.

A peer is eligible only when all required conditions hold: the relevant permission is enabled, the app is running and has the required radio permissions, the device has enough reserved storage, queue limits are not exceeded, the transfer has not expired, and the transport is currently usable. If no eligible peer is found, the origin keeps the item queued and retries later. It must not silently fall back to sending through a non-consenting device.

Turning Peer Mode off stops accepting new relay jobs and allows the user to delete already queued relay data. Relays must not gain ownership or publication credit: the original author remains the author; the carrier is recorded only for abuse investigation and delivery accounting.

## 5. Signed transfer package

Each upload has a stable random transferId and a canonical, origin-signed manifest containing at least:

- protocol/version and media-ingestion key ID
- transfer ID, origin node ID, and authenticated Mortalive account/author binding
- MIME type, byte length, chunk size/count, and whole-file SHA-256
- per-chunk integrity hashes or authenticated chunk metadata
- creation time, expiry time, and maximum hop count
- declared audience/target: global_chat
- origin signature over the canonical manifest

The origin signature prevents a relay from changing the author, target, expiry, or declared file identity. Mortalive verifies that signature against the registered origin key before accepting publication. A carrier authenticates separately to the upload API and is recorded as the last-mile uploader; it cannot impersonate the author.

Suggested first-prototype operating limits (tune after real-device measurements):

| Control | Initial value |
| --- | ---: |
| Maximum video duration | 60 seconds |
| Maximum global-upload size | 100 MiB |
| Chunk size | 256 KiB |
| Maximum relay hops | 8 |
| Queue expiry | 24 hours |
| Per-device relay cache | 250 MiB |
| Concurrent inbound relay jobs | 2 |

These are proposed global-publishing limits, not changes to the existing Private Vault's 2 GiB per-item cap. Enforce space reservations before accepting chunks. Persist transfer and chunk state so interrupted hops can resume without retransmitting verified chunks.

## 6. Relay lifecycle and duplicate protection

A relay should:

- Verify the manifest structure, origin signature format, declared bounds, expiry, and protocol before reserving storage.
- Store only encrypted chunks in an app-private relay cache; never decrypt them or write plaintext to gallery/shared storage.
- Track received chunk indexes and hashes, then forward missing chunks only.
- Keep a durable dedupe key based on origin node ID plus transfer ID. Repeated announcements must not create unlimited copies.
- Decrement a hop budget / record visited relay IDs so loops are bounded. Reject expired jobs and jobs with no remaining hops.
- Reserve the expected bytes and enforce per-job, per-origin, and device-wide quotas before accepting data.
- Delete relay cache data after a server receipt is authenticated, after expiry, or after the owner explicitly clears it. Do not delete the sender's original queued copy until the sender receives a valid final server receipt.
- Never send a success receipt merely because a nearby hop completed. Distinguish stored_by_relay, uploaded_to_server, moderation_passed, and published.

Relays should not promise always-on service. Android may suspend or kill background work and OEM radio policies vary. The first release should relay while Morta Mesh is active, then retry on app resume and connectivity changes. A foreground service and its user-visible notification can be considered later if justified and compliant with Android background-execution rules.

## 7. Mortalive ingestion and moderation

The backend should expose an authenticated, resumable ingestion flow. One workable API shape is:

- Create or resume an upload session using the signed manifest and carrier authentication.
- Upload chunks idempotently by transferId and chunk index.
- Finalize only after all chunks are present and integrity checks pass.
- Return a server receipt and a processing identifier.
- Expose an authenticated status endpoint so the author can retrieve the final state.

The server pipeline should run in this order:

1. Verify carrier authentication, origin signature, origin/account binding, timestamps, expiry, transfer ID uniqueness, size, MIME allowlist, codec/container validity, and complete-file hash.
2. Virus/malware scan and safe media decode in a sandbox with CPU, memory, time, and file-size limits.
3. Run content checks, including graphic violence/gore, sexual or nudity policy, child-safety abuse signals, self-harm, extremist/terror content, spam/manipulated media, and other categories defined by Mortalive's published rules.
4. Quarantine uncertain or high-risk results for a human moderation queue. Automated models should not be the sole final authority for borderline cases.
5. Publish only when all required checks pass. Create the Global Chat media attachment and broadcast the new post/message event only after the moderation state is approved.
6. Reject or keep restricted content that fails policy; do not expose a public media URL before approval. Provide a reason category and an appeal/report path where appropriate.

A successful upload is not the same as a successful publication. The server should return explicit states such as:

**queued_offline → relayed → uploaded → processing → approved → published**

or

**uploaded → processing → rejected / needs_review / expired**

Only **published** means other Global Chat users can view the video.

## 8. Global Chat integration

After approval, Mortalive creates a public Global Chat item containing the original author, media reference, thumbnail/transcode references, moderation state, and creation time. The media should be served through controlled CDN/object storage URLs. The item is not inserted into the existing plaintext mesh World Chat protocol automatically: that protocol is a separate experimental peer-to-peer text broadcast and has no central moderation service.

The client should show useful progress to the author:

- **Waiting for internet** — no validated route or eligible relay found.
- **Relayed by nearby device** — one or more peers accepted encrypted chunks.
- **Uploaded — under review** — server has the complete file; it is not public yet.
- **Published in Global Chat** — moderation passed and publication succeeded.
- **Needs review / Not approved / Expired** — no public item was created.

## 9. Abuse, privacy, and cost controls

- Require an authenticated Mortalive author identity or a deliberately designed, rate-limited guest publishing credential. The current Morta Mesh prototype has no sign-up or account-binding system, so this must be agreed with the Mortalive backend before implementation.
- Apply per-author upload caps, daily quotas, duplicate detection, rate limits, and carrier abuse controls.
- Keep relay metadata minimal. Relays necessarily learn transfer size/timing and the origin node identifier; document this. Do not expose filenames or thumbnails to relays unless needed.
- Never relay private-chat attachments, unknown file types, arbitrary archives, or executable payloads through this path.
- Do not charge a relay owner's mobile data without the explicit gateway permission and selected data policy.
- Make cancellation, expiry, revocation, deletion, reporting, and moderation appeal behavior explicit.
- Do not publish any media before moderation is complete.

## 10. Fit with the current Morta Mesh codebase

The current app already has BLE discovery, Wi-Fi Direct transport, logical node identities, signed protocol messages, bounded replay/deduplication controls, and encrypted local storage. Those can inform this feature, but the current implementation explicitly does **not** relay media: photos use direct BLE, videos use direct Wi-Fi Direct, and an unavailable recipient leaves media queued. That private-media policy must remain intact.

The current repository also has no Mortalive account integration, public internet upload client, ingestion endpoint, moderation service, or central Global Chat media event. Its INTERNET permission currently supports local Wi-Fi Direct TCP sockets; it is not evidence of a working Mortalive server connection.

Implementation should therefore be staged:

1. Agree the backend account binding, ingestion API, moderation policy, server public-key distribution/rotation, and Global Chat publication contract.
2. Add a separate GLOBAL_MEDIA protocol namespace and encrypted relay-cache/data model. Do not reuse or weaken private-media transfer controls.
3. Implement and test relay consent, admission quotas, resumable chunks, hop/expiry/dedupe behavior, and direct-upload fallback.
4. Implement the Mortalive ingestion client and authenticated gateway upload.
5. Add moderation status UI and publish only from the server-approved event.
6. Validate with at least three physical phones across offline islands, gateway arrival, interruption/resume, expiry, revocation, duplicate paths, low storage, metered data, and server rejection cases.

Until these stages and server integration are complete, this document describes the intended architecture only; it must not be represented in the app as a working feature.
