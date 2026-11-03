# Morta Mesh Wallet Foundation — 0.8.0

## Scope

This milestone adds an independent wallet surface to Morta Mesh. It does not modify Mortalive.

## Implemented in this milestone

- Separate Wallet entry in Morta Mesh bottom navigation.
- Bitcoin testnet wallet creation and 12-word BIP-39 recovery through the Bitcoin Dev Kit Android binding.
- BIP-84 native SegWit descriptors for external receive and internal change keychains.
- Recovery material encrypted with AES-256-GCM using a non-exportable Android Keystore key.
- Wallet secret ciphertext and BDK state are stored beneath Android no-backup app-private storage.
- Bitcoin testnet compact-block-filter sync through BDK's Kyoto client.
- Wallet and recovery phrase UI protected from screenshots and recent-app previews.
- Separate Bitcoin, Lama Coin and NFT UI boundaries.
- No wallet address, recovery phrase, balance, NFT holding or wallet identifier is sent to Mortalive or included in anonymous group submissions.

## Explicitly not enabled

- Bitcoin mainnet, real BTC, spending, signing and transaction broadcasting. This milestone is testnet receive and sync only.
- Lama Coin wallet operations. The supplied Rust tree is a desktop node/wallet application, not an Android library. It must be packaged behind a reviewed Android-native boundary and tested before the app can generate Lama addresses or handle Lama funds.
- Native NFT minting, transfers, sales and marketplace operations. The uploaded chain transaction/output model does not yet contain the native asset fields and validation described in the v3 decision plan.
- Mortalive account connection or NFT badge proof. Any future display or proof must be a separate, explicit opt-in and should not reveal a reusable public wallet address by default.
- Cross-chain BTC/Lama swaps and shielded transfers.

## Wallet security boundaries

- Bitcoin testnet and Lama Coin use different authenticated encryption scopes.
- The wallet's recovery phrase is never written into SharedPreferences, logs or the BDK database by this implementation.
- The BDK wallet database and peer cache are stored under no-backup app-private storage.
- Missing Keystore material or unreadable ciphertext must fail closed. The app must not silently replace a missing encryption key or overwrite a wallet file.
- Android Keystore hardware-backed protection depends on device support; this implementation does not claim that every device has a hardware-backed key.
- The recovery phrase is shown once at creation for the user to back up offline. Strings are immutable in the JVM, so the application minimizes their lifetime but cannot guarantee deterministic zeroization of every in-memory copy.
- This is an experimental testnet foundation, not an audited production wallet. The app intentionally has no send flow.

## Follow-up work

1. Reconcile Lama Coin's name, ticker, network magic, genesis timestamp/hash/nonce and fresh-node reproducibility. The supplied merge notes say fresh nodes cannot reproduce the described genesis because nonce and timestamp are not embedded.
2. Package the Rust node/wallet behind a separately tested Android-native interface. Do not reuse the desktop wallet file format in the Android app.
3. Publish the final Lama Coin consensus specification: typed transactions, chain ID in signature hashes, 32-byte address hash, asset-aware UTXOs, burn accounting and signature scheme.
4. Implement native asset outputs and deterministic NFT ownership/mint/transfer validation, then build indexer and marketplace APIs.
5. Add Bitcoin transaction construction and broadcast only after fee handling, full transaction preview, explicit confirmation, recovery testing and independent security review.
6. Test Keystore key loss, reinstall, app-data restore behavior, SQLite recovery, seed restore on a second device, testnet peer connectivity and network interruption.
7. Conduct independent mobile-wallet and cryptography review before any wallet holds real value.

## Product boundary

Morta Mesh owns self-custody, wallet keys, Bitcoin/Lama network clients and the NFT ownership experience. Mortalive owns social identity, groups, DMs, Talk, feed and companions. The products may later interoperate through a narrow documented interface, but wallet use must not require a Mortalive account and Mortalive must not receive wallet secrets or financial history.
