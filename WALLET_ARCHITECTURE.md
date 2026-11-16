# Morta Mesh Wallet Foundation — 0.8.0

## Scope

This milestone adds an independent wallet surface to Morta Mesh. It does not modify Mortalive.

## Implemented in this milestone

- Separate Wallet entry in Morta Mesh bottom navigation.
- Bitcoin Testnet4 wallet creation and 12-word BIP-39 recovery through the Bitcoin Dev Kit Android binding.
- BIP-84 native SegWit descriptors for external receive and internal change keychains.
- Recovery material encrypted with AES-256-GCM using a non-exportable Android Keystore key.
- Wallet secret ciphertext and BDK state are stored beneath Android no-backup app-private storage.
- Bitcoin Testnet4 compact-block-filter sync through BDK's Kyoto client.
- LAMA Android preparation: Testnet-default / Devnet-selectable network setting, Mainnet disabled, Base58Check address and network validation, exact integer lays conversion, asset metadata SHA-256 verification and fail-closed Rust-core transaction guards.
- Wallet and recovery phrase UI protected from screenshots and recent-app previews.
- Separate Bitcoin, Lama Coin and NFT UI boundaries.
- No wallet address, recovery phrase, balance, NFT holding or wallet identifier is sent to Mortalive or included in anonymous group submissions.

## Explicitly not enabled

- Bitcoin mainnet, real BTC, spending, signing and transaction broadcasting. This milestone is Testnet4 receive and sync only.
- LAMA wallet operations: actual key derivation, 24-word wallet creation/restoration, one-time receive-address allocation, UTXO discovery, balance sync, transaction building/signing/broadcast, header/Merkle verification and NFT ownership remain unavailable. The private Rust repository currently contains lamanode; the separately testable lamacore crate and UniFFI bindings have not been extracted. The node RPC is loopback-only and API-key protected, and the public TLS light-client gateway remains planned.
- Native NFT minting, transfers, sales and marketplace operations. The uploaded chain transaction/output model does not yet contain the native asset fields and validation described in the v3 decision plan.
- Mortalive account connection or NFT badge proof. Any future display or proof must be a separate, explicit opt-in and should not reveal a reusable public wallet address by default.
- Cross-chain BTC/Lama swaps and shielded transfers.

## Wallet security boundaries

- Bitcoin Testnet4 and Lama Coin use different authenticated encryption scopes.
- The wallet's recovery phrase is never written into SharedPreferences, logs or the BDK database by this implementation. The new LAMA helpers do not create or store a LAMA phrase or secret.
- The BDK wallet database and peer cache are stored under no-backup app-private storage.
- Missing Keystore material or unreadable ciphertext must fail closed. The app must not silently replace a missing encryption key or overwrite a wallet file.
- Android Keystore hardware-backed protection depends on device support; this implementation does not claim that every device has a hardware-backed key.
- The recovery phrase is shown once at creation for the user to back up offline. Strings are immutable in the JVM, so the application minimizes their lifetime but cannot guarantee deterministic zeroization of every in-memory copy.
- This is an experimental Testnet4 foundation, not an audited production wallet. The app intentionally has no send flow.

## Follow-up work

1. Freeze and publish final network/genesis identifiers after the planned final genesis re-mine; keep Android network/address test vectors synchronized with Rust params.
2. Extract and test the Rust lamacore crate with no tokio/sled dependencies, then generate and validate UniFFI Android bindings. Do not duplicate desktop wallet file formats or cryptography in Kotlin.
3. Build a public TLS read-only gateway for headers, proofs, UTXOs, revealed-address checks, asset records and transaction broadcast. Never put node API keys in the APK.
4. Connect the existing testnet/devnet asset and NFT rules to the light-client/indexer boundary; verify metadata hashes before untrusted metadata is displayed.
5. Add Bitcoin transaction construction and broadcast only after fee handling, full transaction preview, explicit confirmation, recovery testing and independent security review.
6. Test Keystore key loss, reinstall, app-data restore behavior, SQLite recovery, seed restore on a second device, Testnet4 peer connectivity and network interruption.
7. Conduct independent mobile-wallet and cryptography review before any wallet holds real value.

## Product boundary

Morta Mesh owns self-custody, wallet keys, Bitcoin/Lama network clients and the NFT ownership experience. Mortalive owns social identity, groups, DMs, Talk, feed and companions. The products may later interoperate through a narrow documented interface, but wallet use must not require a Mortalive account and Mortalive must not receive wallet secrets or financial history.
