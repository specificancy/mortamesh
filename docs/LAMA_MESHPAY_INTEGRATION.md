# LAMA MeshPay in MortaMesh

Status: **MP0 testable scaffolding only. No value moves in this build.**

## Non-negotiable boundary

- Rust `lamacore` owns key derivation, ML-DSA signing, sighash, canonical transaction serialization, validation, proof verification and fee calculation.
- Kotlin must not implement LAMA cryptography or consensus. `LamaCoreUnavailable` fails loudly until the real Rust library and UniFFI bindings are included.
- The private node RPC is loopback-only and API-key protected. Never ship its API key in the Android APK. A public TLS light-client gateway is a separate requirement.
- Testnet is the default; Devnet is available for development; Mainnet remains disabled.

## Included in MP0

- Network identifiers and mainnet restriction.
- Base58Check address structure, checksum, active signature-scheme and wrong-network checks. These checks do not prove ownership, balance or unspent status.
- Exact integer lays parsing and formatting; no floating-point money arithmetic.
- Asset metadata SHA-256 verification before later strict schema validation and rendering.
- A proposed Rust-core interface that fails closed until the real library is integrated.
- A bounded in-memory carry primitive that requires Rust-core validation for every transaction and rejects on capacity rather than silently evicting an earlier signed payment.
- A provisional LAMA-PAY-v1 memo helper, not connected to a live payment flow.

## Explicitly not included

- No LAMA wallet creation, phrase restore, key derivation, fresh receive address, UTXO discovery, balance query, send preview, signing or transaction serialization.
- No payment request/send UI and no signed transaction handoff over BLE or Wi-Fi Direct.
- No persistent carry queue, gateway URL configuration, proof/confirmation UI, or broadcast service.
- No NFT create/mint/transfer, ownership indexer or marketplace.

The carry primitive is not connected to chat, media, anonymous submissions, World Chat or any network transport. A transaction stored locally or accepted by a future gateway must never be described as confirmed or paid. Offline double-spend protection is impossible; a payment remains a promise until the chain confirms it.

## Next gates

1. Extract and test the Rust `lamacore` crate without `tokio` or `sled`; generate UniFFI bindings for Android.
2. Build a public TLS read-only gateway for headers, Merkle proofs, UTXOs, revealed-address checks and signed-transaction broadcast. Do not expose private node RPC credentials to mobile clients.
3. Validate the light-client trust model against at least two independent servers and hostile/malformed responses.
4. Only then implement wallet create/restore/receive, balances, send-preview/confirmation and testnet-only signing.
5. Add payment handoff and opt-in carry as a separate milestone with replay, expiry, capacity, privacy and three-device tests.

No real-value or production-wallet claim is appropriate before the chain is frozen, implementation is complete, and independent review and device testing are done.
