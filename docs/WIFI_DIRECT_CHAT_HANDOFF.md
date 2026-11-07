# Wi-Fi Direct Chat Handoff

## Scope

This change separates the nearby chat transports:

- Bluetooth LE (BLE): discovery fallback, identity/control plane, and an encrypted request/accept exchange.
- Wi-Fi Direct: the actual data plane for a chat session after both users approve the request.

A Wi-Fi peer appearing in Android's peer list is discovery only. It does not mean that a P2P group, IP route, TCP socket, or authenticated chat exists.

## Why BLE behaves differently

The current BLE flow can be initiated by one phone. It starts a GATT connection and queues the first encrypted text while the other phone's app is open and advertising its GATT service. The recipient does not have to manually tap Connect first. The remote device is using the same GATT link; its UI may only display its connected state once its local GATT callbacks/notification subscription complete.

Wi-Fi Direct needs a separate group negotiation. One phone must create or own a P2P group and the other must join it before the TCP socket can be opened. Discovery alone cannot send chat messages.

## What changed in PR #8

1. Wi-Fi Direct discovery now publishes and reads a DNS-SD TXT record carrying the Morta Mesh node ID, a short identity hint, and display name.
2. The BLE scan response includes a short stable hint. The app uses it to associate the Wi-Fi peer with the corresponding BLE device, while the full node ID comes from Wi-Fi DNS-SD.
3. A Wi-Fi row is actionable only when its Morta Mesh identity has been resolved. Tapping it sends an E2E-encrypted wifi_chat_request control payload over the direct BLE link.
4. The recipient sees a short toast. It is not an AlertDialog, system notification, or front-screen popup. A quiet in-app request card appears under Nearby and Chats with Accept/Decline; the queue is capped at four and duplicate toast spam is throttled.
5. On acceptance, the requester creates a temporary WPA2 Wi-Fi Direct group using a random SSID/passphrase. It sends those credentials over the encrypted BLE control link. The recipient joins with WifiP2pConfig.Builder.
6. Once the P2P group forms, the existing TCP socket performs the existing mutual signed-identity and nonce proof handshake. Only after the handshake succeeds is the chat opened.
7. Wi-Fi chats use a stable wifi:node:<node-id> conversation key and pin user chat text to the Wi-Fi socket. A dropped Wi-Fi socket does not silently fall back to BLE; a message remains in the composer and the user is asked to reconnect.
8. Request expiry, 40-second connection timeout, group cancellation/removal, and session cleanup prevent abandoned attempts from remaining stuck.

## Why the previous attempt failed

The older manual path tried WifiP2pManager.connect() directly from a generic requestPeers() result using the device address. Android's peer discovery result does not establish a link, and the transport address alone does not authenticate or reliably identify the Morta Mesh peer. The user observed a generic Wi-Fi P2P internal error before the chat socket stage.

The previous fix also prevented automatic discovery from racing the connection attempt, but this did not address the identity/bootstrap and group/session lifecycle. PR #8 changes the bootstrap: BLE carries the request and group credentials, while the P2P group is created and joined deliberately.

## Compatibility

- Credential-based WifiP2pConfig.Builder is available from API 29 (Android 10).
- On Android 8/9 (API 26–28), BLE chat remains available; the new credential-based Wi-Fi chat flow reports that it requires Android 10 or newer.
- Both phones need a build containing this protocol. Do not test with one phone on the older Wi-Fi handshake.

## Test procedure (two physical Android devices)

1. Install the same PR #8 build on both phones.
2. Enable Wi-Fi and Bluetooth; grant Nearby Wi-Fi and Bluetooth permissions. Keep Morta Mesh in the foreground on both phones.
3. Open Nearby on both and scan. Wait until the Wi-Fi Direct peer shows a Morta Mesh identity and the action text says it can request a Wi-Fi chat.
4. On phone A, tap phone B's Wi-Fi Direct row once.
5. On phone B, verify a short toast appears. Open Nearby or Chats and verify the non-modal Wi-Fi Direct request card appears. Accept it.
6. Verify both phones transition to the chat only after the mutual Wi-Fi socket identity handshake completes.
7. Send text in both directions. Confirm the chat header says Connected · Wi-Fi Direct · E2E encrypted.
8. Disable Wi-Fi on one phone during the session. Sending should fail visibly and should not deliver over BLE. Re-enable Wi-Fi and start a new accepted session.
9. Separately confirm Bluetooth chat still works as before.

## Remaining verification / known limits

- GitHub Actions verifies compilation, JVM tests, and APK packaging; it cannot prove P2P interoperability across phone chipsets and OEM Android builds.
- The actual DNS-SD TXT identity callback, createGroup()/credential connect(), IP provisioning, TCP bind/connect, and handshake still require physical-device verification on the two target phones.
- If the Wi-Fi row remains at identifying Morta Mesh peer, inspect DNS-SD TXT callbacks and BLE hint matching.
- If the request is delivered and accepted but the session times out, inspect createGroup/connect result codes, connection-info broadcasts, group-owner IP, server bind, and socket handshake.
- Do not treat a visible Wi-Fi peer as a connected chat. The chat is connected only once the authenticated TCP socket is registered in wifiWriters.