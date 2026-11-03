# Mortalive × Morta Mesh: Anonymous Group Submission Integration Draft

**Status:** Engineering proposal only. Mortalive has not been modified. This document is a handoff for the engineers already working on Mortalive.
**Mesh implementation branch:** `feature/anonymous-mesh-group-publishing`
**Morta Mesh app version on this branch:** 0.7.0 (versionCode 8)

## 1. Product context

Morta Mesh is an offline-first Android nearby messenger. It works without a Mortalive account, signup flow, or central chat server. Nearby devices discover one another over Bluetooth LE and Wi-Fi Direct; connected devices can relay supported messages across a multi-hop mesh while the app is running.

Mortalive is a separate social/chat platform and web/app product. Its product areas include human profiles and posts, messaging and talk experiences, creator/developer surfaces, and AI companion personas (for example companion, girlfriend, and boyfriend agents). The proposed connection here is specifically about sending an anonymous item created inside Morta Mesh into a selected Mortalive group conversation. It is not a request to change Mortalive's existing website, companion, or chat implementation now.

The requested behavior:
- A Morta Mesh user submits text and/or a photo, video, or audio attachment without creating or logging into a Mortalive account.
- The submission travels through opted-in nearby mesh devices as encrypted data.
- Mortalive temporarily decrypts it inside a restricted moderation boundary for the required violence/graphic-content check.
- Nothing is delivered to the destination group unless moderation approves it.
- On approval, the media/content is encrypted for the destination Mortalive group and appears as a post/message in that group conversation.
- On rejection or a review hold, it is not delivered to the group.

## 2. Important privacy boundary

This is not end-to-end encryption from the originating device straight to group members, because the moderation service must inspect the submitted content.

The intended stages are:
1. **Origin device:** encrypt the complete submission to Mortalive's published ingestion public key.
2. **Mesh relays:** store and forward only ciphertext and a signed routing manifest. Relays cannot view the text/media or discover the destination group ID.
3. **Mortalive moderation boundary:** unwrap/decrypt the submission in a restricted service, validate it, run violence/graphic-content checks, and quarantine uncertain results.
4. **Approved group delivery:** re-encrypt the approved content using Mortalive's group-message encryption/publishing mechanism, then deliver a normal group message/attachment. Group members decrypt using the group's normal member-side keys.
5. **Rejected/held:** do not create a group message or expose a publicly accessible media URL.

The user should be told that the content is encrypted while crossing the mesh, but is readable to the authorized Mortalive moderation service during review. Do not label this flow "sender-to-group E2E".

The relay-visible manifest contains an anonymous transfer ID, media type, byte counts, timestamps/expiry, ingestion key ID, ciphertext hash and signed chunk hashes. The group identifier is inside the encrypted payload, not the relay-visible manifest. Relays may still observe transfer timing, approximate size, neighboring devices and transport metadata.

## 3. What is present in Morta Mesh 0.7.0

### Implemented in this branch
- A separate **Anonymous group submission** entry inside Conversations.
- A draft composer for a destination group ID/submission code, optional text, and optional photo/video/audio attachment.
- An encrypted local draft queue. Metadata and attachments are encrypted at rest with AES-GCM using an Android Keystore key.
- Draft validation: supported MIME allowlist, 5,000 Unicode code points of text, 100 MiB per attachment, 20 drafts and 250 MiB of total draft attachment storage. Drafts expire after 72 hours.
- A sender-to-ingestion package builder. It creates a random per-submission AES-256-GCM key, wraps it to a supplied P-256 ingestion public key using ephemeral P-256 ECDH + HKDF-SHA256 + AES-GCM, and signs the manifest with a fresh per-submission P-256 ECDSA key.
- The group ID and content are inside the encrypted payload. The signed manifest includes whole-file and per-chunk SHA-256 hashes, protocol limits, timestamps, key ID, encryption metadata and an ephemeral origin public key.
- A separate `@MORTAMESH/2/ANON` wire namespace. It chunks the opaque encrypted package into 96 KiB chunks and binds every chunk to a digest covered by the origin-signed manifest.
- A bounded persistent store-carry-forward queue for opaque encrypted relay packages. Initial bounds: up to 20 transfers, 250 MiB, eight mesh hops, and an expiry of up to 72 hours.
- Wi-Fi Direct is used for anonymous media payload chunks. BLE may carry discovery/short control information, not the bulk submission body.
- **Settings → Peer Mode · encrypted carry** is a separate opt-in for carrying other people's submissions. Sending the device owner's own submission does not require this setting. Peer-mode consent is announced using a signed record and is accepted only after the transport is bound to the same authenticated Mesh node identity. Relays only accept/forward received anonymous package frames when their own carry opt-in is on and the next peer has opted in.
- A per-neighbor admission limiter bounds new relay jobs (eight distinct transfers / 250 MiB per hour by default); additional chunks for an already-admitted transfer remain allowed.
- Draft file encryption/import and large relay forwarding use background executors rather than the Android UI thread.
- Settings displays relay cache size/count and provides a user-controlled clear-cache action.
- Existing private chat/media and public World Chat paths remain separate and unchanged.

### Deliberately not implemented because Mortalive's contract is not available
- A real Mortalive HTTP upload endpoint or endpoint URL.
- A production ingestion public key/key-discovery endpoint or key-rotation contract.
- Upload session creation, chunk upload, finalize, and status polling.
- Internet-gateway upload from a relay device.
- Mortalive-side decryption, moderation, approval/rejection, human review, and appeal.
- Re-encryption using Mortalive's group keys and insertion into a group DM.
- End-user progress states such as uploaded, processing, approved, published, rejected, or needs review.

The Mesh UI therefore saves local encrypted drafts. It does not claim that a draft has been sent, reviewed, or published. A future integration hook can stage a draft as an encrypted package when Mortalive supplies its pinned ingestion public key and key ID, but no UI action calls that hook until the service contract exists.

## 4. Proposed Mortalive engineering work

This is a draft contract, not a claim about existing Mortalive APIs.

### A. Group-scoped anonymous submission authorization
Provide a way for a person with no Mortalive account to select/authorize one destination group. A group owner/admin could issue a revocable submission code or capability link scoped to:
- one destination group;
- permitted content types (text/photo/video/audio);
- optional expiry and usage quota;
- maximum payload size and rate limits.

A private group should not become publicly writable merely because a group ID is guessable. A public group could offer an explicit anonymous-submission capability. The capability should be random, revocable, and group-scoped. Do not require a Mortalive login for the submitting Mesh user.

### B. Ingestion key publication and rotation
Expose a versioned, authenticated-over-TLS way for clients to fetch the current ingestion encryption key:
- key ID;
- P-256 public key in X.509 SubjectPublicKeyInfo encoding;
- algorithm identifiers;
- activation/retirement times;
- signature or pinning mechanism for key authenticity;
- overlap window for key rotation.

The current Mesh package format expects a P-256 ingestion public key and key ID. The backend must confirm/standardize this cryptographic contract before production use. Prefer a reviewed HPKE profile if the backend security team selects one; any change must be coordinated with the Android packager.

### C. Resumable upload API
Suggested logical operations:
1. Create/resume a submission session with the signed manifest and the group-scoped capability.
2. Upload an encrypted chunk idempotently by transfer ID and chunk index.
3. Finalize only after all chunks, manifest signature, signed chunk hashes and whole-file hash verify.
4. Return an upload receipt and processing ID.
5. Expose an authenticated status lookup so the Mesh sender can later learn whether it was published, rejected, held, or expired.

A possible API shape is `create session → PUT chunk(index) → finalize → status`; endpoint paths and request schemas are intentionally left for the Mortalive API owners to define. The same API should support direct internet upload from the origin and upload by an opted-in gateway carrier.

### D. Trusted moderation pipeline
The server should:
- validate capability scope, expiry, quotas, MIME, byte counts, timestamps, replay/duplicate transfer IDs and cryptographic integrity;
- unwrap the data key only inside a restricted processing environment;
- safely decode content in a sandbox;
- run the required graphic violence/gore checks on images/video frames and appropriate analysis on audio/video audio tracks;
- quarantine uncertain results for human review;
- make approval/rejection decisions auditable;
- keep the original media inaccessible to group members until approval;
- delete or retain source plaintext only under a documented retention policy.

A successful upload is not approval. Moderation should return explicit states, at minimum: `processing`, `approved`, `needs_review`, `rejected`, `expired`.

### E. Encrypted group delivery
After approval, Mortalive should create a normal group message/attachment and deliver it through the existing group-chat event/message pipeline. The integration needs an explicit agreement with the group messaging/key-management owners:
- Which service is authorized to publish approved media into a group?
- How is the approved attachment encrypted using the group's active key epoch?
- How do current and newly joined group members receive the attachment key?
- How are group membership changes and key rotation handled?
- How is the moderation result linked to the group message so clients can audit that publication happened only after approval?

If group chat is strict E2E, do not solve this by silently giving the moderation worker every group's long-term secret. Prefer an explicitly authorized publishing principal/key-service flow that creates the group-compatible encrypted envelope without weakening member key isolation. The existing group client/server protocol owners must define this.

### F. Status and notification
Suggested client-facing states:
- `draft_saved` — stored only on the originating Mesh phone;
- `waiting_for_peer` — waiting for an opted-in Wi-Fi Direct relay;
- `relayed` — another device accepted encrypted chunks;
- `uploaded` — Mortalive accepted the complete ciphertext;
- `processing` — moderation is running;
- `needs_review` — held for human review;
- `published` — approved and delivered into the selected group;
- `rejected` — no group message was created;
- `expired` — submission expired before completion.

The origin should retrieve status using a random per-submission receipt/capability, not a Mortalive account. The status token must not reveal the group ID to nearby relays.

### G. Abuse controls while remaining anonymous
The current Mesh origin manifest uses a fresh ephemeral key per submission. That provides unlinkability at the protocol identity layer, but it does not itself prevent spam. Mortalive should define:
- group-scoped submission capabilities and quotas;
- rate limiting and duplicate detection;
- abuse/reporting and revocation;
- optionally privacy-preserving anonymous quota tokens (for example, blind-signed one-time quota tokens) if server-side limits must not be tied to a stable identity;
- file retention and deletion;
- safe audit metadata that does not unnecessarily identify the submitter.

"Anonymous" should be described precisely: anonymous to group members, anonymous from a Mortalive account requirement, and not necessarily unobservable to the network operator. A gateway and the server may observe timing, carrier, transfer size and network metadata.

## 5. End-to-end workflow once the Mortalive contract exists

1. The user opens Morta Mesh → Conversations → Anonymous group submission.
2. They select a group-scoped submission code, type optional context, and optionally attach a photo/video/audio file.
3. Mesh validates the group capability and content bounds, then saves an encrypted local draft.
4. Mesh fetches the current authenticated Mortalive ingestion key and creates an encrypted package. The group ID remains inside the ciphertext.
5. If the origin has a validated route to the Mortalive API, it uploads directly. Otherwise, it offers the ciphertext package to nearby devices that have explicitly opted into encrypted carry.
6. Each opted-in relay stores ciphertext only, resumes missing chunks when peers reconnect, and forwards to another opted-in peer. A separate gateway opt-in permits a device to use its own validated internet connection for upload; this second consent has not yet been implemented.
7. Mortalive validates the upload/capability, decrypts in its restricted moderation boundary and runs the violence check.
8. Rejected/held content never appears in the group. Approved content is encrypted for the selected group and delivered as a normal group message.
9. The origin later retrieves the final state using a submission receipt. It can distinguish relay storage from successful upload and publication.

## 6. Non-goals and compatibility

- Do not route ordinary private one-to-one chat attachments through this anonymous publishing protocol.
- Do not change the existing private-media policy: private photos are direct BLE only; private videos are direct Wi-Fi Direct only; an unavailable recipient leaves them queued.
- Do not merge anonymous group submissions into public mesh World Chat. World Chat is a separate, intentionally public, signed plaintext peer-to-peer text room and has no central moderation.
- Do not change Mortalive's website, companion agents, creator/developer pages, or existing messaging implementation as part of this Mesh branch.
- Do not claim the new path is production-ready until the backend, key-management, moderation, physical-device relay and security reviews are complete.

## 7. Decisions for the Mortalive owners

1. Confirm the exact destination: selected group chat only, or should the submission target a one-to-one DM as well?
2. Define who can issue a group submission capability and its revocation/expiry behavior.
3. Approve the encryption/key-rotation profile and publish a key-discovery contract.
4. Define the resumable ingestion API and status receipt.
5. Define violence thresholds, human-review requirements and media retention.
6. Agree how an approved attachment becomes a group-E2E message without weakening member-key isolation.
7. Decide whether a mesh relay with validated internet is allowed to upload packages under a separate gateway opt-in and what data/battery limits apply.

**No Mortalive code or configuration was changed for this branch.**
