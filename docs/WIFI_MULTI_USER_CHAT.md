# Multi-person Wi-Fi Direct chat

Status: **implemented in a draft PR; JVM checks and simulation are not physical-device verification.**

## Session model

One Wi-Fi Direct group owner hosts a star topology. The host can have up to four other clients (host plus up to four people). This is a conservative app cap, not a promise that every Android/OEM combination supports that many peers. Clients exchange encrypted mesh messages through the existing host relay; the host relays ciphertext and does not decrypt end-to-end payloads. Photos and videos retain their existing direct-recipient media rules.

## Session behavior

- Requests and accepts are controlled over an authenticated Bluetooth link.
- Simultaneous cross-requests use a deterministic node-ID tie-break so only one phone creates a group.
- If a host is creating a group, accepted joiners are queued. Credentials are sent only after Android reports the group formed.
- Pending invitations reserve capacity and expire after two minutes.
- A host does not tear down a group because one joiner fails while other people are connected, invited or queued.
- Authenticated Wi-Fi hello must match an approved logical node ID before the host registers that peer. Registration enforces the client cap.
- A transient `groupFormed=false` callback is rechecked after a grace period before sockets are closed.
- An idle host group is removed after approximately two minutes when there are no connected clients, approvals or queued invitations.
- Automatic video groups are not released while a chat socket is open.

## Known limitations and device checks

A phone can participate in only one Wi-Fi Direct group at a time. If a phone is already a guest and a third phone asks it to host, one session may win and the other request can receive busy; retry is required. Group-size capability is device-dependent, and the four-client cap is an estimate. Relay behavior, socket lifecycle and reconnects still need physical tests.

Test with three to five phones: host B then add C; confirm B and C can message through A; remove one client and ensure remaining clients stay connected; test full capacity, simultaneous requests, requests arriving during group creation, failed-join preservation, host departure and idle cleanup. Capture `MortaMesh-WiFi` logs from both sides for failures.
