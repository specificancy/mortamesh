package com.mortalive.mortamesh

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import java.util.Locale
import java.util.concurrent.Executors

/** Independent Testnet4 wallet surface with no Mortalive account or group-session dependency. */
class WalletActivity : UiKitActivity() {

    override fun areUiActionsEnabled(): Boolean = !busy

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MortaMesh-Wallet-UI").apply { isDaemon = true }
    }
    private lateinit var engine: BitcoinTestnetWalletEngine
    private var snapshot: BitcoinTestnetWalletEngine.Snapshot? = null
    private var walletReady = false
    private var walletExists = false
    private var busy = false
    private var busyText = ""
    private var errorText: String? = null
    private var syncText = "Not synced"
    private var syncing = false
    private var receiveAddress = ""
    private var backupPending = false
    private var lastSyncedAt: Long? = null
    private var walletScrollView: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        engine = BitcoinTestnetWalletEngine(this)
        walletExists = engine.hasWallet()
        backupPending = engine.isBackupPending()
        render()
        if (walletExists) {
            openSavedWallet()
            showPendingBackupIfNeeded()
        }
    }

    override fun onDestroy() {
        try { engine.close() } catch (_: Exception) { }
        executor.shutdownNow()
        walletScrollView = null
        super.onDestroy()
    }

    private fun render() {
        // Keep the reader's place when sync or wallet updates rebuild the view tree.
        val previousScrollY = walletScrollView?.scrollY ?: 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@WalletActivity.background)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
        }
        top.addView(secondaryButton("‹ Back") { finish() })
        val title = label("Wallet", 21f, ink, true).apply { gravity = Gravity.CENTER }
        top.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        top.addView(label("MORTA MESH", 9f, muted, true))
        root.addView(top)
        if (backupPending) {
            val backupBanner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = rounded(0xFFFFF4E5.toInt(), 12, 0xFFF2C078.toInt())
                addView(label("BACKUP PENDING", 11f, 0xFF8A4B08.toInt(), true))
                addView(label("Your recovery phrase has not been confirmed as backed up. Complete the offline backup to keep your wallet recoverable.", 12f, 0xFF8A4B08.toInt()).apply {
                    setPadding(0, dp(4), 0, 0)
                    setLineSpacing(dp(2).toFloat(), 1f)
                })
            }
            val bannerParams = LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8)
            }
            root.addView(backupBanner, bannerParams)
        }

        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(28))
        }
        body.addView(label("Your assets. Your keys.", 27f, ink, true))
        body.addView(label(
            "An independent wallet space inside Morta Mesh. Bitcoin and Lama Coin use separate network adapters and separate key scopes.",
            13f, muted
        ).apply { setPadding(0, dp(8), 0, dp(18)); setLineSpacing(dp(3).toFloat(), 1f) })

        if (busy) {
            val progress = card()
            progress.addView(label(busyText.ifBlank { "Working…" }, 13f, blue, true))
            body.addView(progress)
            body.addView(space(10))
        }
        errorText?.let { message ->
            val error = card()
            error.addView(label("Wallet action could not be completed", 14f, ink, true))
            error.addView(label(message, 12f, muted).apply { setPadding(0, dp(6), 0, 0) })
            body.addView(error)
            body.addView(space(10))
        }

        val bitcoin = card()
        bitcoin.addView(label("BITCOIN", 10f, muted, true))
        bitcoin.addView(label("Bitcoin · Testnet4", 20f, ink, true).apply { setPadding(0, dp(5), 0, 0) })
        bitcoin.addView(label(
            "Test coins only. This first milestone cannot receive or spend real BTC.",
            12f, muted
        ).apply { setPadding(0, dp(6), 0, dp(12)); setLineSpacing(dp(2).toFloat(), 1f) })

        if (!walletReady && !walletExists) {
            bitcoin.addView(primaryButton("Create Testnet4 wallet") { createWallet() })
            bitcoin.addView(space(8))
            bitcoin.addView(secondaryButton("Restore from 12-word phrase") { showRestoreDialog() })
        } else if (!walletReady) {
            bitcoin.addView(label(
                "Encrypted wallet data already exists, but this device could not unlock it. The app will not overwrite it or create a replacement wallet.",
                12f, muted
            ).apply { setPadding(0, dp(5), 0, dp(10)); setLineSpacing(dp(3).toFloat(), 1f) })
            bitcoin.addView(primaryButton("Try opening saved wallet") { openSavedWallet() })
        } else {
            val current = snapshot
            bitcoin.addView(label("BALANCE", 11f, muted, true).apply { setPadding(0, dp(7), 0, dp(4)) })
            val balanceRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            balanceRow.addView(
                label(current?.let { formatBtc(it.balanceSats) } ?: "— tBTC", 24f, ink, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            balanceRow.addView(testnetBadge())
            bitcoin.addView(balanceRow)
            bitcoin.addView(label(
                current?.let { it.balanceSats.toString() + " sats · block " + it.blockHeight } ?: "Waiting for wallet state",
                11f, muted
            ).apply { setPadding(0, dp(4), 0, dp(10)) })
            val addressHeading = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(5))
            }
            addressHeading.addView(label("RECEIVE ADDRESS", 11f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
            addressHeading.addView(testnetBadge())
            bitcoin.addView(addressHeading)
            val addressCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(0xFFF8FAFC.toInt(), 12, border)
                setPadding(dp(11), dp(10), dp(11), dp(10))
            }
            addressCard.addView(label(receiveAddress.ifBlank { current?.receiveAddress.orEmpty() }, 12f, ink).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            addressCard.addView(space(7))
            addressCard.addView(secondaryButton("Copy Testnet4 address") { copyAddress() })
            bitcoin.addView(addressCard)
            bitcoin.addView(space(12))
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            actions.addView(primaryButton(if (syncing) "Syncing…" else "Sync Testnet4") { startSync() },
                LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(spaceHorizontal(8))
            actions.addView(secondaryButton("New address") { generateAddress() })
            bitcoin.addView(actions)
            val syncLabel = lastSyncedAt?.let { timestamp ->
                syncText + " · Updated " + java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(timestamp))
            } ?: syncText
            bitcoin.addView(label(syncLabel, 11f, muted).apply { setPadding(0, dp(9), 0, 0) })
            bitcoin.addView(label("Use a fresh receive address for each payment to reduce address reuse.", 11f, muted).apply {
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
        bitcoin.addView(label(
            "Send/spend is not enabled. It will only be added after fee estimation, transaction preview, explicit confirmation and a reviewed signing flow.",
            11f, muted
        ).apply { setPadding(0, dp(12), 0, 0); setLineSpacing(dp(2).toFloat(), 1f) })
        body.addView(bitcoin)
        body.addView(space(12))

        val lama = compactUnavailableRow(
            title = "Lama Coin",
            subtitle = "Not available yet · Android wallet adapter is not connected",
            description = "The supplied Lama Coin project is currently a desktop Rust node/wallet, not an Android wallet library. The mobile adapter must be built and tested before creating addresses or handling funds. No placeholder address is generated."
        )
        body.addView(lama)
        body.addView(space(12))

        val nfts = compactUnavailableRow(
            title = "NFT collection",
            subtitle = "Not available yet · native asset rules are not finalized",
            description = "Minting, ownership, transfers and marketplace settlement remain unavailable until Lama Coin implements and validates native asset outputs and NFT transaction rules."
        )
        body.addView(nfts)
        body.addView(space(12))

        val privacy = card()
        privacy.addView(label("Separate from Mortalive", 15f, ink, true))
        privacy.addView(label(
            "This wallet does not require a Mortalive account. It does not send your wallet address, recovery phrase, balances, NFT holdings or transaction history to Mortalive or to a group-submission flow. Any future NFT badge connection must be a separate, explicit and minimal opt-in.",
            12f, muted
        ).apply { setPadding(0, dp(7), 0, 0); setLineSpacing(dp(3).toFloat(), 1f) })
        body.addView(privacy)
        body.addView(space(14))
        body.addView(label(
            "Morta Mesh Wallet · experimental Testnet4 milestone · never enter a recovery phrase into a website or share it with support.",
            10f, muted
        ).apply { gravity = Gravity.CENTER; setLineSpacing(dp(2).toFloat(), 1f) })

        scroll.addView(body, android.view.ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        walletScrollView = scroll
        setContentView(root)
        scroll.post { scroll.scrollTo(0, previousScrollY) }
    }

    private fun openSavedWallet() {
        runWalletTask("Opening encrypted Bitcoin Testnet4 wallet", {
            engine.openExistingWallet()
        }) { loaded ->
            walletReady = true
            backupPending = engine.isBackupPending()
            snapshot = loaded
            receiveAddress = loaded.receiveAddress
            syncText = "Wallet ready · Bitcoin Testnet4"
        }
    }

    private fun createWallet() {
        runWalletTask("Creating encrypted Bitcoin Testnet4 wallet", {
            engine.createNewTestnetWallet()
        }) { phrase ->
            walletReady = true
            backupPending = engine.isBackupPending()
            snapshot = engine.snapshot()
            receiveAddress = snapshot?.receiveAddress.orEmpty()
            syncText = "Wallet created · back up the recovery phrase"
            render()
            showRecoveryPhrase(phrase)
        }
    }

    private fun showRecoveryPhrase(phrase: String) {
        val words = label(phrase, 17f, ink, true).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(8).toFloat(), 1f)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setPadding(dp(16), dp(20), dp(16), dp(20))
            background = rounded(0xFFF8FAFC.toInt(), 14, border)
        }
        val message = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label(
                "Write these 12 words down in order and keep them offline. Anyone with these words can control this Testnet4 wallet. Morta Mesh does not send them to a server.",
                13f, muted
            ).apply { setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, 0, 0, dp(14)) })
            addView(words)
        }
        showSecureDialog(
            AlertDialog.Builder(this)
                .setTitle("Back up your recovery phrase")
                .setView(message)
                .setCancelable(false)
                .setPositiveButton("I've written it down") { _, _ ->
                    // Clear the rendered secret immediately; JVM String instances are not deterministically zeroizable.
                    words.text = ""
                    try {
                        engine.acknowledgeBackup()
                        backupPending = false
                        syncText = "Recovery phrase saved in your offline backup"
                    } catch (_: Exception) {
                        errorText = "Could not record the backup confirmation. The phrase will be shown again next time."
                    }
                    render()
                }
        )
    }

    private fun showRestoreDialog() {
        val input = EditText(this).apply {
            hint = "Enter all 12 words in order"
            minLines = 3
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(false)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label(
                "Recovery phrase is processed on this device and stored only as encrypted app-private data.",
                12f, muted
            ).apply { setPadding(0, 0, 0, dp(10)) })
            addView(input)
        }
        val restoreBuilder = AlertDialog.Builder(this)
            .setTitle("Restore Bitcoin Testnet4 wallet")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ ->
                val phrase = input.text.toString()
                input.text.clear()
                runWalletTask("Validating and encrypting recovery phrase", {
                    engine.restoreTestnetWallet(phrase)
                    engine.snapshot()
                }) { restored ->
                    walletReady = true
                    backupPending = false
                    snapshot = restored
                    receiveAddress = restored.receiveAddress
                    syncText = "Wallet restored · Testnet4"
                }
            }
        showSecureDialog(restoreBuilder)
    }

    /** Dialogs are separate windows; the activity's FLAG_SECURE does not cover them. */
    private fun showSecureDialog(builder: AlertDialog.Builder) {
        if (isFinishing || isDestroyed) return
        val dialog = builder.create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    /** Re-offer a newly-created phrase if the user has not yet confirmed an offline backup. */
    private fun showPendingBackupIfNeeded() {
        try {
            executor.execute {
                val phrase = try {
                    if (engine.isBackupPending()) engine.recoveryPhraseForBackup() else null
                } catch (_: Exception) { null }
                if (phrase != null) runOnUiThread {
                    if (!isFinishing && !isDestroyed) showRecoveryPhrase(phrase)
                }
            }
        } catch (_: Exception) {
            // Activity is closing; no UI callback is needed.
        }
    }

    private fun generateAddress() {
        runWalletTask("Generating a fresh Testnet4 receive address", {
            engine.newReceiveAddress()
        }) { address ->
            receiveAddress = address
            snapshot = engine.snapshot()
            syncText = "New receive address generated"
        }
    }

    private fun startSync() {
        if (syncing || busy) return
        syncing = true
        syncText = "Connecting to Bitcoin Testnet4 peers…"
        render()
        try {
            engine.startSync(
                onUpdate = { next ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        snapshot = next
                        lastSyncedAt = System.currentTimeMillis()
                        receiveAddress = next.receiveAddress
                        syncText = "Synced through block " + next.blockHeight
                        render()
                    }
                },
                onStatus = { message ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        syncText = message
                        render()
                    }
                },
                onError = { message ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        syncing = false
                        syncText = "Sync stopped: " + message
                        render()
                    }
                }
            )
        } catch (error: Exception) {
            syncing = false
            syncText = error.message ?: "Could not start Bitcoin testnet sync"
            render()
        }
    }

    private fun copyAddress() {
        val address = receiveAddress.ifBlank { snapshot?.receiveAddress.orEmpty() }
        if (address.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Bitcoin Testnet4 receive address", address))
        Toast.makeText(this, "Testnet address copied", Toast.LENGTH_SHORT).show()
    }

    private fun <T> runWalletTask(message: String, action: () -> T, onSuccess: (T) -> Unit) {
        if (busy) return
        busy = true
        busyText = message
        errorText = null
        render()
        executor.execute {
            try {
                val result = action()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    busyText = ""
                    walletExists = engine.hasWallet()
                    onSuccess(result)
                    render()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    busyText = ""
                    walletExists = engine.hasWallet()
                    errorText = error.message ?: "Wallet operation failed"
                    render()
                }
            }
        }
    }

    private fun testnetBadge(): TextView = label("TESTNET · NO REAL VALUE", 11f, blue, true).apply {
        background = rounded(blueLight, 99)
        setPadding(dp(8), dp(5), dp(8), dp(5))
        gravity = Gravity.CENTER
    }

    private fun compactUnavailableRow(title: String, subtitle: String, description: String): LinearLayout =
        card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            isClickable = true
            isFocusable = true
            contentDescription = "$title, not available yet. Double tap for details."
            setOnClickListener {
                AlertDialog.Builder(this@WalletActivity)
                    .setTitle(title)
                    .setMessage(description)
                    .setPositiveButton("Got it", null)
                    .show()
            }
            val copy = LinearLayout(this@WalletActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 15f, ink, true))
                addView(label(subtitle, 11f, muted).apply { setPadding(0, dp(4), 0, 0) })
            }
            addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("Details  ›", 12f, blue, true))
        }

    private fun formatBtc(sats: ULong): String {
        val whole = sats / 100_000_000uL
        val fraction = sats % 100_000_000uL
        return String.format(Locale.US, "%d.%08d tBTC", whole.toLong(), fraction.toLong())
    }

    private fun space(height: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun spaceHorizontal(width: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(width), 1)
    }
}
