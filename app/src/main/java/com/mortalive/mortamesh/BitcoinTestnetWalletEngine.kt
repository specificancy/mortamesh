package com.mortalive.mortamesh

import android.content.Context
import org.bitcoindevkit.CbfBuilder
import org.bitcoindevkit.CbfClient
import org.bitcoindevkit.CbfNode
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.NetworkKind
import org.bitcoindevkit.Peer
import org.bitcoindevkit.Persister
import org.bitcoindevkit.ScanType
import org.bitcoindevkit.Wallet as BdkWallet
import org.bitcoindevkit.WordCount
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Bitcoin Testnet4-only adapter backed by the Bitcoin Dev Kit Android bindings. */
internal class BitcoinTestnetWalletEngine(context: Context) {
    data class Snapshot(
        val balanceSats: ULong,
        val blockHeight: UInt,
        val receiveAddress: String
    )

    private val appContext = context.applicationContext
    private val secretVault = WalletSecretVault(appContext)
    private val metadata = appContext.getSharedPreferences("wallet_public_metadata_v1", Context.MODE_PRIVATE)
    private val walletId = "primary"
    private val networkId = "bitcoin_testnet4"
    private val walletDirectory = File(appContext.noBackupFilesDir, "bitcoin-testnet4-wallet")
    private val databaseFile = File(walletDirectory, "bdk-wallet.sqlite3")
    private val syncDirectory = File(walletDirectory, "peer-sync")

    private val lock = Any()
    private var wallet: BdkWallet? = null
    private var persister: Persister? = null
    private var syncClient: CbfClient? = null
    private var syncNode: CbfNode? = null
    private var syncThread: Thread? = null
    private val stopping = AtomicBoolean(false)

    fun hasWallet(): Boolean = secretVault.exists(walletId, networkId)

    fun createNewTestnetWallet(): String {
        check(!hasWallet()) { "A Bitcoin Testnet4 wallet already exists on this device" }
        check(!databaseFile.exists()) {
            "A wallet database exists without its encrypted recovery material. It was preserved."
        }
        val mnemonic = Mnemonic(WordCount.WORDS12)
        val phrase = mnemonic.toString()
        val bytes = phrase.toByteArray(StandardCharsets.UTF_8)
        try {
            secretVault.save(walletId, networkId, bytes)
            openWithMnemonic(mnemonic, createOnly = true)
        } finally {
            bytes.fill(0)
        }
        return phrase
    }

    fun restoreTestnetWallet(recoveryPhrase: String) {
        check(!hasWallet()) { "A Bitcoin Testnet4 wallet already exists on this device" }
        check(!databaseFile.exists()) {
            "A wallet database exists without its encrypted recovery material. It was preserved."
        }
        val normalized = recoveryPhrase.trim().split(Regex("\\s+")).joinToString(" ")
        require(normalized.isNotBlank()) { "Enter the 12-word recovery phrase" }
        val mnemonic = Mnemonic.fromString(normalized)
        val canonical = mnemonic.toString().toByteArray(StandardCharsets.UTF_8)
        try {
            secretVault.save(walletId, networkId, canonical)
            openWithMnemonic(mnemonic, createOnly = true)
        } finally {
            canonical.fill(0)
        }
    }

    fun openExistingWallet(): Snapshot {
        val secret = secretVault.load(walletId, networkId)
            ?: throw IllegalStateException("No Bitcoin Testnet4 wallet is configured")
        val phrase = try {
            String(secret, StandardCharsets.UTF_8)
        } finally {
            secret.fill(0)
        }
        val mnemonic = Mnemonic.fromString(phrase)
        return try {
            openWithMnemonic(mnemonic, createOnly = false)
            snapshot()
        } finally {
            // Kotlin/Java Strings are immutable; keep their lifetime short and never log or persist them.
        }
    }

    fun newReceiveAddress(): String = synchronized(lock) {
        val currentWallet = wallet ?: throw IllegalStateException("Open the wallet first")
        val currentPersister = persister ?: throw IllegalStateException("Wallet storage is unavailable")
        val address = currentWallet.revealNextAddress(KeychainKind.EXTERNAL).address.toString()
        currentWallet.persist(currentPersister)
        metadata.edit().putString("btc_testnet4_receive_address", address).commit()
        address
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        val currentWallet = wallet ?: throw IllegalStateException("Open the wallet first")
        val currentPersister = persister ?: throw IllegalStateException("Wallet storage is unavailable")
        val currentAddress = metadata.getString("btc_testnet4_receive_address", null)
            ?: currentWallet.revealNextAddress(KeychainKind.EXTERNAL).address.toString().also {
                metadata.edit().putString("btc_testnet4_receive_address", it).commit()
                currentWallet.persist(currentPersister)
            }
        Snapshot(
            balanceSats = currentWallet.balance().total.toSat(),
            blockHeight = currentWallet.latestCheckpoint().height,
            receiveAddress = currentAddress
        )
    }

    fun startSync(onUpdate: (Snapshot) -> Unit, onStatus: (String) -> Unit, onError: (String) -> Unit) {
        synchronized(lock) {
            if (syncThread?.isAlive == true) {
                onStatus("Bitcoin Testnet4 sync is already running")
                return
            }
            val currentWallet = wallet ?: throw IllegalStateException("Open the wallet first")
            check(syncDirectory.exists() || syncDirectory.mkdirs()) { "Could not create Bitcoin peer cache" }

            val (client, node) = CbfBuilder()
                .dataDir(syncDirectory.absolutePath)
                .peers(emptyList<Peer>())
                .connections(1u)
                .scanType(ScanType.Sync)
                .build(currentWallet)

            syncClient = client
            syncNode = node
            stopping.set(false)
            node.run()
            onStatus("Connecting to Bitcoin Testnet4 peers")

            syncThread = Thread({
                try {
                    while (!Thread.currentThread().isInterrupted && !stopping.get()) {
                        val update = client.update()
                        val next = synchronized(lock) {
                            val activeWallet = wallet ?: return@synchronized null
                            val activePersister = persister ?: return@synchronized null
                            activeWallet.applyUpdate(update)
                            activeWallet.persist(activePersister)
                            snapshot()
                        }
                        if (next != null) onUpdate(next)
                    }
                } catch (error: Throwable) {
                    if (!stopping.get() && !Thread.currentThread().isInterrupted) {
                        onError(error.message ?: "Bitcoin Testnet4 sync stopped unexpectedly")
                    }
                } finally {
                    synchronized(lock) {
                        if (syncClient === client) {
                            syncClient = null
                            syncNode = null
                            syncThread = null
                        }
                    }
                }
            }, "MortaMesh-Bitcoin-Testnet4-Sync").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stopSync() {
        stopping.set(true)
        val client = synchronized(lock) { syncClient }
        try { client?.shutdown() } catch (_: Exception) { }
        synchronized(lock) { syncThread?.interrupt() }
    }

    fun close() {
        stopSync()
        synchronized(lock) {
            try {
                val activeWallet = wallet
                val activePersister = persister
                if (activeWallet != null && activePersister != null) activeWallet.persist(activePersister)
            } catch (_: Exception) {
                // Preserve the original database and encrypted recovery material on persistence errors.
            }
            wallet = null
            persister = null
        }
    }

    private fun openWithMnemonic(mnemonic: Mnemonic, createOnly: Boolean) {
        check(walletDirectory.exists() || walletDirectory.mkdirs()) { "Could not create Bitcoin wallet storage" }
        val networkKind = NetworkKind.TEST
        val rootKey = DescriptorSecretKey(networkKind, mnemonic, null)
        val externalDescriptor = Descriptor.newBip84(rootKey, KeychainKind.EXTERNAL, networkKind)
        val changeDescriptor = Descriptor.newBip84(rootKey, KeychainKind.INTERNAL, networkKind)
        val newPersister = Persister.newSqlite(databaseFile.absolutePath)

        val restoredWallet = if (!createOnly && databaseFile.exists() && databaseFile.length() > 0L) {
            BdkWallet.load(
                descriptor = externalDescriptor,
                changeDescriptor = changeDescriptor,
                persister = newPersister
            )
        } else {
            BdkWallet(
                descriptor = externalDescriptor,
                changeDescriptor = changeDescriptor,
                network = Network.TESTNET4,
                persister = newPersister
            )
        }

        synchronized(lock) {
            persister = newPersister
            wallet = restoredWallet
            if (metadata.getString("btc_testnet4_receive_address", null).isNullOrBlank()) {
                val address = restoredWallet.revealNextAddress(KeychainKind.EXTERNAL).address.toString()
                check(metadata.edit().putString("btc_testnet4_receive_address", address).commit()) {
                    "Could not save the Bitcoin Testnet4 receive address"
                }
                restoredWallet.persist(newPersister)
            }
        }
    }
}
