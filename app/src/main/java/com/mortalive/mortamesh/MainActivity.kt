package com.mortalive.mortamesh

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.text.InputType
import android.util.Base64
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.net.wifi.WifiManager
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.net.wifi.WpsInfo
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.VideoView
import android.widget.MediaController
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.Locale
import kotlin.math.max

class MainActivity : UiKitActivity() {
    private val green = 0xFF159467.toInt()

    private lateinit var content: FrameLayout
    private lateinit var navBar: LinearLayout
    private lateinit var preferences: android.content.SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentScreen = Screen.HOME
    private lateinit var privateVault: PrivateVault
    private lateinit var encryptedChatStore: EncryptedChatStore
    private lateinit var meshSecurity: MeshSecurity
    private lateinit var replayGuard: ReplayGuard
    private var chatHistoryStorageLocked = false
    private var vaultUnlocked = false
    private var privateMediaDialog: AlertDialog? = null
    private var privatePreviewFile: File? = null
    private var pendingCaptureMime: String = "image/jpeg"
    private data class IncomingMediaSession(
        val writer: PrivateVault.IncomingWrite,
        val senderNodeId: String,
        val expectedBytes: Long,
        val startedAt: Long = System.currentTimeMillis(),
        var nextChunk: Int = 0,
        var lastActivityAt: Long = System.currentTimeMillis()
    )
    private val incomingMedia = java.util.concurrent.ConcurrentHashMap<String, IncomingMediaSession>()
    private val incomingMediaLifecycleLock = Any()
    @Volatile private var mediaReceiveClosing = false
    private val activeMediaTransfers: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val mediaAckWaiters = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()
    private var openingVaultPreview = false
    private val mediaReceiveExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "MortaMesh-MediaReceive").apply { isDaemon = true } }
    private var activeChatId: String? = null
    private var nickname = "Morta user"
    private var meshRunning = false
    private var gattServiceReady = false
    private var advertisingRequested = false
    private var advertisingStartPending = false
    private var isAdvertising = false
    private var isScanning = false
    private var scanAvailable = true
    private var lastPeerRenderAt = 0L
    private var scrollableScreen: ScrollView? = null
    private var scrollableScreenId: Screen? = null
    private var permissionsPermanentlyDenied = false
    private var statusText = "Not connected"
    private var chatColumn: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var currentComposer: EditText? = null
    private lateinit var worldChatStore: WorldChatStore
    private lateinit var anonymousSubmissionStore: AnonymousSubmissionStore
    private lateinit var anonymousSubmissionPackager: AnonymousSubmissionPackager
    private lateinit var anonymousRelayStore: AnonymousRelayStore
    private val anonymousRelayPeerModes = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val anonymousRelayAdmissionLimiter = AnonymousRelayAdmissionLimiter()
    private val anonymousRelayCapabilitySeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var anonymousGroupValue = ""
    private var anonymousTextValue = ""
    private var anonymousSelectedFile: File? = null
    private var anonymousSelectedMime: String? = null
    private var anonymousAttachmentInfo: TextView? = null
    private var anonymousDraftSaving = false
    private var anonymousAttachmentImporting = false
    private val anonymousSubmissionExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "MortaMesh-AnonymousSubmission").apply { isDaemon = true } }
    private val anonymousRelayExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "MortaMesh-AnonymousRelay").apply { isDaemon = true } }
    private var worldPostColumn: LinearLayout? = null
    private var worldChatScroll: ScrollView? = null
    private var worldComposer: EditText? = null
    private var worldCharCounter: TextView? = null
    private var worldLinkStatus: TextView? = null
    private var worldPostFeedback: TextView? = null

    private data class NearbyPeer(
        val device: BluetoothDevice,
        var name: String,
        var nodeHint: String? = null,
        var lastSeen: Long = System.currentTimeMillis()
    )
    private data class PendingWifiChatRequest(
        val requestId: String,
        val peerNodeId: String,
        val peerName: String,
        val expiresAt: Long
    )
    private data class ChatMessage(
        val text: String,
        val mine: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
        val mediaMimeType: String? = null,
        val mediaSize: Long = 0L,
        val deliveryState: String? = null,
        val requiredTransport: String? = null,
        val transferId: String? = null
    )
    private data class MeshRoute(val nodeId: String, val name: String, val nextHop: String, val hops: Int, val lastSeen: Long = System.currentTimeMillis())
    private data class IncomingTextFragments(val total: Int, val parts: MutableMap<Int, String>, val createdAt: Long)
    private val nearbyPeers = linkedMapOf<String, NearbyPeer>()
    private val peerNames = linkedMapOf<String, String>()
    private val chatHistory = linkedMapOf<String, MutableList<ChatMessage>>()
    private val pendingMessages = linkedMapOf<String, ArrayDeque<String>>()
    // Logical node identity is separate from transient Bluetooth MAC/Wi-Fi Direct addresses.
    private val physicalNodeByTransport = linkedMapOf<String, String>()
    private val meshRoutes = linkedMapOf<String, MeshRoute>()
    private val identityRecords = linkedMapOf<String, JSONObject>()
    private val keyChangedContacts = linkedSetOf<String>()
    private val seenIdentityAdvertisements = linkedMapOf<String, Long>()
    private val seenMeshPackets = linkedMapOf<String, Long>()
    private val meshIngressLimiter = IngressRateLimiter(maxPerPeer = 240, maxGlobal = 1_200)
    private val authenticatedChatIngressLimiter = IngressRateLimiter(maxPerPeer = 30, maxGlobal = 60)
    private val outgoingChatSendLimiter = IngressRateLimiter(maxPerPeer = 30, maxGlobal = 60)
    @Volatile private var chatSendRateLimited = false
    private val mediaStartIngressLimiter = IngressRateLimiter(maxPerPeer = 10, maxGlobal = 40)
    private val worldChatIngressLimiter = IngressRateLimiter(maxPerPeer = 30, maxGlobal = 120)
    private val incomingTextFragments = linkedMapOf<String, IncomingTextFragments>()
    private val transportIdentitySentAt = linkedMapOf<String, Long>()
    private val transportWorldSyncSentAt = linkedMapOf<String, Long>()
    private val meshProtocolExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "MortaMesh-Protocol").apply { isDaemon = true } }
    private var legacyWarningShown = false

    private lateinit var bluetoothManager: BluetoothManager
    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null
    private val serverDevices = linkedMapOf<String, BluetoothDevice>()
    private val serverMtu = linkedMapOf<String, Int>()
    private val subscribedDevices = linkedSetOf<String>()
    private val serverNotifyQueues = linkedMapOf<String, ArrayDeque<ByteArray>>()
    private val serverNotifyBusy = linkedSetOf<String>()
    private val clientGatts = linkedMapOf<String, BluetoothGatt>()
    private val clientCharacteristics = linkedMapOf<String, BluetoothGattCharacteristic>()
    private val clientMtus = linkedMapOf<String, Int>()
    private val clientReady = linkedSetOf<String>()
    private val clientWriteQueues = linkedMapOf<String, ArrayDeque<ByteArray>>()
    private val clientWriteBusy = linkedSetOf<String>()
    private val bleSendFailures = linkedMapOf<String, Int>()
    private var nextMessageId = (System.nanoTime() and 0xFFFF).toInt()

    // Wi-Fi Direct is the higher-throughput path; BLE remains available as a fallback.
    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var localNodeId: String = ""
    private var wifiReceiverRegistered = false
    private var wifiP2pEnabled = false
    private var wifiDiscovering = false
    private var wifiLocalAddress: String = ""
    private var wifiConnectingChatId: String? = null
    // A P2P request remains active until the authenticated Wi-Fi socket is ready or times out.
    // Discovery must stay paused while this flag is true; Android stops discovery during negotiation.
    private var wifiP2pConnectRequestPending = false
    private var wifiConnectTimeoutRunnable: Runnable? = null
    private var wifiSocketClientConnecting = false
    private var wifiGroupFormed = false
    private var wifiServerSocket: ServerSocket? = null
    private var wifiServerThread: Thread? = null
    private val wifiPeers = linkedMapOf<String, WifiP2pDevice>()
    // Wi-Fi Direct exposes a transport address, not the Morta identity. DNS-SD binds the
    // transient P2P address to the stable node ID advertised by the same app.
    private val wifiPeerNodeIds = linkedMapOf<String, String>()
    private val wifiPeerNodeHints = linkedMapOf<String, String>()
    private val wifiPeerDisplayNames = linkedMapOf<String, String>()
    private var wifiLocalServiceRegistered = false
    private var wifiServiceDiscoveryStarting = false
    private val wifiIncomingChatRequests = linkedMapOf<String, PendingWifiChatRequest>()
    private val wifiOutgoingChatRequests = linkedMapOf<String, PendingWifiChatRequest>()
    private val wifiAcceptedChatRequests = linkedMapOf<String, String>()
    private var lastWifiChatRequestToastAt = 0L
    private var pendingWifiChatAutoOpenNodeId: String? = null
    private var wifiChatGroupRequestId: String? = null
    private var wifiChatGroupPeerNodeId: String? = null
    private val wifiSockets = linkedMapOf<String, Socket>()
    private val wifiWriters = linkedMapOf<String, BufferedWriter>()
    private val pendingWifiMessages = linkedMapOf<String, ArrayDeque<String>>()
    private val wifiConnectAddresses = linkedMapOf<String, String>()
    private val wifiPort = 42892

    // Keep indirect routes fresh while the process is alive. Android may suspend the app in the
    // background; this is deliberately not a claim of always-on mesh service.
    private val meshGossipRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            try {
                val now = System.currentTimeMillis()
                val expired = meshRoutes.values.any { now - it.lastSeen > MESH_ROUTE_MAX_AGE_MS }
                meshRoutes.entries.removeAll { now - it.value.lastSeen > MESH_ROUTE_MAX_AGE_MS }
                if (expired) renderIfBrowseScreen()
                gossipLocalIdentity()
                mediaReceiveExecutor.execute { reapExpiredIncomingMedia() }
                wifiAutoTick()
            } catch (_: Exception) {
                // One radio error must not stop future route refreshes.
            } finally {
                if (!isFinishing && !isDestroyed) mainHandler.postDelayed(this, MESH_GOSSIP_INTERVAL_MS)
            }
        }
    }

    private val serviceUuid = UUID.fromString("a31c9e18-29c4-4a7e-9d54-3f9c0a750100")
    private val messageUuid = UUID.fromString("a31c9e18-29c4-4a7e-9d54-3f9c0a750101")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val parcelUuid by lazy { ParcelUuid(serviceUuid) }
    private val permissionRequestCode = 901
    private val enableBluetoothRequestCode = 902

    private enum class Screen { HOME, NEARBY, CHATS, VAULT, SETTINGS, CHAT, WORLD_CHAT, ANONYMOUS_POST }
    private companion object {
        const val WIFI_PREFIX = "wifi:"
        const val WIFI_NODE_PREFIX = "wifi:node:"
        const val MESH_PREFIX = "mesh:"
        const val MESH_ID_PREFIX = "@MORTAMESH/2/ID|"
        const val MESH_PACKET_PREFIX = "@MORTAMESH/2/PACKET|"
        const val WORLD_CHAT_PREFIX = "@MORTAMESH/2/WORLD|"
        const val ANONYMOUS_RELAY_CAPABILITY_PREFIX = "@MORTAMESH/2/ANON_CAP|"
        const val MESH_ROUTE_TTL = 8
        const val WORLD_CHAT_TTL = 8
        const val MAX_WORLD_CHAT_FRAME_BYTES = 3500
        const val MESH_ROUTE_MAX_AGE_MS = 90_000L
        const val MESH_GOSSIP_INTERVAL_MS = 30_000L
        const val MAX_TEXT_CHUNKS = 30
        const val REQUEST_TAKE_PHOTO = 951
        const val REQUEST_RECORD_VIDEO = 952
        const val REQUEST_ANONYMOUS_ATTACHMENT = 953
        const val MEDIA_WIRE_PREFIX = "@MM1|"
        const val MAX_INCOMING_MEDIA_SESSIONS = 4
        const val MAX_INCOMING_MEDIA_SESSIONS_PER_SENDER = 2
        const val INCOMING_MEDIA_IDLE_TIMEOUT_MS = 120_000L
        const val INCOMING_MEDIA_ABSOLUTE_TIMEOUT_MS = 30L * 60L * 1000L
        const val MEDIA_WIFI_CHUNK_BYTES = 36 * 1024
        const val MEDIA_BLE_CHUNK_BYTES = 900
        const val WIFI_MAX_FRAME_CHARS = 400_000
        const val WIFI_HELLO_TIMEOUT_MS = 15_000
        const val KEY_CHANGED_KEY_PREFIX = "mesh_key_changed_contact_"
        const val VERIFIED_FINGERPRINT_PREFIX = "mesh_verified_fingerprint_"
    }

    private val wifiChannelListener: WifiP2pManager.ChannelListener = WifiP2pManager.ChannelListener {
        wifiP2pChannel = wifiP2pManager?.initialize(applicationContext, mainLooper, wifiChannelListener)
        wifiLocalServiceRegistered = false
        wifiServiceDiscoveryStarting = false
        runOnUiThread { Toast.makeText(this, "Wi-Fi Direct was reset. Scan again to reconnect.", Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = white
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(true)
        if (Build.VERSION.SDK_INT >= 21) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        preferences = getSharedPreferences("morta_mesh_local", Context.MODE_PRIVATE)
        permissionsPermanentlyDenied = preferences.getBoolean("nearby_permissions_permanently_denied", false)
        preferences.all.keys
            .filter { it.startsWith(KEY_CHANGED_KEY_PREFIX) && preferences.getBoolean(it, false) }
            .forEach { keyChangedContacts.add(it.removePrefix(KEY_CHANGED_KEY_PREFIX)) }
        replayGuard = ReplayGuard(this)
        worldChatStore = WorldChatStore(this)
        anonymousSubmissionStore = AnonymousSubmissionStore(this)
        anonymousSubmissionPackager = AnonymousSubmissionPackager(this, anonymousSubmissionStore)
        anonymousRelayStore = AnonymousRelayStore(this)
        File(cacheDir, "anonymous-source").deleteRecursively()
        privateVault = PrivateVault(this)
        encryptedChatStore = EncryptedChatStore(this)
        // Capture/preview temporaries are private to this app; remove leftovers from an interrupted session.
        File(cacheDir, "morta-capture").deleteRecursively()
        File(cacheDir, "morta-preview").deleteRecursively()
        File(cacheDir, "anonymous-source").deleteRecursively()
        nickname = preferences.getString("nickname", "Morta user") ?: "Morta user"
        localNodeId = preferences.getString("node_id", null)?.takeIf { savedId ->
            try { UUID.fromString(savedId).toString() == savedId } catch (_: Exception) { false }
        } ?: UUID.randomUUID().toString().also { preferences.edit().putString("node_id", it).apply() }
        meshSecurity = MeshSecurity(this, localNodeId, preferences)
        val keyBoundNodeId = meshSecurity.derivedLocalNodeId()
        if (keyBoundNodeId != localNodeId) {
            localNodeId = keyBoundNodeId
            check(preferences.edit().putString("node_id", localNodeId).commit()) {
                "Could not persist the key-bound Morta Mesh node ID"
            }
            meshSecurity = MeshSecurity(this, localNodeId, preferences)
        }
        identityRecords[localNodeId] = meshSecurity.identityRecord(nickname)
        loadHistory()
        recoverLegacyQueuedMedia()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = bluetoothManager.adapter
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        wifiP2pChannel = wifiP2pManager?.initialize(applicationContext, mainLooper, wifiChannelListener)
        content = FrameLayout(this)
        content.setBackgroundColor(this@MainActivity.background)
        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(white)
            elevation = dp(8).toFloat()
            setPadding(dp(8), dp(4), dp(8), dp(4))
            minimumHeight = dp(64)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(navBar, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        render()
    }

    override fun onStart() {
        super.onStart()
        uiVisible = true
        if (!wifiReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(wifiDirectReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    registerReceiver(wifiDirectReceiver, filter)
                }
                wifiReceiverRegistered = true
            } catch (_: Exception) { }
        }
        mainHandler.removeCallbacks(meshGossipRunnable)
        mainHandler.postDelayed(meshGossipRunnable, 10_000L)
    }

    override fun onResume() {
        super.onResume()
        if (permissionsPermanentlyDenied && hasBluetoothRuntimePermissions() && hasWifiDirectRuntimePermission()) {
            permissionsPermanentlyDenied = false
            preferences.edit().putBoolean("nearby_permissions_permanently_denied", false).apply()
            startAvailableTransports()
            if (currentScreen == Screen.HOME || currentScreen == Screen.NEARBY) render()
        }
        if (currentScreen == Screen.VAULT) render() else refreshNav()
        retryQueuedMedia()
        autoStartTransportsOnce()
        wifiAutoTick()
    }

    override fun onStop() {
        uiVisible = false
        vaultUnlocked = false
        openingVaultPreview = false
        try { privateMediaDialog?.dismiss() } catch (_: Exception) { }
        privateMediaDialog = null
        privatePreviewFile?.delete()
        privatePreviewFile = null
        if (wifiReceiverRegistered) {
            try { unregisterReceiver(wifiDirectReceiver) } catch (_: Exception) { }
            wifiReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(meshGossipRunnable)
        clearWifiConnectTimeout()
        anonymousSelectedFile?.delete()
        File(cacheDir, "anonymous-source").deleteRecursively()
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) { }
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) { }
        advertisingRequested = false
        advertisingStartPending = false
        isAdvertising = false
        gattServiceReady = false
        try { gattServer?.close() } catch (_: Exception) { }
        clientGatts.values.toList().forEach { try { it.disconnect(); it.close() } catch (_: Exception) { } }
        clientGatts.clear()
        try { wifiServerSocket?.close() } catch (_: Exception) { }
        synchronized(incomingMediaLifecycleLock) { mediaReceiveClosing = true }
        mediaReceiveExecutor.shutdownNow()
        meshProtocolExecutor.shutdownNow()
        anonymousSubmissionExecutor.shutdownNow()
        anonymousRelayExecutor.shutdownNow()
        incomingMedia.values.toList().forEach { try { it.writer.abort() } catch (_: Exception) { } }
        incomingMedia.clear()
        try {
            val channel = wifiP2pChannel
            if (hasWifiDirectRuntimePermission() && channel != null) wifiP2pManager?.removeGroup(channel, null)
        } catch (_: Exception) { }
        wifiSockets.values.toList().forEach { try { it.close() } catch (_: Exception) { } }
        wifiSockets.clear()
        wifiWriters.clear()
        super.onDestroy()
    }

    private fun vSpace(height: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(22))
    }

    private fun scrollPage(body: LinearLayout) = ScrollView(this).apply {
        setFillViewport(true)
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        addView(body, FrameLayout.LayoutParams(-1, -2))
    }

    private fun addSectionTitle(parent: LinearLayout, title: String, subtitle: String? = null) {
        parent.addView(label(title, 19f, ink, true))
        if (subtitle != null) {
            val sub = label(subtitle, 13f, muted)
            sub.setPadding(0, dp(6), 0, 0)
            parent.addView(sub)
        }
    }

    private fun addBrandHeader(parent: LinearLayout, trailing: String? = null, trailingClick: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(MeshMark(this), LinearLayout.LayoutParams(dp(42), dp(42)))
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        textCol.addView(label("Morta Mesh", 19f, ink, true))
        textCol.addView(label("OFFLINE-FIRST · NEARBY MESSAGING", 9.5f, muted, true).apply { letterSpacing = .06f; setPadding(0, dp(4), 0, 0) })
        row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) {
            row.addView(secondaryButton(trailing) { trailingClick?.invoke() })
        } else {
            val dot = TextView(this).apply {
                text = if (meshRunning) "●" else "○"
                textSize = 15f
                setTextColor(if (meshRunning) green else muted)
                gravity = Gravity.CENTER
                background = rounded(white, 16, border)
                setPadding(dp(11), dp(9), dp(11), dp(9))
            }
            row.addView(dot)
        }
        parent.addView(row)
        parent.addView(vSpace(24))
    }

    private fun render() {
        chatColumn = null
        chatScroll = null
        currentComposer = null
        worldPostColumn = null
        worldChatScroll = null
        worldComposer = null
        worldCharCounter = null
        worldLinkStatus = null
        worldPostFeedback = null
        val previousScrollY = if (scrollableScreenId == currentScreen) scrollableScreen?.scrollY ?: 0 else 0
        content.removeAllViews()
        val view = when (currentScreen) {
            Screen.HOME -> buildHome()
            Screen.NEARBY -> buildNearby()
            Screen.CHATS -> buildChats()
            Screen.SETTINGS -> buildSettings()
            Screen.VAULT -> buildVault()
            Screen.CHAT -> buildChat()
            Screen.WORLD_CHAT -> buildWorldChat()
            Screen.ANONYMOUS_POST -> buildAnonymousPost()
        }
        content.addView(view, FrameLayout.LayoutParams(-1, -1))
        scrollableScreen = view as? ScrollView
        scrollableScreenId = currentScreen
        scrollableScreen?.let { restored ->
            restored.post { restored.scrollTo(0, previousScrollY) }
        }
        refreshNav()
    }

    private fun buildHome(): View {
        val p = page()
        addBrandHeader(p, if (meshRunning || wifiDiscovering) "Visible" else "Start nearby discovery") { ensureBluetoothPermissionsAndStart() }

        val hero = card().apply {
            background = rounded(0xFFEDF4FF.toInt(), 24)
            setPadding(dp(20), dp(22), dp(20), dp(20))
            addView(label("Your people,\nwithin reach.", 28f, 0xFF12315B.toInt(), true))
            addView(label("Find nearby people and start private, end-to-end encrypted conversations. Unsent media stays queued for its intended recipient.", 14f, 0xFF46607E.toInt()).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(12), 0, dp(18))
            })
            addView(primaryButton(if (isScanning || wifiDiscovering) "Searching nearby…" else "Find nearby devices") { ensureBluetoothPermissionsAndStart() })
            addView(vSpace(8))
            addView(secondaryButton("How media travels  ⓘ") { showMediaTravelInfo() })
            addView(vSpace(10))
            addView(secondaryButton("Open conversations") { currentScreen = Screen.CHATS; render() })
        }
        p.addView(hero)
        addPermissionsNeededCard(p)
        p.addView(vSpace(18))

        val status = card()
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val online = meshRunning || wifiDiscovering || wifiSockets.isNotEmpty()
        val statusDot = TextView(this).apply {
            text = if (online) "●" else "○"
            setTextColor(if (online) green else muted)
            textSize = 19f
        }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(28), dp(28)))
        val statusColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusColumn.addView(label(if (online) "Nearby discovery is active" else "Nearby discovery is off", 14f, ink, true))
        statusColumn.addView(label(
            if (online) "Scanning only · tap a discovered device to start a connection" else "No nearby search is running",
            12f, muted
        ).apply { setPadding(0, dp(4), 0, 0) })
        statusRow.addView(statusColumn, LinearLayout.LayoutParams(0, -2, 1f))
        status.addView(statusRow)
        p.addView(status)

        p.addView(vSpace(24))
        val count = nearbyPeers.size + wifiPeers.size + meshRoutes.values.count { it.hops > 1 && hasPeerTransport(it.nextHop) }
        addSectionTitle(p, "Nearby", if (count == 0) "Devices appear here when they are close by." else "$count nearby or relayed device${if (count == 1) "" else "s"} found")
        p.addView(vSpace(10))
        if (count == 0) {
            val empty = card()
            empty.gravity = Gravity.CENTER_HORIZONTAL
            empty.addView(TextView(this).apply {
                text = "◎"
                textSize = 30f
                setTextColor(blue)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, dp(42)))
            empty.addView(label("No one nearby yet", 14f, ink, true).apply { gravity = Gravity.CENTER })
            empty.addView(label("Keep Morta Mesh open while both phones search.", 12f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(7), 0, 0) })
            p.addView(empty)
        } else {
            wifiPeers.values.take(2).forEach { p.addView(wifiPeerRow(it, false)); p.addView(vSpace(8)) }
            nearbyPeers.values.sortedByDescending { it.lastSeen }.take(2).forEach { p.addView(peerRow(it, false)); p.addView(vSpace(8)) }
            p.addView(secondaryButton("See all nearby devices") { currentScreen = Screen.NEARBY; render() })
        }
        return scrollPage(p)
    }

    private fun pruneWifiChatRequests() {
        val now = System.currentTimeMillis()
        wifiIncomingChatRequests.entries.removeAll { it.value.expiresAt <= now }
        wifiOutgoingChatRequests.entries.removeAll { it.value.expiresAt <= now }
    }

    private fun addWifiChatRequestCards(parent: LinearLayout) {
        pruneWifiChatRequests()
        if (wifiIncomingChatRequests.isEmpty()) return
        parent.addView(vSpace(12))
        addSectionTitle(parent, "Wi-Fi Direct requests", "Requests stay here quietly; no blocking popup is shown.")
        parent.addView(vSpace(8))
        wifiIncomingChatRequests.values.sortedBy { it.expiresAt }.take(4).forEach { request ->
            val requestCard = card()
            requestCard.addView(label(request.peerName, 14f, ink, true))
            requestCard.addView(label(
                "Wants to start an encrypted Wi-Fi Direct chat",
                12f, muted
            ).apply { setPadding(0, dp(4), 0, dp(10)) })
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            actions.addView(primaryButton("Accept") {
                acceptWifiChatRequest(request.requestId)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
            actions.addView(secondaryButton("Decline") {
                declineWifiChatRequest(request.requestId)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            requestCard.addView(actions)
            parent.addView(requestCard)
            parent.addView(vSpace(8))
        }
    }

    private fun buildNearby(): View {
        val p = page()
        addBrandHeader(p, "Scan again") { ensureBluetoothPermissionsAndStart(forceRescan = true) }
        addSectionTitle(p, "Nearby devices", if (isScanning || wifiDiscovering) "Searching over both nearby transports…" else "Choose the faster Wi-Fi Direct link or Bluetooth LE")
        addPermissionsNeededCard(p)
        p.addView(label(
            "A nearby phone may appear in both sections. Each entry is a separate connection path to the same phone, not a second person.",
            12f, muted
        ).apply { setPadding(0, dp(2), 0, dp(8)) })
        p.addView(vSpace(6))
        p.addView(secondaryButton("How media travels  ⓘ") { showMediaTravelInfo() })
        p.addView(vSpace(16))
        addWifiChatRequestCards(p)
        if (wifiPeers.isEmpty()) {
            p.addView(label("WI-FI DIRECT · HIGHER SPEED", 10f, blue, true).apply { letterSpacing = .08f })
            p.addView(vSpace(7))
            val wifiEmpty = card()
            wifiEmpty.addView(label(if (wifiDiscovering) "Searching for Wi-Fi Direct peers…" else "No Wi-Fi Direct peers yet", 14f, ink, true))
            wifiEmpty.addView(label("Turn on Wi-Fi and tap Scan again on both phones.", 12f, muted).apply { setPadding(0, dp(6), 0, 0) })
            p.addView(wifiEmpty)
            p.addView(vSpace(14))
        } else {
            addSectionTitle(p, "Wi-Fi Direct", "Higher-throughput direct connection")
            p.addView(vSpace(8))
            wifiPeers.values.sortedBy { it.deviceName.lowercase(Locale.ROOT) }.forEach { p.addView(wifiPeerRow(it, true)); p.addView(vSpace(9)) }
            p.addView(vSpace(7))
        }
        addSectionTitle(p, "Bluetooth LE", if (nearbyPeers.isEmpty()) "Low-power fallback for nearby text messages" else "${nearbyPeers.size} Bluetooth device${if (nearbyPeers.size == 1) "" else "s"} found")
        p.addView(vSpace(8))
        if (nearbyPeers.isEmpty()) {
            val empty = card()
            empty.gravity = Gravity.CENTER
            empty.setPadding(dp(20), dp(20), dp(20), dp(20))
            empty.addView(label(if (isScanning) "Scanning over Bluetooth…" else "No Bluetooth peers yet", 15f, ink, true).apply { gravity = Gravity.CENTER })
            empty.addView(label("Keep both devices close and Morta Mesh open.", 12f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(7), 0, 0) })
            p.addView(empty)
        } else {
            nearbyPeers.values.sortedByDescending { it.lastSeen }.forEach { p.addView(peerRow(it, true)); p.addView(vSpace(9)) }
        }
        val relayed = meshRoutes.values
            .filter { it.nodeId != localNodeId && it.hops > 1 && hasPeerTransport(it.nextHop) }
            .distinctBy { it.nodeId }
            .sortedWith(compareBy<MeshRoute> { it.hops }.thenBy { it.name.lowercase(Locale.ROOT) })
        if (relayed.isNotEmpty()) {
            p.addView(vSpace(14))
            addSectionTitle(p, "Reachable through Morta Mesh", "Relayed across ${relayed.maxOf { it.hops }} hops · end-to-end encrypted")
            p.addView(vSpace(8))
            relayed.forEach { p.addView(meshPeerRow(it)); p.addView(vSpace(9)) }
        }
        p.addView(vSpace(8))
        p.addView(primaryButton("Scan with both transports") { ensureBluetoothPermissionsAndStart(forceRescan = true) })
        return scrollPage(p)
    }

    private fun buildChats(): View {
        val p = page()
        addBrandHeader(p)
        addSectionTitle(p, "Conversations", "Private messages and the public mesh timeline.")
        p.addView(vSpace(14))
        addWifiChatRequestCards(p)
        val worldEntry = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener { openWorldChat() }
        }
        worldEntry.addView(AvatarView(this, "World"), LinearLayout.LayoutParams(dp(48), dp(48)))
        val worldCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(6), 0)
            addView(label("World Chat", 15f, ink, true))
            addView(label("Public posts · relayed across connected devices", 12f, muted).apply { setPadding(0, dp(5), 0, 0) })
        }
        worldEntry.addView(worldCopy, LinearLayout.LayoutParams(0, -2, 1f))
        worldEntry.addView(label("›", 26f, blue, true))
        p.addView(worldEntry)
        p.addView(vSpace(10))
        val anonymousEntry = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener { currentScreen = Screen.ANONYMOUS_POST; render() }
        }
        anonymousEntry.addView(AvatarView(this, "Anon"), LinearLayout.LayoutParams(dp(48), dp(48)))
        val anonymousCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(6), 0)
            addView(label("Anonymous group submission", 15f, ink, true))
            addView(label("No Mortalive account · encrypted local drafts", 12f, muted).apply { setPadding(0, dp(5), 0, 0) })
        }
        anonymousEntry.addView(anonymousCopy, LinearLayout.LayoutParams(0, -2, 1f))
        anonymousEntry.addView(label("›", 26f, blue, true))
        p.addView(anonymousEntry)
        p.addView(vSpace(18))
        addSectionTitle(p, "Private conversations", "One-to-one messages remain end-to-end encrypted.")
        p.addView(vSpace(12))
        val ids = chatHistory.filterValues { it.isNotEmpty() }.keys.toList().sortedByDescending { chatHistory[it]?.lastOrNull()?.timestamp ?: 0L }
        if (ids.isEmpty()) {
            val empty = card()
            empty.gravity = Gravity.CENTER_HORIZONTAL
            empty.setPadding(dp(20), dp(28), dp(20), dp(28))
            empty.addView(label("Nothing here yet", 17f, ink, true).apply { gravity = Gravity.CENTER })
            empty.addView(label("Find someone nearby to start your first conversation.", 13f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(18)) })
            empty.addView(primaryButton("Find nearby devices") { ensureBluetoothPermissionsAndStart() })
            p.addView(empty)
        } else {
            ids.forEach { id ->
                val last = chatHistory[id]?.lastOrNull() ?: return@forEach
                val box = card().apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    isClickable = true
                    setOnClickListener { openChat(id) }
                }
                box.addView(AvatarView(this, nameFor(id)), LinearLayout.LayoutParams(dp(46), dp(46)))
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                val nodeId = resolveTargetNode(id)
                val status = identityVerificationStatus(nodeId)
                box.contentDescription = "Conversation with ${nameFor(id)}. Identity status: $status. Double tap to open."
                col.addView(label(nameFor(id), 14f, ink, true))
                col.addView(label(status, 11f, if (status == "Key changed") 0xFFB42318.toInt() else if (status == "Verified") green else muted, status == "Key changed").apply {
                    setPadding(0, dp(4), 0, 0)
                })
                col.addView(label(last.text, 12f, muted).apply {
                    setPadding(0, dp(5), 0, 0)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                box.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                box.addView(label("›", 26f, muted))
                p.addView(box); p.addView(vSpace(9))
            }
        }
        return scrollPage(p)
    }


    private fun buildAnonymousPost(): View {
        val p = page()
        addBrandHeader(p)
        addSectionTitle(p, "Anonymous group submission", "Prepare a post for a Mortalive group without creating or signing into a Mortalive account.")
        p.addView(vSpace(12))

        val explanation = card()
        explanation.addView(label("How this works right now", 14f, ink, true))
        explanation.addView(label(
            "Your text and optional photo, video, or audio attachment are encrypted and saved only on this phone. This is a private draft, not a published post. Mesh relay, moderation approval, and delivery into the destination group remain disabled until the Mortalive team supplies the ingestion endpoint, pinned encryption key, moderation result API, and group-encryption publishing contract.",
            12f, muted
        ).apply { setPadding(0, dp(7), 0, 0); setLineSpacing(dp(3).toFloat(), 1f) })
        p.addView(explanation)
        p.addView(vSpace(12))

        val targetCard = card()
        targetCard.addView(label("DESTINATION GROUP ID / SUBMISSION CODE", 10f, muted, true).apply { letterSpacing = .05f })
        val groupInput = EditText(this).apply {
            setText(anonymousGroupValue)
            textSize = 14f
            setTextColor(ink)
            setSingleLine(true)
            hint = "Paste the group ID or anonymous-post code"
            inputType = InputType.TYPE_CLASS_TEXT
            background = rounded(0xFFF9FAFC.toInt(), 12, border)
            setPadding(dp(13), dp(12), dp(13), dp(12))
            filters = arrayOf(android.text.InputFilter.LengthFilter(128))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    anonymousGroupValue = s?.toString().orEmpty()
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        targetCard.addView(groupInput, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(9) })
        p.addView(targetCard)
        p.addView(vSpace(10))

        val contentCard = card()
        contentCard.addView(label("POST TEXT", 10f, muted, true).apply { letterSpacing = .05f })
        val textInput = EditText(this).apply {
            setText(anonymousTextValue)
            textSize = 14f
            setTextColor(ink)
            hint = "Add context or a message (optional when media is attached)"
            gravity = Gravity.TOP
            minLines = 4
            maxLines = 7
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = rounded(0xFFF9FAFC.toInt(), 12, border)
            setPadding(dp(13), dp(12), dp(13), dp(12))
            filters = arrayOf(android.text.InputFilter.LengthFilter(AnonymousSubmissionPolicy.MAX_TEXT_CODE_POINTS))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    anonymousTextValue = s?.toString().orEmpty()
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        contentCard.addView(textInput, LinearLayout.LayoutParams(-1, dp(116)).apply { topMargin = dp(9) })
        p.addView(contentCard)
        p.addView(vSpace(10))

        val attachmentCard = card()
        attachmentCard.addView(label("OPTIONAL ATTACHMENT", 10f, muted, true).apply { letterSpacing = .05f })
        attachmentCard.addView(secondaryButton("Choose photo, video, or audio") { launchAnonymousAttachmentPicker() }.apply {
            setPadding(dp(8), dp(12), dp(8), dp(9))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        anonymousAttachmentInfo = label(
            anonymousSelectedFile?.let { "Selected · ${it.name} · ${formatBytes(it.length())}" } ?: "No attachment selected",
            12f, muted
        ).apply { setPadding(0, dp(5), 0, 0) }
        attachmentCard.addView(anonymousAttachmentInfo)
        p.addView(attachmentCard)
        p.addView(vSpace(12))

        p.addView(primaryButton("Save encrypted draft on this phone") {
            if (anonymousDraftSaving) return@primaryButton
            anonymousDraftSaving = true
            val groupId = anonymousGroupValue.trim()
            val textValue = anonymousTextValue
            val mime = anonymousSelectedMime
            val selectedFile = anonymousSelectedFile
            Toast.makeText(this, "Encrypting draft on this phone…", Toast.LENGTH_SHORT).show()
            anonymousSubmissionExecutor.execute {
                try {
                    val draft = anonymousSubmissionStore.saveDraft(groupId, textValue, mime, selectedFile)
                    selectedFile?.delete()
                    mainHandler.post {
                        anonymousDraftSaving = false
                        if (anonymousSelectedFile == selectedFile) {
                            anonymousSelectedFile = null
                            anonymousSelectedMime = null
                            anonymousAttachmentInfo?.text = "No attachment selected"
                        }
                        anonymousTextValue = ""
                        Toast.makeText(this, "Encrypted draft saved · ${draft.id.take(8)}", Toast.LENGTH_LONG).show()
                        if (currentScreen == Screen.ANONYMOUS_POST) render()
                    }
                } catch (error: Exception) {
                    mainHandler.post {
                        anonymousDraftSaving = false
                        Toast.makeText(this, error.message ?: "Could not save the encrypted draft.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
        p.addView(vSpace(20))

        addSectionTitle(p, "Saved on this phone", "Drafts are encrypted at rest and expire automatically after 72 hours.")
        p.addView(vSpace(8))
        val drafts = anonymousSubmissionStore.listDrafts()
        if (drafts.isEmpty()) {
            val empty = card()
            empty.addView(label("No anonymous drafts yet", 14f, ink, true))
            empty.addView(label("Saved drafts will appear here. They are not sent to a group until the separate moderation and publishing service is connected.", 12f, muted).apply {
                setPadding(0, dp(6), 0, 0); setLineSpacing(dp(3).toFloat(), 1f)
            })
            p.addView(empty)
        } else {
            drafts.forEach { draft ->
                val row = card()
                row.addView(label("Group · ${draft.groupId}", 13f, ink, true))
                row.addView(label(
                    listOfNotNull(
                        if (draft.text.isNotBlank()) "Text" else null,
                        draft.mimeType?.substringBefore('/')?.replaceFirstChar { it.uppercase() },
                        if (draft.hasAttachment) formatBytes(draft.attachmentBytes) else null
                    ).joinToString(" · ") + " · ${draft.statusLabel}",
                    11f, muted
                ).apply { setPadding(0, dp(5), 0, dp(8)) })
                row.addView(secondaryButton("Delete draft") {
                    anonymousSubmissionStore.deleteDraft(draft.id)
                    render()
                })
                p.addView(row)
                p.addView(vSpace(8))
            }
        }
        return scrollPage(p)
    }

    private fun launchAnonymousAttachmentPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "image/jpeg", "image/png", "image/webp",
                "video/mp4", "video/3gpp",
                "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg",
                "audio/3gpp", "audio/wav", "audio/x-wav"
            ))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_ANONYMOUS_ATTACHMENT)
        } catch (_: Exception) {
            Toast.makeText(this, "No compatible file picker is available.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importAnonymousAttachment(uri: Uri) {
        if (anonymousAttachmentImporting) {
            Toast.makeText(this, "An attachment is already being imported.", Toast.LENGTH_SHORT).show()
            return
        }
        anonymousAttachmentImporting = true
        anonymousAttachmentInfo?.text = "Importing attachment…"
        anonymousSubmissionExecutor.execute {
            var candidate: File? = null
            try {
                val mime = AnonymousSubmissionPolicy.normalizeMime(contentResolver.getType(uri))
                require(AnonymousSubmissionPolicy.isAcceptedMime(mime)) {
                    "Choose a supported photo, video, or audio file."
                }
                val directory = File(cacheDir, "anonymous-source").apply { mkdirs() }
                candidate = File(directory, UUID.randomUUID().toString() + ".tmp")
                var total = 0L
                val input = contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("The selected file could not be opened.")
                input.use { source ->
                    FileOutputStream(candidate!!).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > AnonymousSubmissionPolicy.MAX_ATTACHMENT_BYTES) {
                                throw java.io.IOException("The attachment must be 100 MiB or smaller.")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                if (total <= 0L) throw java.io.IOException("The selected file is empty.")
                val imported = candidate!!
                mainHandler.post {
                    anonymousAttachmentImporting = false
                    if (currentScreen != Screen.ANONYMOUS_POST || isFinishing) {
                        imported.delete()
                        return@post
                    }
                    anonymousSelectedFile?.delete()
                    anonymousSelectedFile = imported
                    anonymousSelectedMime = mime
                    anonymousAttachmentInfo?.text = "Selected · ${imported.name} · ${formatBytes(total)}"
                }
            } catch (error: Exception) {
                candidate?.delete()
                mainHandler.post {
                    anonymousAttachmentImporting = false
                    anonymousAttachmentInfo?.text = "No attachment selected"
                    Toast.makeText(this, error.message ?: "Could not read the selected attachment.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openWorldChat() {
        activeChatId = null
        currentScreen = Screen.WORLD_CHAT
        render()
    }

    private fun buildWorldChat(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(white)
            elevation = dp(2).toFloat()
        }
        top.addView(label("‹", 32f, blue, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Back to conversations"
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener { currentScreen = Screen.CHATS; render() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(label("World Chat", 16f, ink, true))
        titleColumn.addView(label("PUBLIC · MESH BROADCAST", 9.5f, muted, true).apply { letterSpacing = .06f; setPadding(0, dp(4), 0, 0) })
        top.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label("●", 13f, if (connectedTransportIds().isNotEmpty()) green else muted))
        top.minimumHeight = dp(64)
        wrapper.addView(top, LinearLayout.LayoutParams(-1, -2))

        val explainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
            setBackgroundColor(0xFFEAF2FF.toInt())
        }
        explainer.addView(label("Posts travel device to device", 13f, 0xFF174A8B.toInt(), true))
        explainer.addView(label("Every connected device can relay a post to its neighbors, up to 8 hops. This is a local mesh, not a worldwide internet room. Posts are public plaintext and can be read by every device that relays them.", 11.5f, 0xFF46607E.toInt()).apply {
            setPadding(0, dp(4), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        wrapper.addView(explainer, LinearLayout.LayoutParams(-1, -2))

        worldLinkStatus = label(worldLinkStatusText(), 10.5f, muted).apply {
            setPadding(dp(16), dp(8), dp(16), dp(5))
        }
        wrapper.addView(worldLinkStatus)

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            setPadding(dp(16), dp(4), dp(16), dp(10))
        }
        val posts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        worldPostColumn = posts
        worldChatScroll = scroll
        scroll.addView(posts, FrameLayout.LayoutParams(-1, -2))
        wrapper.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        renderWorldTimeline(false)

        val composerShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            setBackgroundColor(white)
        }
        val counter = label("0/500", 10f, muted).apply { gravity = Gravity.END; setPadding(0, 0, dp(4), dp(5)) }
        worldCharCounter = counter
        composerShell.addView(counter, LinearLayout.LayoutParams(-1, -2))
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val composer = EditText(this).apply {
            hint = "Share something with the mesh…"
            textSize = 14f
            setTextColor(ink)
            setHintTextColor(0xFF98A2B3.toInt())
            minLines = 1
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = rounded(0xFFF5F7FB.toInt(), 18, border)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            filters = arrayOf(android.text.InputFilter { source, start, end, dest, dstart, dend ->
                val candidate = StringBuilder(dest).replace(dstart, dend, source.subSequence(start, end).toString()).toString()
                if (WorldChatPolicy.codePointCount(candidate) <= WorldChatPolicy.MAX_CHARACTERS) null else ""
            })
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    val count = WorldChatPolicy.codePointCount(s?.toString().orEmpty())
                    counter.text = count.toString() + "/500"
                    counter.setTextColor(if (count > WorldChatPolicy.MAX_CHARACTERS) 0xFFD92D20.toInt() else muted)
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendWorldChatPost(); true } else false
            }
        }
        worldComposer = composer
        inputRow.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
        val send = TextView(this).apply {
            text = "↑"
            textSize = 23f
            setTextColor(white)
            gravity = Gravity.CENTER
            background = rounded(blue, 15)
            isClickable = true
            isFocusable = true
            setOnClickListener { sendWorldChatPost() }
        }
        inputRow.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(7) })
        composerShell.addView(inputRow)
        composerShell.addView(label("500 characters max · public and signed · no media in World Chat", 10f, muted).apply {
            setPadding(dp(2), dp(6), 0, 0)
        })
        val publicNotice = label("PUBLIC · ANYONE IN RANGE CAN READ", 11f, 0xFFB42318.toInt(), true).apply {
            setPadding(dp(4), dp(8), dp(4), dp(4))
            contentDescription = "Public World Chat. Anyone in range can read posts."
        }
        composerShell.addView(publicNotice, 0)
        worldPostFeedback = label("", 12f, 0xFFB42318.toInt(), true).apply {
            visibility = View.GONE
            setPadding(dp(4), dp(5), dp(4), dp(4))
        }
        composerShell.addView(worldPostFeedback, 1)
        wrapper.addView(composerShell, LinearLayout.LayoutParams(-1, -2))
        return wrapper
    }

    private fun worldLinkStatusText(): String {
        val direct = connectedTransportIds().size
        val relayed = meshRoutes.values.count { it.hops > 1 && hasPeerTransport(it.nextHop) }
        return "Connected links: " + direct + " direct · " + relayed + " relayed routes"
    }

    private fun renderWorldTimeline(scrollToEnd: Boolean = true) {
        val column = worldPostColumn ?: return
        column.removeAllViews()
        val posts = worldChatStore.recent(WorldChatStore.MAX_POSTS)
        if (posts.isEmpty()) {
            val empty = card().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(20), dp(25), dp(20), dp(25))
                addView(label("The world is quiet", 16f, ink, true).apply { gravity = Gravity.CENTER })
                addView(label("Your post will be broadcast to connected neighbors and relayed through the mesh.", 12f, muted).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(7), 0, 0)
                })
            }
            column.addView(empty)
        } else {
            posts.forEach { post ->
                val item = card().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
                val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val author = if (post.authorNodeId == localNodeId) "You" else post.authorName
                heading.addView(label(author, 13f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
                heading.addView(label(
                    java.text.SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(java.util.Date(post.timestamp)),
                    9.5f, muted
                ))
                item.addView(heading)
                item.addView(label(post.text, 14f, ink).apply {
                    setPadding(0, dp(9), 0, dp(5))
                    setLineSpacing(dp(2).toFloat(), 1f)
                })
                item.addView(label("PUBLIC POST · SIGNED BY AUTHOR", 8.5f, muted, true).apply { letterSpacing = .04f })
                column.addView(item)
                column.addView(vSpace(9))
            }
        }
        worldLinkStatus?.text = worldLinkStatusText()
        if (scrollToEnd) worldChatScroll?.post { worldChatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendWorldChatPost() {
        val input = worldComposer ?: return
        val text = input.text.toString().trim()
        if (!WorldChatPolicy.isValidPostText(text)) {
            Toast.makeText(this, "World Chat posts must contain 1–500 characters.", Toast.LENGTH_SHORT).show()
            return
        }
        val json = meshSecurity.createWorldPost(nickname, text)
        if (json == null) {
            Toast.makeText(this, "Could not sign this public post.", Toast.LENGTH_LONG).show()
            return
        }
        val post = WorldChatPost.fromJson(json) ?: run {
            Toast.makeText(this, "Could not prepare this public post.", Toast.LENGTH_LONG).show()
            return
        }
        when (worldChatStore.insertIfNew(post)) {
            WorldChatStore.InsertResult.RATE_LIMITED -> {
                worldPostFeedback?.apply {
                    this.text = "Rate limit reached · wait before posting again (maximum 5 posts per minute)."
                    visibility = View.VISIBLE
                }
                Toast.makeText(this, "World Chat allows up to 5 posts per minute.", Toast.LENGTH_LONG).show()
                return
            }
            WorldChatStore.InsertResult.STORAGE_ERROR -> {
                worldPostFeedback?.apply {
                    this.text = "This post could not be saved safely, so it was not broadcast."
                    visibility = View.VISIBLE
                }
                Toast.makeText(this, "The post could not be saved safely. It was not broadcast.", Toast.LENGTH_LONG).show()
                return
            }
            WorldChatStore.InsertResult.DUPLICATE -> return
            WorldChatStore.InsertResult.INSERTED -> Unit
        }
        worldPostFeedback?.visibility = View.GONE
        input.text.clear()
        renderWorldTimeline()
        val sent = broadcastWorldPost(post, null)
        val toast = if (sent == 0) "Saved on this device. It will sync when a mesh link is available."
            else "Broadcast to " + sent + " connected links."
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    private fun worldPostFrame(post: WorldChatPost, ttl: Int): String =
        WORLD_CHAT_PREFIX + ttl + "|" + post.toJson().toString()

    private fun broadcastWorldPost(post: WorldChatPost, excludedPeer: String?): Int {
        val frame = worldPostFrame(post, WORLD_CHAT_TTL)
        if (frame.toByteArray(Charsets.UTF_8).size > MAX_WORLD_CHAT_FRAME_BYTES) return 0
        val neighbors = connectedTransportIds().filter { it != excludedPeer }
        var sent = 0
        neighbors.forEach { peer ->
            try { sendTextToPeer(peer, frame); sent++ } catch (_: Exception) { }
        }
        return sent
    }

    /** A small, bounded catch-up burst lets a newly connected mesh island converge on recent posts. */
    private fun sendWorldHistoryTo(peerId: String) {
        if (!hasPeerTransport(peerId)) return
        worldChatStore.recentForSync(WorldChatStore.SYNC_POSTS).forEach { post ->
            try {
                val frame = worldPostFrame(post, WORLD_CHAT_TTL)
                if (frame.toByteArray(Charsets.UTF_8).size <= MAX_WORLD_CHAT_FRAME_BYTES) sendTextToPeer(peerId, frame)
            } catch (_: Exception) { }
        }
    }

    private fun confirmDeleteVaultItem(item: PrivateVault.Item) {
        AlertDialog.Builder(this)
            .setTitle("Delete private media?")
            .setMessage("${item.fileName} will be removed from this device's private vault.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (!privateVault.delete(item)) {
                    Toast.makeText(this, "The vault item could not be deleted.", Toast.LENGTH_LONG).show()
                }
                render()
            }
            .show()
    }

    /** One reusable bottom sheet explains the private-media transport rule without repeating it on every screen. */
    private fun showMediaTravelInfo() {
        val dialog = Dialog(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(24))
            background = rounded(white, 22, border)
            addView(label("How media travels", 20f, ink, true))
            addView(label("PRIVATE CHAT", 11f, blue, true).apply { setPadding(0, dp(16), 0, dp(5)) })
            addView(label("Text uses an available nearby encrypted link. Photos use Bluetooth LE only. Videos use Wi-Fi Direct only. Media is sent directly to its intended recipient and is never relayed through other mesh devices.", 13f, muted).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            })
            addView(label("PRIVATE VAULT", 11f, blue, true).apply { setPadding(0, dp(14), 0, dp(5)) })
            addView(label("If the required direct link is unavailable, encrypted media stays queued in the Private Vault until the recipient can reconnect. Previews are available only after the Vault is unlocked.", 13f, muted).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            })
            addView(label("SEPARATE SPACES", 11f, blue, true).apply { setPadding(0, dp(14), 0, dp(5)) })
            addView(label("World Chat posts are public to connected mesh devices. Anonymous group submissions remain encrypted local drafts; they are not published or uploaded.", 13f, muted).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            })
            addView(vSpace(16))
            addView(primaryButton("Got it") { dialog.dismiss() })
        }
        dialog.setContentView(sheet)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(rounded(white, 22))
            setLayout(-1, -2)
            setGravity(Gravity.BOTTOM)
        }
    }

    private fun buildVault(): View {
        val p = page()
        addBrandHeader(p, if (vaultUnlocked) "Lock" else "Unlock") {
            if (vaultUnlocked) { vaultUnlocked = false; render() }
            else unlockOrSetVaultPin()
        }
        addSectionTitle(p, "Private Vault", "Your photos and videos stay inside Morta Mesh.")
        p.addView(vSpace(14))
        if (privateVault.isUnavailable()) {
            val unavailable = card()
            unavailable.addView(label("Vault temporarily unavailable", 16f, ink, true))
            unavailable.addView(label("The secure key or vault index could not be read, or encrypted files exist without readable index metadata. Your encrypted files have not been changed or deleted. Retry after restarting; do not clear app data or uninstall while recovery is needed.", 13f, muted).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(10), 0, dp(14))
            })
            unavailable.addView(primaryButton("Retry") { privateVault.retryLoad(); render() })
            p.addView(unavailable)
            return scrollPage(p)
        }
        if (!vaultUnlocked) {
            val locked = card()
            locked.gravity = Gravity.CENTER_HORIZONTAL
            locked.setPadding(dp(22), dp(28), dp(22), dp(24))
            locked.addView(TextView(this).apply {
                text = "▣"
                textSize = 38f
                gravity = Gravity.CENTER
                setTextColor(blue)
            }, LinearLayout.LayoutParams(-1, dp(54)))
            locked.addView(label("Encrypted, app-private storage", 16f, ink, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
            locked.addView(label("Captured and received media is encrypted with Android Keystore AES-GCM and stored under this app's private files. Nothing is added to the source or recipient photo gallery. Enter a PIN to view or play items here.", 13f, muted).apply {
                gravity = Gravity.CENTER
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(10), 0, dp(18))
            })
            locked.addView(primaryButton(if (privateVault.hasPin()) "Unlock Private Vault" else "Set Vault PIN") { unlockOrSetVaultPin() })
            p.addView(locked)
            return scrollPage(p)
        }

        val items = try {
            privateVault.allItems()
        } catch (_: Exception) {
            val failure = card()
            failure.addView(label("Vault could not be opened safely", 15f, ink, true))
            failure.addView(label("The encrypted index was not changed. Close and reopen the app; if this repeats, keep the encrypted files intact and collect the crash log.", 13f, muted).apply { setPadding(0, dp(7), 0, 0) })
            failure.addView(secondaryButton("Retry vault") { render() })
            p.addView(failure)
            return scrollPage(p)
        }
        val summary = card()
        summary.addView(label("${items.size} private item${if (items.size == 1) "" else "s"}", 15f, ink, true))
        summary.addView(label("Encrypted locally · PIN-gated viewer · not visible in Gallery", 12f, muted).apply { setPadding(0, dp(5), 0, 0) })
        p.addView(summary)
        p.addView(vSpace(14))
        if (items.isEmpty()) {
            val empty = card()
            empty.gravity = Gravity.CENTER_HORIZONTAL
            empty.setPadding(dp(18), dp(24), dp(18), dp(24))
            empty.addView(label("Your vault is empty", 16f, ink, true).apply { gravity = Gravity.CENTER })
            empty.addView(label("No private media yet. Captured and received items are encrypted here and never added to your Gallery.", 13f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
            p.addView(empty)
            p.addView(vSpace(10))
            p.addView(secondaryButton("How media travels  ⓘ") { showMediaTravelInfo() })
        } else {
            items.forEach { item ->
                val row = card().apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(13), dp(13), dp(13), dp(13))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { openPrivateMedia(item) }
                    setOnLongClickListener {
                        confirmDeleteVaultItem(item)
                        true
                    }
                }
                row.addView(TextView(this).apply {
                    text = if (item.mimeType.startsWith("video/")) "▶" else "▧"
                    textSize = 22f
                    setTextColor(blue)
                    gravity = Gravity.CENTER
                    background = rounded(blueLight, 14)
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
                col.addView(label(item.fileName, 14f, ink, true))
                col.addView(label("${if (item.mimeType.startsWith("video/")) "Video" else "Photo"} · ${formatBytes(item.size)}", 12f, muted).apply { setPadding(0, dp(5), 0, 0) })
                val transferLabel = when {
                    item.direction == "Sent" && item.transferState == "DELIVERED" -> "Delivered to ${item.peerName}"
                    item.direction == "Sent" && item.transferState == "SENDING" -> "Sending to ${item.peerName} · waiting for receipt"
                    item.direction == "Sent" && item.transferState == "QUEUED" -> "Queued for ${item.peerName} · ${if (item.mimeType.startsWith("video/")) "Wi-Fi only" else "Bluetooth"}"
                    item.direction == "Sent" && item.transferState.isBlank() && item.recipientId.isNullOrBlank() -> "Older item · recipient needs confirmation"
                    item.direction == "Sent" && item.transferState.isBlank() -> "Previously sent · delivery not confirmed"
                    else -> "${item.direction} media"
                }
                col.addView(label(transferLabel, 11f, if (item.transferState == "QUEUED") blue else muted).apply { setPadding(0, dp(3), 0, 0) })
                col.addView(label(java.text.SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(java.util.Date(item.createdAt)), 11f, muted).apply { setPadding(0, dp(3), 0, 0) })
                col.addView(secondaryButton("Delete") { confirmDeleteVaultItem(item) }.apply {
                    setTextColor(0xFFB42318.toInt())
                    background = rounded(0xFFFFEDEC.toInt(), 10)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                })
                if (item.direction == "Sent" && item.recipientId.isNullOrBlank() &&
                    item.transferId.isNullOrBlank() && item.transferState.isBlank()) {
                    col.addView(secondaryButton("Retry delivery") { retryLegacyVaultItem(item) }.apply {
                        setPadding(dp(8), dp(5), dp(8), dp(5))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(6)
                    })
                }
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(label("›", 24f, muted))
                p.addView(row)
                p.addView(vSpace(9))
            }
        }
        p.addView(vSpace(8))
        p.addView(secondaryButton("Lock vault now") { vaultUnlocked = false; render() })
        return scrollPage(p)
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    private fun buildSettings(): View {
        val p = page()
        addBrandHeader(p)
        addSectionTitle(p, "Settings", "Make Morta Mesh feel like yours.")
        p.addView(vSpace(16))
        val profile = card()
        profile.addView(label("YOUR DISPLAY NAME", 10f, muted, true).apply { letterSpacing = .08f })
        p.addView(profile)
        val nameInput = EditText(this).apply {
            setText(nickname)
            textSize = 15f
            setTextColor(ink)
            setSingleLine(true)
            hint = "Choose a nickname"
            background = rounded(0xFFF9FAFC.toInt(), 12, border)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(android.text.InputFilter.LengthFilter(20))
        }
        profile.addView(nameInput, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        profile.addView(vSpace(12))
        profile.addView(primaryButton("Save display name") {
            val newName = nameInput.text.toString().trim().ifBlank { "Morta user" }
            nickname = newName.take(20)
            preferences.edit().putString("nickname", nickname).apply()
            if (meshRunning) restartAdvertising()
            Toast.makeText(this, "Display name saved", Toast.LENGTH_SHORT).show()
            render()
        })
        p.addView(vSpace(12))

        val relayMode = card()
        val carryEnabled = preferences.getBoolean("anonymous_relay_carry_enabled", false)
        relayMode.addView(label("Peer Mode · encrypted carry", 15f, ink, true))
        relayMode.addView(label("Opt in to temporarily store and forward other people's anonymous Mortalive submissions as opaque ciphertext over Wi-Fi Direct. Sending your own submission will not require this setting. Relays do not receive the group destination or media decryption key. The queue is capped at 250 MiB / 20 transfers and expires after the package expiry (maximum 72 hours).", 12f, muted).apply {
            setPadding(0, dp(7), 0, dp(9)); setLineSpacing(dp(3).toFloat(), 1f)
        })
        relayMode.addView(primaryButton(if (carryEnabled) "Pause encrypted carry" else "Enable encrypted carry") {
            val enabled = !preferences.getBoolean("anonymous_relay_carry_enabled", false)
            preferences.edit().putBoolean("anonymous_relay_carry_enabled", enabled).commit()
            announceAnonymousRelayModeToPeers()
            Toast.makeText(this, if (enabled) "Peer Mode enabled · ciphertext only" else "Peer Mode paused", Toast.LENGTH_SHORT).show()
            render()
        })
        val relayCount = anonymousRelayStore.listTransfers().size
        val relayBytes = anonymousRelayStore.queuedBytes()
        relayMode.addView(label(
            "$relayCount queued transfer${if (relayCount == 1) "" else "s"} · ${formatBytes(relayBytes)} stored",
            11f, if (relayCount > 0) ink else muted, relayCount > 0
        ).apply { setPadding(0, dp(9), 0, dp(5)) })
        relayMode.addView(secondaryButton("Clear stored relay packages") {
            AlertDialog.Builder(this)
                .setTitle("Clear encrypted relay cache?")
                .setMessage("This deletes the encrypted packages currently stored on this phone for carry-forward. It does not delete your private chats or anonymous drafts.")
                .setNegativeButton("Keep them", null)
                .setPositiveButton("Clear cache") { _, _ ->
                    val cleared = anonymousRelayStore.clearAll()
                    Toast.makeText(this, if (cleared) "Relay cache cleared" else "Some relay files could not be removed", Toast.LENGTH_LONG).show()
                    render()
                }
                .show()
        }.apply { setPadding(dp(8), dp(7), dp(8), dp(7)) })
        relayMode.addView(label("Internet gateway upload is intentionally unavailable until Mortalive provides the ingestion API and key contract. This device is not uploading or publishing submissions yet.", 11f, muted).apply {
            setPadding(0, dp(9), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
        })
        p.addView(relayMode)
        p.addView(vSpace(12))

        val privacy = card()
        privacy.addView(label("Privacy by design", 15f, ink, true))
        privacy.addView(label("No sign-up is required. Chat and private media payloads are encrypted end-to-end for trusted identities. Devices can relay ciphertext over Wi-Fi Direct or Bluetooth. Fingerprints use first-contact pinning; compare them with contacts for stronger protection.", 13f, muted).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(dp(3).toFloat(), 1f) })
        p.addView(privacy)
        p.addView(vSpace(12))
        val info = card()
        info.addView(label("Morta Mesh · v${appVersionName()}", 14f, ink, true))
        info.addView(label("Offline-first Android messaging with Wi-Fi Direct and Bluetooth LE.", 12f, muted).apply { setPadding(0, dp(7), 0, 0) })
        p.addView(info)
        return scrollPage(p)
    }

    private fun buildChat(): View {
        val peerId = activeChatId
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(this@MainActivity.background) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(white)
            elevation = dp(2).toFloat()
        }
        top.addView(label("‹", 32f, blue, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Back to conversations"
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener { activeChatId = null; currentScreen = Screen.CHATS; render() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(AvatarView(this, peerId?.let { nameFor(it) } ?: "M"), LinearLayout.LayoutParams(dp(40), dp(40)))
        val headerColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        headerColumn.addView(label(peerId?.let { nameFor(it) } ?: "Conversation", 15f, ink, true))
        val isMeshChat = peerId?.startsWith(MESH_PREFIX) == true
        val meshRoute = if (isMeshChat) peerId?.removePrefix(MESH_PREFIX)?.let { meshRoutes[it] } else null
        val transportPeer = peerId?.takeUnless { it.startsWith(MESH_PREFIX) }
        val isWifiChat = transportPeer?.startsWith(WIFI_PREFIX) == true || (meshRoute?.nextHop?.startsWith(WIFI_PREFIX) == true)
        val chatConnected = if (isMeshChat) meshRoute != null && hasPeerTransport(meshRoute.nextHop) else if (isWifiChat) transportPeer?.let { wifiWriters.containsKey(it) } == true || meshRoute != null else transportPeer != null && (clientReady.contains(transportPeer) || subscribedDevices.contains(transportPeer))
        val connectionLabel = if (isMeshChat && meshRoute != null && meshRoute.hops > 1) "Relayed · ${meshRoute.hops} hops · E2E encrypted"
            else if (chatConnected) if (isWifiChat) "Connected · Wi-Fi Direct · E2E encrypted" else "Connected · Bluetooth · E2E encrypted"
            else if (isMeshChat) "Waiting for a mesh route" else if (isWifiChat) "Wi-Fi Direct · connecting" else "Bluetooth LE · nearby"
        headerColumn.addView(label(connectionLabel, 11f, if (chatConnected) green else muted).apply { setPadding(0, dp(4), 0, 0) })
        val peerNodeId = peerId?.let { resolveTargetNode(it) }
        val verification = identityVerificationStatus(peerNodeId)
        headerColumn.addView(label(
            verification,
            11f,
            when (verification) { "Verified" -> green; "Key changed" -> 0xFFB42318.toInt(); else -> muted },
            verification == "Key changed"
        ).apply { setPadding(0, dp(3), 0, 0) })
        val keyChanged = verification == "Key changed"
        top.addView(headerColumn, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label("⋯", 24f, muted).apply {
            gravity = Gravity.CENTER
            contentDescription = "Peer identity and safety details"
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener { showPeerInfo() }
        })
        top.minimumHeight = dp(66)
        wrapper.addView(top, LinearLayout.LayoutParams(-1, -2))
        if (keyChanged) {
            val warning = card().apply {
                background = rounded(0xFFFFEDEC.toInt(), 12, 0xFFFDA29B.toInt())
                addView(label("KEY CHANGED · CHAT BLOCKED", 12f, 0xFFB42318.toInt(), true))
                addView(label("The peer is advertising a different identity key. Do not send messages or media. Confirm their identity through another trusted channel; the app will not silently accept the new key.", 12f, 0xFF912018.toInt()).apply {
                    setPadding(0, dp(6), 0, 0)
                    setLineSpacing(dp(2).toFloat(), 1f)
                })
            }
            wrapper.addView(warning, LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(8)
            })
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatColumn = messages
        chatScroll = scroll
        val history = peerId?.let { chatHistory[it] }.orEmpty()
        if (history.isEmpty()) {
            val intro = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(36), dp(18), dp(30))
            }
            intro.addView(AvatarView(this, peerId?.let { nameFor(it) } ?: "M"), LinearLayout.LayoutParams(dp(58), dp(58)))
            intro.addView(label(peerId?.let { nameFor(it) } ?: "Nearby user", 17f, ink, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
            intro.addView(label("Messages use end-to-end encryption and can travel directly or through participating Morta Mesh relays.", 12f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) })
            messages.addView(intro)
        } else history.forEach { messages.addView(messageBubble(it)) }
        scroll.addView(messages, FrameLayout.LayoutParams(-1, -2))
        wrapper.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val composerShell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(white)
        }
        val composer = EditText(this).apply {
            hint = if (keyChanged) "Messaging paused · identity key changed" else "Message nearby…"
            isEnabled = !keyChanged
            alpha = if (keyChanged) 0.55f else 1f
            textSize = 14f
            setTextColor(ink)
            setHintTextColor(0xFF98A2B3.toInt())
            maxLines = 4
            minLines = 1
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = rounded(0xFFF5F7FB.toInt(), 18, border)
            setPadding(dp(15), dp(12), dp(15), dp(12))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendComposerMessage(); true } else false
            }
        }
        currentComposer = composer
        composerShell.addView(mediaActionButton("▧", "Take photo") { launchPhotoCapture() }.apply { isEnabled = !keyChanged }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(4) })
        composerShell.addView(mediaActionButton("●", "Record video") { launchVideoCapture() }.apply { isEnabled = !keyChanged }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(5) })
        composerShell.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
        val send = TextView(this).apply {
            text = "↑"
            textSize = 25f
            setTextColor(white)
            gravity = Gravity.CENTER
            background = rounded(blue, 16)
            contentDescription = "Send message"
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            isEnabled = !keyChanged
            alpha = if (keyChanged) 0.55f else 1f
            setOnClickListener { sendComposerMessage() }
        }
        composerShell.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(5) })
        wrapper.addView(composerShell, LinearLayout.LayoutParams(-1, -2))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return wrapper
    }

    private fun mediaActionButton(symbol: String, description: String, action: () -> Unit): TextView = label(symbol, 21f, blue, true).apply {
        gravity = Gravity.CENTER
        contentDescription = description
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        background = rounded(blueLight, 13)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun launchPhotoCapture() = launchCapture(video = false)
    private fun launchVideoCapture() = launchCapture(video = true)

    private fun launchCapture(video: Boolean) {
        if (isContactKeyChanged(activeChatId)) {
            Toast.makeText(this, "Media blocked: this contact's identity key changed.", Toast.LENGTH_LONG).show()
            return
        }
        if (activeChatId.isNullOrBlank()) {
            Toast.makeText(this, "Open a conversation before capturing media.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, CameraCaptureActivity::class.java)
            .putExtra(CameraCaptureActivity.EXTRA_VIDEO_MODE, video)
        pendingCaptureMime = if (video) "video/mp4" else "image/jpeg"
        try { startActivityForResult(intent, if (video) REQUEST_RECORD_VIDEO else REQUEST_TAKE_PHOTO) }
        catch (e: Exception) {
            Toast.makeText(this, "Private camera could not be opened: ${e.message ?: "camera unavailable"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun processCapturedMedia(plainFile: File, mimeType: String) {
        val peerId = activeChatId
        if (peerId.isNullOrBlank()) {
            plainFile.delete()
            Toast.makeText(this, "Open a conversation to send media.", Toast.LENGTH_SHORT).show()
            return
        }
        val isVideo = mimeType.startsWith("video/")
        val peerName = nameFor(peerId)
        val transferId = UUID.randomUUID().toString()
        Thread {
            var preparedFile = plainFile
            try {
                var preparedMime = mimeType
                if (!isVideo) {
                    val compressed = compressPhotoLosslessly(plainFile)
                    preparedFile = compressed.first
                    preparedMime = compressed.second
                }
                val extension = when {
                    preparedMime == "image/webp" -> "webp"
                    preparedMime == "image/png" -> "png"
                    preparedMime.startsWith("video/") -> "mp4"
                    else -> "jpg"
                }
                val fileName = "morta_${if (isVideo) "video" else "photo"}_${System.currentTimeMillis()}.$extension"
                val recipient = stableRecipientId(peerId)
                val item = privateVault.saveFromFile(
                    preparedFile, fileName, preparedMime, "Sent", peerName,
                    recipient, peerId, transferId, "QUEUED"
                ) ?: throw java.io.IOException("The encrypted vault item could not be saved")
                if (preparedFile != plainFile) preparedFile.delete()
                plainFile.delete()

                val requiredTransport = if (preparedMime.startsWith("video/")) "Wi-Fi Direct" else "Bluetooth LE"
                runOnUiThread {
                    appendMessage(peerId, ChatMessage(
                        text = if (preparedMime.startsWith("video/")) "Video" else "Photo",
                        mine = true,
                        mediaMimeType = preparedMime,
                        mediaSize = item.size,
                        deliveryState = "QUEUED",
                        requiredTransport = requiredTransport,
                        transferId = transferId
                    ))
                }

                val target = chooseMediaTransport(recipient, preparedMime)
                if (target == null) {
                    runOnUiThread {
                        if (isVideo) prepareWifiForVideo()
                        Toast.makeText(
                            this,
                            "${if (isVideo) "Video needs Wi-Fi Direct" else "Photo uses Bluetooth"}; the encrypted item is queued for $peerName. It will send when that recipient is directly connected on that link type (media is never relayed).",
                            Toast.LENGTH_LONG
                        ).show()
                        if (currentScreen == Screen.VAULT) render()
                    }
                    return@Thread
                }
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (isVideo) "Video queued over Wi-Fi Direct only; waiting for recipient receipt…"
                        else "Photo encoded losslessly where smaller and queued over Bluetooth only…",
                        Toast.LENGTH_LONG
                    ).show()
                }
                sendMediaFile(item, target)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Media could not be queued: ${e.message ?: "private storage error"}. Check available device storage.", Toast.LENGTH_LONG).show()
                }
            } finally {
                if (preparedFile != plainFile) preparedFile.delete()
                plainFile.delete()
                if (currentScreen == Screen.VAULT) runOnUiThread { render() }
            }
        }.apply { name = "MortaMesh-MediaQueue"; isDaemon = true; start() }
    }

    /**
     * Encode a captured photo with a lossless format: WebP lossless on Android 11+, PNG earlier.
     * If lossless output is larger or cannot be produced safely, retain the original unchanged.
     */
    private fun compressPhotoLosslessly(source: File): Pair<File, String> {
        if (!source.isFile || source.length() <= 0L) return source to "image/jpeg"
        val format: Bitmap.CompressFormat
        val mime: String
        val extension: String
        if (Build.VERSION.SDK_INT >= 30) {
            format = Bitmap.CompressFormat.WEBP_LOSSLESS
            mime = "image/webp"
            extension = "webp"
        } else {
            format = Bitmap.CompressFormat.PNG
            mime = "image/png"
            extension = "png"
        }
        val outputFile = File(File(cacheDir, "morta-capture").apply { mkdirs() }, "lossless_${UUID.randomUUID()}.$extension")
        var sourceBitmap: Bitmap? = null
        var uprightBitmap: Bitmap? = null
        return try {
            sourceBitmap = BitmapFactory.decodeFile(source.absolutePath) ?: return source to "image/jpeg"
            val orientation = try {
                ExifInterface(source.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(270f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(90f) }
            }
            uprightBitmap = if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
                sourceBitmap
            } else Bitmap.createBitmap(sourceBitmap, 0, 0, sourceBitmap.width, sourceBitmap.height, matrix, true)
            FileOutputStream(outputFile).use { output ->
                if (!uprightBitmap.compress(format, 100, output)) throw java.io.IOException("Lossless photo compression failed")
                output.fd.sync()
            }
            if (outputFile.length() >= source.length()) {
                outputFile.delete()
                source to "image/jpeg"
            } else outputFile to mime
        } catch (_: OutOfMemoryError) {
            outputFile.delete()
            source to "image/jpeg"
        } catch (_: Exception) {
            outputFile.delete()
            source to "image/jpeg"
        } finally {
            if (uprightBitmap != null && uprightBitmap !== sourceBitmap && !uprightBitmap.isRecycled) uprightBitmap.recycle()
            if (sourceBitmap != null && !sourceBitmap.isRecycled) sourceBitmap.recycle()
        }
    }

    private fun nodeForTransport(transportId: String): String? =
        synchronized(physicalNodeByTransport) { physicalNodeByTransport[transportId] }

    private fun stableRecipientId(peerId: String): String {
        val nodeId = resolveTargetNode(peerId)?.takeIf { it.isNotBlank() && it != localNodeId }
        return nodeId?.let { MESH_PREFIX + it } ?: peerId
    }

    /**
     * Media never travels through relays: relays cannot see (end-to-end encrypted) that a frame is
     * media, so they could forward it over the wrong radio. A direct link to the recipient on the
     * required transport is the only acceptable path; otherwise the item stays queued.
     */
    private fun chooseMediaTransport(peerId: String, mimeType: String): String? {
        val required = MediaTransferPolicy.requiredTransport(mimeType)
        val nodeId = resolveTargetNode(peerId) ?: return null
        val bluetoothReady: (String) -> Boolean = { id ->
            !id.startsWith(WIFI_PREFIX) &&
                (clientReady.contains(id) || (serverDevices.containsKey(id) && subscribedDevices.contains(id)))
        }
        return connectedTransportIds().firstOrNull { id ->
            nodeForTransport(id) == nodeId && when (required) {
                MediaTransferPolicy.RequiredTransport.WIFI_DIRECT -> id.startsWith(WIFI_PREFIX) && wifiWriters.containsKey(id)
                MediaTransferPolicy.RequiredTransport.BLUETOOTH_LE -> !id.startsWith(MESH_PREFIX) && bluetoothReady(id)
            }
        }
    }

    private fun sendMediaFile(item: PrivateVault.Item, targetPeerId: String) {
        val recipientId = item.recipientId ?: return
        val transferId = item.transferId ?: return
        val required = MediaTransferPolicy.requiredTransport(item.mimeType)
        val wifi = targetPeerId.startsWith(WIFI_PREFIX)
        if ((required == MediaTransferPolicy.RequiredTransport.WIFI_DIRECT) != wifi) {
            privateVault.updateTransferState(item.id, "QUEUED")
            return
        }
        if (!activeMediaTransfers.add(item.id)) return
        val ackLatch = CountDownLatch(1)
        mediaAckWaiters[transferId] = ackLatch
        Thread {
            var failure: Exception? = null
            try {
                if (privateVault.updateTransferState(item.id, "SENDING") == null) {
                    throw java.io.IOException("Could not persist transfer state in the vault")
                }
                updateChatMediaState(item.chatPeerId, transferId, "SENDING")
                val chunkSize = if (wifi) MEDIA_WIFI_CHUNK_BYTES else MEDIA_BLE_CHUNK_BYTES
                val start = listOf(
                    MEDIA_WIRE_PREFIX + "S", transferId,
                    encodeWire(item.fileName), encodeWire(item.mimeType), item.size.toString()
                ).joinToString("|")
                if (!sendMediaWireText(recipientId, targetPeerId, start)) throw java.io.IOException("Secure media route is unavailable")
                privateVault.openDecryptedInput(item)?.use { input ->
                    val buffer = ByteArray(chunkSize)
                    var index = 0
                    var totalSent = 0L
                    while (true) {
                        if (ackLatch.count == 0L) break
                        val count = input.read(buffer)
                        if (count < 0) break
                        totalSent += count
                        if (totalSent > item.size || totalSent > PrivateVault.MAX_MEDIA_BYTES) throw java.io.IOException("Vault item size does not match its metadata")
                        val slice = if (count == buffer.size) buffer else buffer.copyOf(count)
                        val encoded = Base64.encodeToString(slice, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                        if (!sendMediaWireText(recipientId, targetPeerId, "${MEDIA_WIRE_PREFIX}C|$transferId|$index|$encoded")) {
                            throw java.io.IOException("Secure media route was interrupted")
                        }
                        index++
                        if (!wifi && index % 32 == 0) waitForBluetoothMediaQueue(targetPeerId)
                    }
                    if (totalSent != item.size && ackLatch.count != 0L) throw java.io.IOException("Vault item was incomplete")
                } ?: throw java.io.IOException("Private Vault item could not be opened")
                if (ackLatch.count != 0L &&
                    !sendMediaWireText(recipientId, targetPeerId, "${MEDIA_WIRE_PREFIX}E|$transferId")) {
                    throw java.io.IOException("Secure media route was interrupted")
                }
                if (!awaitMediaReceipt(ackLatch, targetPeerId, TimeUnit.MINUTES.toMillis(5))) {
                    throw java.io.IOException("Recipient link went offline or its delivery receipt timed out")
                }
                if (privateVault.transferById(transferId)?.transferState != "DELIVERED") {
                    throw java.io.IOException("Receipt could not be committed to the sender's vault")
                }
            } catch (e: Exception) {
                failure = e
                if (privateVault.transferById(transferId)?.transferState != "DELIVERED") privateVault.updateTransferState(item.id, "QUEUED")
            } finally {
                mediaAckWaiters.remove(transferId, ackLatch)
                activeMediaTransfers.remove(item.id)
                val delivered = privateVault.transferById(transferId)?.transferState == "DELIVERED"
                runOnUiThread {
                    if (delivered && failure == null) {
                        updateChatMediaState(item.chatPeerId, transferId, "DELIVERED")
                        Toast.makeText(this, "${if (item.mimeType.startsWith("video/")) "Video" else "Photo"} delivered to ${item.peerName}.", Toast.LENGTH_LONG).show()
                    } else if (!delivered && failure != null) {
                        updateChatMediaState(item.chatPeerId, transferId, "QUEUED")
                        Toast.makeText(this, "Media remains queued for ${item.peerName}: ${failure.message ?: "connection interrupted"}. It will retry when the recipient is reachable.", Toast.LENGTH_LONG).show()
                    }
                    if (currentScreen == Screen.VAULT) render()
                    wifiAutoTick()
                }
            }
        }.apply { name = "MortaMesh-MediaSend"; isDaemon = true; start() }
    }

    private fun awaitMediaReceipt(latch: CountDownLatch, transportPeerId: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (latch.count > 0L && !Thread.currentThread().isInterrupted) {
            if (!hasPeerTransport(transportPeerId)) return false
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return latch.count == 0L
            if (latch.await(minOf(remaining, TimeUnit.SECONDS.toMillis(5)), TimeUnit.MILLISECONDS)) return true
        }
        return latch.count == 0L
    }

    private fun retryQueuedMedia() {
        if (!::privateVault.isInitialized || isFinishing || isDestroyed) return
        val pending = try { privateVault.pendingTransfers() } catch (_: Exception) { return }
        pending.forEach { item ->
            val recipient = item.recipientId ?: return@forEach
            if (activeMediaTransfers.contains(item.id)) return@forEach
            val target = chooseMediaTransport(recipient, item.mimeType) ?: return@forEach
            sendMediaFile(item, target)
        }
    }

    private fun retryLegacyVaultItem(item: PrivateVault.Item) {
        // Older vault records don't contain a recipient ID or delivery receipt. Never guess silently.
        val possibleIds = linkedSetOf<String>()
        possibleIds.addAll(chatHistory.keys)
        possibleIds.addAll(peerNames.keys)
        possibleIds.addAll(nearbyPeers.keys)
        wifiPeers.values.forEach { possibleIds.add(wifiChatId(it.deviceAddress)) }
        meshRoutes.keys.forEach { possibleIds.add(MESH_PREFIX + it) }
        val candidates = possibleIds.mapNotNull { id ->
            val displayedName = nameFor(id).trim()
            if (displayedName.isBlank() || !displayedName.equals(item.peerName.trim(), ignoreCase = true)) return@mapNotNull null
            val recipient = stableRecipientId(id)
            if (recipient.isBlank() || recipient == MESH_PREFIX + localNodeId) return@mapNotNull null
            Triple(recipient, id, displayedName)
        }.distinctBy { it.first }

        if (candidates.isEmpty()) {
            Toast.makeText(this, "No matching conversation for ${item.peerName} is known yet. Reopen that conversation after the recipient appears nearby, then retry.", Toast.LENGTH_LONG).show()
            return
        }
        val confirm: (Int) -> Unit = { selected ->
            val candidate = candidates.getOrNull(selected)
            if (candidate != null) {
                AlertDialog.Builder(this)
                    .setTitle("Retry old media?")
                    .setMessage("Queue this ${if (item.mimeType.startsWith("video/")) "video" else "photo"} for ${candidate.third}? This older item has no delivery receipt, so retrying could create a duplicate if the earlier send actually arrived.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Queue retry") { _, _ ->
                        val attached = privateVault.attachPendingRecipient(
                            item.id, candidate.first, candidate.second, UUID.randomUUID().toString()
                        )
                        if (attached == null) {
                            Toast.makeText(this, "Could not queue this item. Its encrypted vault copy was preserved.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "Queued for ${candidate.third}. It will send over ${if (item.mimeType.startsWith("video/")) "Wi-Fi Direct" else "Bluetooth"} when available.", Toast.LENGTH_LONG).show()
                            retryQueuedMedia()
                            render()
                        }
                    }.show()
            }
        }
        if (candidates.size == 1) {
            confirm(0)
        } else {
            val entries = candidates.map { (recipient, chatId, name) ->
                val route = if (recipient.startsWith(MESH_PREFIX)) meshRoutes[recipient.removePrefix(MESH_PREFIX)] else null
                "$name · " + when {
                    chatId.startsWith(WIFI_PREFIX) -> "Wi-Fi Direct"
                    route != null && route.hops > 1 -> "Mesh · ${route.hops} hops"
                    else -> "Bluetooth / nearby"
                }
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Choose the original recipient")
                .setMessage("Delivery wasn't recorded for this old item. Choose carefully; retrying may duplicate an earlier successful send.")
                .setItems(entries) { _, which -> confirm(which) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun recoverLegacyQueuedMedia() {
        // A previous process may have died between writing "SENDING" and receiving an ACK.
        val oldQueue = try { privateVault.pendingTransfers() } catch (_: Exception) { emptyList() }
        oldQueue.filter { it.transferState == "SENDING" }.forEach { privateVault.updateTransferState(it.id, "QUEUED") }

        // Earlier versions didn't persist recipients; recover only from a unique, time-correlated unsent marker.
        val legacyItems = try {
            privateVault.allItems().filter {
                it.direction == "Sent" && it.recipientId.isNullOrBlank() && it.transferId.isNullOrBlank() && it.transferState.isBlank()
            }
        } catch (_: Exception) { return }
        var recovered = 0
        legacyItems.forEach { item ->
            val marker = if (item.mimeType.startsWith("video/")) "Video saved in Private Vault · not sent" else "Photo saved in Private Vault · not sent"
            val candidates = chatHistory.entries.filter { (_, messages) ->
                messages.any { message -> message.mine && message.text.contains(marker) &&
                    kotlin.math.abs(message.timestamp - item.createdAt) <= TimeUnit.MINUTES.toMillis(5) }
            }.map { it.key }.distinct()
            if (candidates.size == 1) {
                val chatId = candidates.single()
                val peerName = peerNames[chatId] ?: nameFor(chatId)
                if (peerName.isNotBlank() && peerName.equals(item.peerName, ignoreCase = true)) {
                    if (privateVault.attachPendingRecipient(item.id, stableRecipientId(chatId), chatId, UUID.randomUUID().toString()) != null) recovered++
                }
            }
        }
        if (recovered > 0) mainHandler.post {
            Toast.makeText(this, "$recovered previously unsent vault item(s) recovered into the recipient queue.", Toast.LENGTH_LONG).show()
            retryQueuedMedia()
        }
    }

    private fun sendMediaWireText(chatPeerId: String, targetPeerId: String, wireText: String, strict: Boolean = true): Boolean {
        // Every media start/chunk/end control is wrapped in recipient-only E2E encryption.
        // Start/chunk/end frames are pinned to the chosen direct link; small ACKs may route normally.
        return sendSecurePayload(chatPeerId, wireText, targetPeerId, strictFirstHop = strict)
    }

    private fun waitForBluetoothMediaQueue(targetPeerId: String) {
        // Keep multi-megabyte BLE transfers from building an unbounded in-memory GATT queue.
        while (!Thread.currentThread().isInterrupted) {
            val latch = CountDownLatch(1)
            var queuedPackets = 0
            mainHandler.post {
                queuedPackets = if (clientWriteQueues.containsKey(targetPeerId)) {
                    clientWriteQueues[targetPeerId]?.size ?: 0
                } else {
                    serverNotifyQueues[targetPeerId]?.size ?: 0
                }
                latch.countDown()
            }
            if (!latch.await(2, TimeUnit.SECONDS) || queuedPackets < 100) return
            Thread.sleep(120)
        }
    }

    private fun encodeWire(text: String): String = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decodeWire(text: String): String = try {
        String(Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
    } catch (_: Exception) { "" }

    private fun reapExpiredIncomingMedia() {
        val now = System.currentTimeMillis()
        val expired = incomingMedia.filterValues {
            now - it.lastActivityAt > INCOMING_MEDIA_IDLE_TIMEOUT_MS ||
                now - it.startedAt > INCOMING_MEDIA_ABSOLUTE_TIMEOUT_MS
        }.keys.toList()
        expired.forEach { key -> incomingMedia.remove(key)?.writer?.abort() }
    }

    /** Returns true for a consumed private-media control frame so it never appears as chat text. */
    private fun handleIncomingMediaWireText(
        peerId: String, text: String, sourceTransportId: String? = null, fromNodeId: String? = null
    ): Boolean {
        if (mediaReceiveClosing) return true
        if (!text.startsWith(MEDIA_WIRE_PREFIX)) return false
        reapExpiredIncomingMedia()
        val parts = text.split("|")
        if (parts.size < 3) return true
        if (parts[1] == "S" || parts[1] == "C" || parts[1] == "E" || parts[1] == "W") {
            // Media must arrive over a direct physical link from its authenticated sender.
            val direct = sourceTransportId != null && fromNodeId != null &&
                synchronized(physicalNodeByTransport) { physicalNodeByTransport[sourceTransportId] == fromNodeId }
            if (!direct) return true
        }
        when (parts[1]) {
            "W" -> {
                handleWifiLinkOffer(peerId, parts, fromNodeId)
                return true
            }
            "S" -> {
                if (parts.size < 6) return true
                val transferId = parts[2]
                if (runCatching { UUID.fromString(transferId).toString() }.getOrNull() != transferId) return true
                val authenticatedSender = fromNodeId ?: return true
                // Bind sessions to the stable authenticated identity, not a transient radio address.
                val key = "$authenticatedSender|$transferId"
                // A completed or already-active transfer is an idempotent retry, not a new
                // allocation. Let it finish/ACK without spending the new-session rate budget.
                if (privateVault.receivedTransfer(transferId, authenticatedSender) != null) {
                    sendMediaWireText(peerId, peerId, "${MEDIA_WIRE_PREFIX}A|$transferId", strict = false)
                    return true
                }
                if (incomingMedia.containsKey(key)) return true
                if (!mediaStartIngressLimiter.allow(authenticatedSender)) return true
                val fileName = decodeWire(parts[3]).ifBlank { "private_media" }
                val mime = MediaTransferPolicy.normalizeMime(decodeWire(parts[4]))
                if (!MediaTransferPolicy.isAcceptedMime(mime)) {
                    runOnUiThread { Toast.makeText(this, "Incoming media type is not supported and was ignored.", Toast.LENGTH_LONG).show() }
                    return true
                }
                val viaWifi = sourceTransportId?.startsWith(WIFI_PREFIX) == true
                if (!MediaTransferPolicy.isTransportAllowed(mime, viaWifi)) {
                    runOnUiThread { Toast.makeText(this, "Media arrived over the wrong link type and was ignored.", Toast.LENGTH_LONG).show() }
                    return true
                }
                val size = parts[5].toLongOrNull() ?: return true
                if (size !in 1L..PrivateVault.MAX_MEDIA_BYTES) {
                    runOnUiThread { Toast.makeText(this, "Incoming media exceeds the allowed size.", Toast.LENGTH_LONG).show() }
                    return true
                }
                val sessionsFromSender = incomingMedia.values.count {
                    it.senderNodeId == authenticatedSender
                }
                // Limit both global concurrency and per-identity slot use; a single neighbor
                // cannot hold every receiver slot open.
                // Repeated start frames for an already active transfer are harmless retries;
                // do not abort and recreate the receiver's partial file.
                if (incomingMedia.size >= MAX_INCOMING_MEDIA_SESSIONS) return true
                if (sessionsFromSender >= MAX_INCOMING_MEDIA_SESSIONS_PER_SENDER) return true
                val writer = privateVault.beginIncoming(
                    transferId, fileName, mime, size, nameFor(peerId), authenticatedSender
                )
                if (writer == null) {
                    runOnUiThread { Toast.makeText(this, "Could not create an encrypted incoming file or the vault storage quota is full.", Toast.LENGTH_LONG).show() }
                    return true
                }
                val session = IncomingMediaSession(writer, authenticatedSender, size)
                val registered = synchronized(incomingMediaLifecycleLock) {
                    if (mediaReceiveClosing) false else {
                        incomingMedia[key] = session
                        true
                    }
                }
                if (!registered) {
                    incomingMedia.remove(key, session)
                    writer.abort()
                }
            }
            "C" -> {
                if (parts.size < 5) return true
                val sender = fromNodeId ?: return true
                val key = "$sender|${parts[2]}"
                val session = incomingMedia[key] ?: return true
                if (session.senderNodeId != sender) return true
                val index = parts[3].toIntOrNull()?.takeIf { it >= 0 } ?: return true
                if (index < session.nextChunk) return true // duplicate/replayed chunk already committed
                if (index != session.nextChunk) {
                    session.writer.abort()
                    incomingMedia.remove(key)
                    runOnUiThread { Toast.makeText(this, "Media transfer arrived out of order. Please resend it.", Toast.LENGTH_LONG).show() }
                    return true
                }
                try {
                    val bytes = Base64.decode(parts[4], Base64.URL_SAFE or Base64.NO_WRAP)
                    if (session.writer.write(bytes)) {
                        session.nextChunk++
                        session.lastActivityAt = System.currentTimeMillis()
                    } else {
                        incomingMedia.remove(key)
                        runOnUiThread { Toast.makeText(this, "Could not encrypt incoming media.", Toast.LENGTH_LONG).show() }
                    }
                } catch (_: Exception) {
                    session.writer.abort()
                    incomingMedia.remove(key)
                }
            }
            "E" -> {
                val sender = fromNodeId ?: return true
                val key = "$sender|${parts[2]}"
                val session = incomingMedia[key] ?: return true
                if (session.senderNodeId != sender) return true
                incomingMedia.remove(key)
                val item = session.writer.finish()
                if (item == null) {
                    runOnUiThread { Toast.makeText(this, "Media transfer was incomplete; no file was saved.", Toast.LENGTH_LONG).show() }
                } else {
                    // finish() returns only after the receiver's encrypted file and index commit.
                    sendMediaWireText(peerId, peerId, "${MEDIA_WIRE_PREFIX}A|${session.writer.transferId}", strict = false)
                    runOnUiThread {
                        appendMessage(peerId, ChatMessage(
                            text = if (item.mimeType.startsWith("video/")) "Video" else "Photo",
                            mine = false,
                            mediaMimeType = item.mimeType,
                            mediaSize = item.size,
                            deliveryState = "RECEIVED",
                            requiredTransport = if (item.mimeType.startsWith("video/")) "Wi-Fi Direct" else "Bluetooth LE",
                            transferId = item.transferId
                        ))
                        if (currentScreen == Screen.VAULT) render()
                        Toast.makeText(this, "Received and saved in Private Vault.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            "A" -> {
                val transferId = parts.getOrNull(2) ?: return true
                val authenticatedSender = fromNodeId ?: return true
                val result = privateVault.markTransferDelivered(transferId, authenticatedSender)
                if (result != null) {
                    val (item, firstReceipt) = result
                    mediaAckWaiters[transferId]?.countDown()
                    if (firstReceipt) runOnUiThread {
                        val chatId = item.chatPeerId ?: item.recipientId ?: peerId
                        updateChatMediaState(chatId, transferId, "DELIVERED")
                        if (currentScreen == Screen.VAULT) render()
                    }
                }
            }
        }
        return true
    }

    private fun unlockOrSetVaultPin() {
        if (privateVault.hasPin()) showVaultPinDialog() else showSetVaultPinDialog()
    }

    private fun showSetVaultPinDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(10), dp(22), 0) }
        box.addView(label("Choose a 4–12 digit PIN. It is never saved in clear text. Media files use AES-GCM encryption with Android Keystore.", 13f, muted).apply { setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, 0, 0, dp(12)) })
        val first = EditText(this).apply { hint = "Create PIN"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; setSingleLine(true) }
        val confirm = EditText(this).apply { hint = "Confirm PIN"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; setSingleLine(true) }
        box.addView(first, LinearLayout.LayoutParams(-1, dp(50)))
        box.addView(confirm, LinearLayout.LayoutParams(-1, dp(50)))
        val dialog = AlertDialog.Builder(this).setTitle("Set Private Vault PIN").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save PIN", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = first.text.toString()
                if (!pin.matches(Regex("[0-9]{4,12}"))) {
                    first.error = "Enter 4–12 digits"
                    return@setOnClickListener
                }
                if (pin != confirm.text.toString()) {
                    confirm.error = "PINs do not match"
                    return@setOnClickListener
                }
                privateVault.setPin(pin)
                vaultUnlocked = true
                dialog.dismiss()
                currentScreen = Screen.VAULT
                render()
            }
        }
        dialog.show()
    }

    private fun showVaultPinDialog() {
        val input = EditText(this).apply {
            hint = "Vault PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Unlock Private Vault").setMessage("Enter your PIN to view private media on this device.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Unlock", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val waitMs = privateVault.lockoutRemainingMs()
                if (waitMs > 0L) {
                    input.error = "Too many attempts. Try again in ${(waitMs + 999L) / 1000L}s"
                    return@setOnClickListener
                }
                if (privateVault.verifyPin(input.text.toString())) {
                    vaultUnlocked = true
                    dialog.dismiss()
                    render()
                } else input.error = "Incorrect PIN"
            }
        }
        dialog.show()
    }

    private fun openPrivateMedia(item: PrivateVault.Item) {
        if (!vaultUnlocked) { showVaultPinDialog(); return }
        if (openingVaultPreview) {
            Toast.makeText(this, "A private preview is already opening.", Toast.LENGTH_SHORT).show()
            return
        }
        openingVaultPreview = true
        val dir = File(cacheDir, "morta-preview").apply { mkdirs() }
        val extension = item.fileName.substringAfterLast('.', "jpg").filter { it.isLetterOrDigit() }.take(8).ifBlank { "jpg" }
        val preview = File(dir, "preview_${item.id}.$extension")
        Toast.makeText(this, "Opening encrypted media securely…", Toast.LENGTH_SHORT).show()
        Thread {
            val decrypted = try { privateVault.decryptToTemp(item, preview) } catch (_: Exception) { false }
            val video = item.mimeType.startsWith("video/")
            val bitmap = if (decrypted && !video) decodeVaultPhotoPreview(preview) else null
            runOnUiThread {
                openingVaultPreview = false
                if (!vaultUnlocked || isFinishing || isDestroyed) {
                    bitmap?.let { if (!it.isRecycled) it.recycle() }
                    preview.delete()
                    return@runOnUiThread
                }
                if (!decrypted) {
                    preview.delete()
                    Toast.makeText(this, "This vault item could not be decrypted. Its encrypted copy was preserved.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (video) showVaultVideoPreview(item, preview)
                else if (bitmap != null) showVaultPhotoPreview(item, preview, bitmap)
                else {
                    preview.delete()
                    Toast.makeText(this, "This photo could not be decoded for preview. Its encrypted copy was preserved.", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { name = "MortaMesh-VaultPreview"; isDaemon = true; start() }
    }

    private fun decodeVaultPhotoPreview(preview: File): Bitmap? {
        var bitmap: Bitmap? = null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(preview.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 1800) sample *= 2
            bitmap = BitmapFactory.decodeFile(preview.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.RGB_565
            })
            bitmap
        } catch (_: Exception) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            null
        } catch (_: OutOfMemoryError) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            null
        }
    }

    private fun showVaultVideoPreview(item: PrivateVault.Item, preview: File) {
        val video = VideoView(this).apply {
            setVideoURI(Uri.fromFile(preview))
            setMediaController(MediaController(this@MainActivity))
            keepScreenOn = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(video, LinearLayout.LayoutParams(-1, dp(300)))
        }
        val dialog = AlertDialog.Builder(this).setTitle(item.fileName).setView(container).setPositiveButton("Close", null).create()
        privatePreviewFile = preview
        privateMediaDialog = dialog
        dialog.setOnDismissListener {
            try { video.stopPlayback() } catch (_: Exception) { }
            video.keepScreenOn = false
            preview.delete()
            privatePreviewFile = null
            if (privateMediaDialog === dialog) privateMediaDialog = null
        }
        dialog.setOnShowListener {
            dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            video.setOnPreparedListener { player -> player.isLooping = false; video.start() }
        }
        dialog.show()
    }

    private fun showVaultPhotoPreview(item: PrivateVault.Item, preview: File, bitmap: Bitmap) {
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(0xFF111827.toInt())
        }
        val box = FrameLayout(this).apply { addView(image, FrameLayout.LayoutParams(-1, dp(360), Gravity.CENTER)) }
        val dialog = AlertDialog.Builder(this).setTitle(item.fileName).setView(box).setPositiveButton("Close", null).create()
        privatePreviewFile = preview
        privateMediaDialog = dialog
        dialog.setOnDismissListener {
            if (!bitmap.isRecycled) bitmap.recycle()
            preview.delete()
            privatePreviewFile = null
            if (privateMediaDialog === dialog) privateMediaDialog = null
        }
        dialog.setOnShowListener { dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        dialog.show()
    }

    private fun formatBytes(size: Long): String = when {
        size < 1024L -> "$size B"
        size < 1024L * 1024L -> String.format(Locale.getDefault(), "%.0f KB", size / 1024.0)
        else -> String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0))
    }

    private fun peerRow(peer: NearbyPeer, showSignal: Boolean): View {
        val row = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = "${peer.name}, ${if (clientReady.contains(peer.device.address) || subscribedDevices.contains(peer.device.address)) "connected" else "nearby device"}. Double tap to chat."
            setOnClickListener { openChat(peer.device.address) }
        }
        row.addView(AvatarView(this, peer.name), LinearLayout.LayoutParams(dp(46), dp(46)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
        col.addView(label(peer.name, 14f, ink, true))
        col.addView(label(if (clientReady.contains(peer.device.address) || subscribedDevices.contains(peer.device.address)) "Connected" else "Nearby device", 12f, if (clientReady.contains(peer.device.address) || subscribedDevices.contains(peer.device.address)) green else muted).apply { setPadding(0, dp(5), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label(if (showSignal) "Connect  ›" else "›", if (showSignal) 12f else 24f, blue, true))
        return row
    }

    private fun wifiPeerRow(device: WifiP2pDevice, showSignal: Boolean): View {
        val address = normalizeWifiAddress(device.deviceAddress)
        val chatId = wifiChatId(address)
        val displayName = wifiPeerDisplayNames[address]
            ?: device.deviceName?.takeIf { it.isNotBlank() }
            ?: "Wi-Fi Direct device"
        val nodeId = wifiPeerNodeIds[address]
        val nodeChatId = nodeId?.let { wifiNodeChatId(it) }
        val connected = nodeChatId?.let { wifiWriters.containsKey(it) } == true || wifiWriters.containsKey(chatId)
        val pending = nodeId?.let { wifiOutgoingChatRequests[it] } != null
        wifiConnectAddresses[chatId] = address
        peerNames.putIfAbsent(chatId, displayName)
        return card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = "$displayName, ${if (connected) "connected over Wi-Fi Direct" else if (pending) "Wi-Fi chat request sent" else "nearby over Wi-Fi Direct"}. Double tap to request a chat."
            setOnClickListener {
                peerNames[chatId] = displayName
                persistPeerNames()
                when {
                    connected && nodeChatId != null -> openChat(nodeChatId)
                    nodeId != null -> requestWifiChatConnection(address)
                    else -> Toast.makeText(
                        this@MainActivity,
                        "Waiting for this phone's Morta Mesh identity. Make sure both phones have the current app and scan again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            addView(AvatarView(this@MainActivity, displayName), LinearLayout.LayoutParams(dp(46), dp(46)))
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
            col.addView(label(displayName, 14f, ink, true))
            col.addView(label(
                when {
                    connected -> "Connected · Wi-Fi Direct"
                    pending -> "Request sent · waiting for acceptance"
                    nodeId == null -> "Discovered · identifying Morta Mesh peer…"
                    else -> "Tap to request a Wi-Fi chat"
                },
                12f, if (connected) green else muted
            ).apply { setPadding(0, dp(5), 0, 0) })
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label(if (showSignal) "Fast  ›" else "›", if (showSignal) 12f else 24f, blue, true))
        }
    }

    private fun meshPeerRow(route: MeshRoute): View {
        val id = MESH_PREFIX + route.nodeId
        return card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = "${route.name}, relayed through ${route.hops} hops with end-to-end encryption. Double tap to chat."
            setOnClickListener { openChat(id) }
            addView(AvatarView(this@MainActivity, route.name), LinearLayout.LayoutParams(dp(46), dp(46)))
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
            col.addView(label(route.name, 14f, ink, true))
            col.addView(label("Relayed through ${route.hops} hops · E2E encrypted", 12f, green).apply { setPadding(0, dp(5), 0, 0) })
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("Chat  ›", 12f, blue, true))
        }
    }

    private fun messageBubble(message: ChatMessage): View {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (message.mine) Gravity.END else Gravity.START
            setPadding(0, dp(3), 0, dp(7))
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(8))
            background = rounded(if (message.mine) blue else white, 17, if (message.mine) null else border)
        }
        if (!message.mediaMimeType.isNullOrBlank()) {
            val isVideo = message.mediaMimeType.startsWith("video/")
            val mediaRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            mediaRow.addView(label(if (isVideo) "▶" else "▧", 22f, if (message.mine) white else blue, true).apply {
                gravity = Gravity.CENTER
                background = rounded(if (message.mine) 0xFF3D8BF4.toInt() else blueLight, 10)
                minimumWidth = dp(44)
                minimumHeight = dp(44)
            })
            val detail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            val mediaName = if (isVideo) "Video" else "Photo"
            detail.addView(label("$mediaName · ${formatBytes(message.mediaSize)}", 13f, if (message.mine) white else ink, true))
            val deliveredTransport = when (message.requiredTransport) {
                "Bluetooth LE" -> "Bluetooth"
                else -> message.requiredTransport ?: "nearby link"
            }
            val stateText = when (message.deliveryState) {
                "QUEUED" -> if (isVideo && Build.VERSION.SDK_INT >= 29) {
                    "Connecting over Wi-Fi Direct automatically…"
                } else {
                    "Queued · needs ${message.requiredTransport ?: "nearby link"}"
                }
                "SENDING" -> "Sending · ${message.requiredTransport ?: "nearby link"}"
                "DELIVERED" -> "Delivered over $deliveredTransport"
                "RECEIVED" -> "Received · saved in Private Vault"
                else -> "Saved in Private Vault"
            }
            val stateColor = when (message.deliveryState) {
                "DELIVERED", "RECEIVED" -> if (message.mine) 0xFFD7FBE9.toInt() else green
                "QUEUED" -> if (message.mine) 0xFFFFE3B8.toInt() else 0xFF9A6700.toInt()
                else -> if (message.mine) 0xFFD9E8FF.toInt() else muted
            }
            val stateBackground = when (message.deliveryState) {
                "DELIVERED", "RECEIVED" -> 0xFFE6F8EF.toInt()
                "QUEUED" -> 0xFFFFF4E5.toInt()
                else -> if (message.mine) 0xFF3D8BF4.toInt() else 0xFFF2F4F7.toInt()
            }
            detail.addView(label(stateText, 11f, stateColor).apply {
                background = rounded(stateBackground, 99)
                setPadding(dp(7), dp(4), dp(7), dp(4))
            })
            mediaRow.addView(detail, LinearLayout.LayoutParams(0, -2, 1f))
            bubble.addView(mediaRow)
            bubble.contentDescription = "$mediaName, ${formatBytes(message.mediaSize)}. $stateText. Open in Private Vault."
            bubble.isClickable = true
            bubble.isFocusable = true
            bubble.setOnClickListener {
                val transferId = message.transferId
                val item = transferId?.let { privateVault.transferById(it) }
                if (item != null) openPrivateMedia(item)
                else Toast.makeText(this, "This media item is no longer in the Private Vault.", Toast.LENGTH_LONG).show()
            }
        } else {
            val text = label(message.text, 14f, if (message.mine) white else ink)
            text.setLineSpacing(dp(2).toFloat(), 1f)
            bubble.addView(text)
            if (message.mine && message.deliveryState == "SENT_TO_LINK") {
                bubble.addView(label("Sent to nearby link · not a delivery receipt", 11f, 0xFFD9E8FF.toInt()).apply {
                    gravity = Gravity.END
                    setPadding(0, dp(5), 0, 0)
                })
            }
        }
        bubble.addView(label(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(message.timestamp)), 11f, if (message.mine) 0xFFD9E8FF.toInt() else muted).apply {
            gravity = Gravity.END
            setPadding(0, dp(6), 0, 0)
        })
        line.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply { width = minOf(dp(290), (resources.displayMetrics.widthPixels * .78f).toInt()) })
        return line
    }

    private fun updateChatMediaState(peerId: String?, transferId: String, state: String) {
        if (peerId.isNullOrBlank()) return
        mainHandler.post {
            val messages = chatHistory[peerId] ?: return@post
            val index = messages.indexOfLast { it.transferId == transferId }
            if (index < 0) return@post
            messages[index] = messages[index].copy(deliveryState = state)
            saveHistory()
            if (currentScreen == Screen.CHAT && activeChatId == peerId) {
                chatColumn?.removeAllViews()
                messages.forEach { chatColumn?.addView(messageBubble(it)) }
                chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun appendMessage(peerId: String, message: ChatMessage) {
        val peerMessages = chatHistory.getOrPut(peerId) { mutableListOf() }
        val wasEmpty = peerMessages.isEmpty()
        peerMessages.add(message)
        saveHistory()
        if (currentScreen == Screen.CHAT && activeChatId == peerId) {
            if (wasEmpty) chatColumn?.removeAllViews()
            chatColumn?.addView(messageBubble(message))
            chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun sendComposerMessage() {
        val peerId = activeChatId ?: return
        if (isContactKeyChanged(peerId)) {
            Toast.makeText(this, "Message blocked: this contact's identity key changed. Verify out of band before continuing.", Toast.LENGTH_LONG).show()
            return
        }
        val input = currentComposer ?: return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (text.toByteArray(Charset.forName("UTF-8")).size > 3000) {
            Toast.makeText(this, "Message is too long. Keep it under 3 KB.", Toast.LENGTH_SHORT).show()
            return
        }
        val useWifiChatTransport = peerId.startsWith(WIFI_NODE_PREFIX) && wifiWriters.containsKey(peerId)
        if (!sendSecurePayload(
                peerId,
                text,
                forcedFirstHop = if (useWifiChatTransport) peerId else null,
                asChatText = true,
                strictFirstHop = useWifiChatTransport
            )) {
            val message = if (chatSendRateLimited) {
                "Sending paused to protect mesh delivery limits. Your message is still in the composer; retry after the short rate-limit window."
            } else {
                "Secure identity/route is not ready yet. Your message is still in the composer; retry after the peer appears."
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        input.text.clear()
        appendMessage(peerId, ChatMessage(text, true, deliveryState = "SENT_TO_LINK"))
    }

    private fun openChat(peerId: String) {
        activeChatId = peerId
        currentScreen = Screen.CHAT
        if (peerNames[peerId].isNullOrBlank()) {
            nearbyPeers[peerId]?.let { peerNames[peerId] = it.name }
        }
        persistPeerNames()
        render()
        if (peerId.startsWith(WIFI_NODE_PREFIX)) {
            val nodeId = peerId.removePrefix(WIFI_NODE_PREFIX)
            if (!hasPeerTransport(peerId)) requestWifiChatConnectionForNode(nodeId)
        } else if (peerId.startsWith(MESH_PREFIX)) {
            val route = meshRoutes[peerId.removePrefix(MESH_PREFIX)]
            if (route == null) Toast.makeText(this, "No active mesh route to this device yet.", Toast.LENGTH_LONG).show()
            else if (!hasPeerTransport(route.nextHop)) Toast.makeText(this, "The relay link is offline. Scan nearby devices again.", Toast.LENGTH_LONG).show()
        } else if (!hasPeerTransport(peerId)) {
            if (peerId.startsWith(WIFI_PREFIX)) {
                val address = wifiConnectAddresses[peerId] ?: peerId.removePrefix(WIFI_PREFIX)
                connectWifiToPeer(address, peerId)
            } else connectToPeer(peerId)
        }
    }

    private fun identityVerificationStatus(nodeId: String?): String {
        if (nodeId.isNullOrBlank()) return "Unverified"
        if (keyChangedContacts.contains(nodeId) || preferences.getBoolean(KEY_CHANGED_KEY_PREFIX + nodeId, false)) {
            return "Key changed"
        }
        val fingerprint = meshSecurity.fingerprintFor(nodeId) ?: return "Unverified"
        val verified = preferences.getString(VERIFIED_FINGERPRINT_PREFIX + nodeId, null)
        return if (verified == fingerprint) "Verified" else "Unverified"
    }

    private fun isContactKeyChanged(peerId: String?): Boolean {
        val nodeId = peerId?.let { resolveTargetNode(it) } ?: return false
        return identityVerificationStatus(nodeId) == "Key changed"
    }

    private fun recordKeyChanged(nodeId: String) {
        if (nodeId.isBlank()) return
        mainHandler.post {
            keyChangedContacts.add(nodeId)
            preferences.edit().putBoolean(KEY_CHANGED_KEY_PREFIX + nodeId, true).apply()
            val activeNode = activeChatId?.let { resolveTargetNode(it) }
            if ((currentScreen == Screen.CHAT && activeNode == nodeId) || currentScreen == Screen.CHATS) {
                render()
            }
        }
    }

    private fun markContactVerified(nodeId: String, fingerprint: String) {
        if (identityVerificationStatus(nodeId) == "Key changed") {
            Toast.makeText(this, "A changed identity key cannot be verified from this screen.", Toast.LENGTH_LONG).show()
            return
        }
        val current = meshSecurity.fingerprintFor(nodeId)
        if (current.isNullOrBlank() || current != fingerprint) {
            Toast.makeText(this, "The peer fingerprint changed. Re-open peer info before verifying.", Toast.LENGTH_LONG).show()
            return
        }
        if (!preferences.edit().putString(VERIFIED_FINGERPRINT_PREFIX + nodeId, fingerprint).commit()) {
            Toast.makeText(this, "Could not save this verification on the device.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Fingerprint marked as verified on this device.", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun showPeerInfo() {
        val peer = activeChatId ?: return
        val nodeId = if (peer.startsWith(MESH_PREFIX)) peer.removePrefix(MESH_PREFIX) else nodeForTransport(peer)
        val fingerprint = nodeId?.let { meshSecurity.fingerprintFor(it) }
        val status = identityVerificationStatus(nodeId)
        val keyChanged = status == "Key changed"
        val route = nodeId?.let { meshRoutes[it] }
        val transport = when {
            peer.startsWith(MESH_PREFIX) && route != null && route.hops > 1 -> "Morta Mesh relay · ${route.hops} hops"
            peer.startsWith(WIFI_PREFIX) -> "Wi-Fi Direct"
            else -> "Bluetooth LE / nearby"
        }
        val groupedFingerprint = fingerprint?.filterNot { it.isWhitespace() }?.chunked(5)?.joinToString(" ")
        val detail = buildString {
            append(nameFor(peer)).append("\n").append(transport)
            append("\n\nIdentity status: ").append(status)
            if (keyChanged) {
                append("\n\nThis peer is advertising a different identity key. Messages to this contact are blocked. The new key is not trusted or automatically accepted.")
            } else if (groupedFingerprint != null) {
                append("\n\nSHA-256 fingerprint (compare all groups with your contact through another trusted channel before marking verified):\n")
                append(groupedFingerprint)
            } else if (peer.startsWith(WIFI_PREFIX) && nodeId == null) {
                append("\n\nNo authenticated Wi-Fi Direct link exists yet. Discovery only finds the phone; it does not exchange identity fingerprints. Once the Wi-Fi Direct socket connects, keep both apps open while the encrypted identity handshake completes.")
            } else {
                append("\n\nIdentity fingerprint is not available yet. Keep both apps open while identities exchange.")
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Peer identity")
            .setMessage(detail)
            .setPositiveButton("Close", null)
        if (fingerprint != null) {
            dialog.setNeutralButton("Copy pinned fingerprint") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Morta Mesh fingerprint", groupedFingerprint))
                Toast.makeText(this, "Pinned fingerprint copied", Toast.LENGTH_SHORT).show()
            }
        }
        if (fingerprint != null && nodeId != null && !keyChanged && status != "Verified") {
            dialog.setNegativeButton("Mark as verified") { _, _ -> markContactVerified(nodeId, fingerprint) }
        }
        dialog.show()
    }

    private fun nameFor(peerId: String): String {
        if (peerId.startsWith(WIFI_NODE_PREFIX)) {
            val node = peerId.removePrefix(WIFI_NODE_PREFIX)
            return meshSecurity.displayNameFor(node) ?: peerNames[peerId] ?: "Morta Mesh user"
        }
        if (peerId.startsWith(MESH_PREFIX)) {
            val node = peerId.removePrefix(MESH_PREFIX)
            return meshRoutes[node]?.name ?: meshSecurity.displayNameFor(node) ?: peerNames[peerId] ?: "Morta Mesh user"
        }
        return peerNames[peerId]
            ?: if (peerId.startsWith(WIFI_PREFIX)) wifiPeers[normalizeWifiAddress(peerId.removePrefix(WIFI_PREFIX))]?.deviceName?.takeIf { it.isNotBlank() } ?: "Nearby user"
            else nearbyPeers[peerId]?.name ?: "Nearby user"
    }

    private fun refreshNav() {
        if (!::navBar.isInitialized) return
        navBar.removeAllViews()
        val items: List<Triple<Screen?, Int, String>> = listOf(
            Triple(Screen.HOME, R.drawable.ic_nav_home, "Home"),
            Triple(Screen.NEARBY, R.drawable.ic_nav_nearby, "Nearby"),
            Triple(Screen.CHATS, R.drawable.ic_nav_chats, "Chats"),
            Triple(Screen.VAULT, R.drawable.ic_nav_vault, "Vault"),
            Triple(null, R.drawable.ic_nav_bitcoin, "Wallet"),
            Triple(Screen.SETTINGS, R.drawable.ic_nav_settings, "Settings")
        )
        items.forEach { (screen, icon, title) ->
            val selected = screen != null && (
                currentScreen == screen ||
                    ((currentScreen == Screen.CHAT || currentScreen == Screen.WORLD_CHAT || currentScreen == Screen.ANONYMOUS_POST) && screen == Screen.CHATS)
                )
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, dp(3))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (screen == null) {
                        vaultUnlocked = false
                        startActivity(Intent(this@MainActivity, WalletActivity::class.java))
                    } else {
                        if (screen != Screen.VAULT) vaultUnlocked = false
                        currentScreen = screen
                        if (screen != Screen.CHATS) activeChatId = null
                        render()
                    }
                }
            }
            item.minimumHeight = dp(64)
            item.contentDescription = "$title tab"
            item.isSelected = selected
            item.isActivated = selected
            item.addView(ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(if (selected) blue else 0xFF8A94A6.toInt())
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            item.addView(label(title, 11f, if (selected) blue else 0xFF8A94A6.toInt(), selected).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            navBar.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun addPermissionsNeededCard(parent: LinearLayout) {
        if (!permissionsPermanentlyDenied) return
        val notice = card().apply {
            addView(label("Permissions needed", 14f, ink, true))
            addView(label("Nearby discovery is limited because an Android permission was denied permanently. You can continue using transports that are already allowed.", 12f, muted).apply {
                setPadding(0, dp(5), 0, dp(10))
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            addView(secondaryButton("Open app settings") {
                try {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    ))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Could not open app settings.", Toast.LENGTH_LONG).show()
                }
            })
        }
        parent.addView(notice)
        parent.addView(vSpace(10))
    }

    private var pendingForceRescan = false

    private fun ensureBluetoothPermissionsAndStart(forceRescan: Boolean = false) {
        pendingForceRescan = forceRescan
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.NEARBY_WIFI_DEVICES
        else if (Build.VERSION.SDK_INT >= 31) needed += Manifest.permission.ACCESS_FINE_LOCATION

        val missing = needed.distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Allow nearby discovery")
                .setMessage(
                    "Bluetooth permission lets Morta Mesh find and connect to nearby phones. Nearby Wi-Fi permission enables Wi-Fi Direct links for faster media transfer. On older Android versions, the system may require Location permission or Location services for discovery. Morta Mesh does not collect or upload your GPS location. Messages and private media still use the app's peer-to-peer encrypted flows."
                )
                .setNegativeButton("Not now", null)
                .setPositiveButton("Continue") { _, _ ->
                    requestPermissions(missing.toTypedArray(), permissionRequestCode)
                }
                .show()
            return
        }
        startAvailableTransports(forceRescan)
    }

    private fun hasBluetoothRuntimePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasWifiDirectRuntimePermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun startAvailableTransports(forceRescan: Boolean = false) {
        var started = false
        if (hasBluetoothRuntimePermissions()) {
            val bt = adapter
            if (bt != null && bt.isEnabled) {
                if (forceRescan && isScanning) stopScanning()
                startMesh()
                started = true
            } else if (bt != null) {
                try { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), enableBluetoothRequestCode) }
                catch (_: Exception) { Toast.makeText(this, "Bluetooth is unavailable. Wi-Fi Direct may still work.", Toast.LENGTH_LONG).show() }
            }
        }
        if (hasWifiDirectRuntimePermission()) started = startWifiDirectDiscovery(forceRescan) || started
        if (!started && !hasBluetoothRuntimePermissions() && !hasWifiDirectRuntimePermission()) {
            Toast.makeText(this, "Grant Bluetooth and Nearby Wi-Fi permissions to discover nearby devices.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            // Start whichever transport the user allowed; one denied permission should not disable the other transport.
            startAvailableTransports(pendingForceRescan)
            wifiAutoTick()
            val denied = permissions.indices.filter { index ->
                grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED
            }
            permissionsPermanentlyDenied = denied.any { index ->
                !shouldShowRequestPermissionRationale(permissions[index])
            }
            preferences.edit()
                .putBoolean("nearby_permissions_permanently_denied", permissionsPermanentlyDenied)
                .apply()
            if (denied.isNotEmpty()) {
                Toast.makeText(this, "You can still use whichever nearby transport has its permissions enabled.", Toast.LENGTH_LONG).show()
                if (currentScreen == Screen.HOME || currentScreen == Screen.NEARBY) render()
            }
        }
    }

    @Deprecated("Android Bluetooth enable flow uses activity result in this prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == enableBluetoothRequestCode && resultCode == Activity.RESULT_OK) {
            startAvailableTransports(pendingForceRescan)
            return
        }
        if (requestCode == REQUEST_ANONYMOUS_ATTACHMENT) {
            if (resultCode == Activity.RESULT_OK) {
                val uri = data?.data
                if (uri != null) importAnonymousAttachment(uri)
            }
            return
        }
        if (requestCode == REQUEST_TAKE_PHOTO || requestCode == REQUEST_RECORD_VIDEO) {
            val path = data?.getStringExtra(CameraCaptureActivity.EXTRA_RESULT_PATH)
            val captureDir = File(cacheDir, "morta-capture")
            val file = path?.let { File(it) }?.takeIf {
                try { it.canonicalPath.startsWith(captureDir.canonicalPath + File.separator) } catch (_: Exception) { false }
            }
            val mime = data?.getStringExtra(CameraCaptureActivity.EXTRA_RESULT_MIME) ?: pendingCaptureMime
            if (resultCode == Activity.RESULT_OK && file != null && file.isFile && file.length() > 0L) {
                processCapturedMedia(file, mime)
            } else {
                file?.delete()
                Toast.makeText(this, "Capture cancelled or no media was returned.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMesh() {
        val bt = adapter ?: run { Toast.makeText(this, "Bluetooth is not available on this device.", Toast.LENGTH_LONG).show(); return }
        if (!bt.isEnabled) return
        try {
            scanner = bt.bluetoothLeScanner
            advertiser = bt.bluetoothLeAdvertiser
            if (scanner == null || advertiser == null) {
                Toast.makeText(this, "Bluetooth LE scanning or advertising is unavailable on this device.", Toast.LENGTH_LONG).show()
                scanAvailable = false
                return
            }
            advertisingRequested = true
            startGattServer()
            // A rescan restarts only the scanner. Restarting the advertisement can rotate
            // the BLE address and make the same phone appear as a new nearby row.
            if (gattServiceReady && !isAdvertising && !advertisingStartPending) startAdvertising()
            startScanning()
            meshRunning = true
            statusText = "Visible over Bluetooth LE"
            currentScreen = if (currentScreen == Screen.HOME) Screen.NEARBY else currentScreen
            render()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Bluetooth permission is required.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start Bluetooth discovery: ${e.message ?: "Bluetooth error"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun normalizeWifiAddress(address: String): String = address.trim().uppercase(Locale.ROOT)
    private fun wifiChatId(address: String): String = WIFI_PREFIX + normalizeWifiAddress(address)
    private fun wifiNodeChatId(nodeId: String): String = WIFI_NODE_PREFIX + nodeId

    private fun directBluetoothHop(nodeId: String, preferredHop: String? = null): String? {
        val preferred = preferredHop?.takeIf {
            !it.startsWith(WIFI_PREFIX) && hasPeerTransport(it) && nodeForTransport(it) == nodeId
        }
        if (preferred != null) return preferred
        val routeHop = meshRoutes[nodeId]
            ?.takeIf { it.hops == 1 && !it.nextHop.startsWith(WIFI_PREFIX) && hasPeerTransport(it.nextHop) }
            ?.nextHop
        if (routeHop != null && nodeForTransport(routeHop) == nodeId) return routeHop
        return synchronized(physicalNodeByTransport) {
            physicalNodeByTransport.entries.firstOrNull { (transport, node) ->
                node == nodeId && !transport.startsWith(WIFI_PREFIX) && hasPeerTransport(transport)
            }?.key
        }
    }

    private fun sendWifiChatControl(
        nodeId: String,
        payload: JSONObject,
        preferredBleHop: String? = null
    ): Boolean {
        val hop = directBluetoothHop(nodeId, preferredBleHop) ?: return false
        return sendSecurePayload(
            MESH_PREFIX + nodeId,
            payload.toString(),
            forcedFirstHop = hop,
            strictFirstHop = true
        )
    }

    private fun requestWifiChatConnectionForNode(nodeId: String) {
        val address = wifiPeerNodeIds.entries.firstOrNull { it.value == nodeId }?.key
        if (address == null) {
            Toast.makeText(this, "Re-scan Wi-Fi Direct to find this phone before requesting a new session.", Toast.LENGTH_LONG).show()
            return
        }
        requestWifiChatConnection(address)
    }

    private fun requestWifiChatConnection(address: String) {
        val normalizedAddress = normalizeWifiAddress(address)
        val nodeId = wifiPeerNodeIds[normalizedAddress]
        if (nodeId.isNullOrBlank() || !isValidNodeId(nodeId)) {
            Toast.makeText(this, "This Wi-Fi peer has not published a Morta Mesh identity yet. Scan both transports again.", Toast.LENGTH_LONG).show()
            return
        }
        if (isContactKeyChanged(MESH_PREFIX + nodeId)) {
            Toast.makeText(this, "Wi-Fi chat blocked because this contact's identity key changed.", Toast.LENGTH_LONG).show()
            return
        }
        val wifiId = wifiNodeChatId(nodeId)
        if (wifiWriters.containsKey(wifiId)) {
            openChat(wifiId)
            return
        }
        val existing = wifiOutgoingChatRequests[nodeId]
        if (existing != null && existing.expiresAt > System.currentTimeMillis()) {
            Toast.makeText(this, "Wi-Fi chat request is already waiting for " + existing.peerName + ".", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasWifiDirectRuntimePermission() || !isWifiRadioOn()) {
            if (!hasWifiDirectRuntimePermission()) ensureBluetoothPermissionsAndStart() else promptEnableWifi()
            return
        }

        val nodeHint = wifiPeerNodeHints[normalizedAddress]
        val bluetoothAddress = nearbyPeers.entries.firstOrNull { (_, peer) ->
            nodeHint != null && peer.nodeHint == nodeHint
        }?.key
        if (directBluetoothHop(nodeId) == null && bluetoothAddress == null) {
            Toast.makeText(
                this,
                "Wi-Fi sees this phone, but its Bluetooth control link is not visible yet. Keep both apps open and scan both transports.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val request = PendingWifiChatRequest(
            UUID.randomUUID().toString(),
            nodeId,
            wifiPeerDisplayNames[normalizedAddress] ?: nameFor(MESH_PREFIX + nodeId),
            System.currentTimeMillis() + 60_000L
        )
        wifiOutgoingChatRequests[nodeId] = request
        trySendWifiChatRequest(nodeId, request.requestId, bluetoothAddress, System.currentTimeMillis() + 15_000L)
        renderIfBrowseScreen()
    }

    private fun trySendWifiChatRequest(
        nodeId: String,
        requestId: String,
        bluetoothAddress: String?,
        deadline: Long
    ) {
        val request = wifiOutgoingChatRequests[nodeId]
        if (request == null || request.requestId != requestId) return
        val hop = directBluetoothHop(nodeId)
        if (hop != null) {
            val payload = JSONObject()
                .put("kind", "wifi_chat_request")
                .put("requestId", requestId)
                .put("expiresAt", request.expiresAt)
            if (sendWifiChatControl(nodeId, payload, hop)) {
                Toast.makeText(this, "Wi-Fi chat request sent to " + request.peerName + ".", Toast.LENGTH_SHORT).show()
                renderIfBrowseScreen()
                return
            }
        }
        if (System.currentTimeMillis() >= deadline) {
            wifiOutgoingChatRequests.remove(nodeId)
            Toast.makeText(
                this,
                "Could not establish the encrypted Bluetooth control link. Connect over Bluetooth first, then retry Wi-Fi.",
                Toast.LENGTH_LONG
            ).show()
            renderIfBrowseScreen()
            return
        }
        if (bluetoothAddress != null && !hasPeerTransport(bluetoothAddress) &&
            !clientGatts.containsKey(bluetoothAddress)) {
            connectToPeer(bluetoothAddress)
        }
        mainHandler.postDelayed({
            trySendWifiChatRequest(nodeId, requestId, bluetoothAddress, deadline)
        }, 300L)
    }

    private fun handleIncomingWifiChatRequest(
        chatId: String,
        fromNodeId: String,
        payload: JSONObject
    ) {
        // Connection requests are accepted only from an authenticated, direct BLE neighbor.
        if (chatId.startsWith(WIFI_PREFIX) || chatId.startsWith(MESH_PREFIX) ||
            nodeForTransport(chatId) != fromNodeId || !hasPeerTransport(chatId)) return
        val requestId = payload.optString("requestId")
        val expiresAt = payload.optLong("expiresAt", 0L)
        val now = System.currentTimeMillis()
        if (!isValidNodeId(fromNodeId) ||
            runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false).not() ||
            expiresAt <= now || expiresAt - now > 90_000L) return

        pruneWifiChatRequests()
        if (wifiIncomingChatRequests.containsKey(requestId)) return
        if (wifiIncomingChatRequests.size >= 4) {
            val oldest = wifiIncomingChatRequests.values.minByOrNull { it.expiresAt }?.requestId
            if (oldest != null) wifiIncomingChatRequests.remove(oldest)
        }
        val peerName = identityRecords[fromNodeId]?.optString("name")
            ?.takeIf { it.isNotBlank() } ?: nameFor(chatId)
        wifiIncomingChatRequests[requestId] = PendingWifiChatRequest(requestId, fromNodeId, peerName, expiresAt)
        if (now - lastWifiChatRequestToastAt >= 6_000L) {
            lastWifiChatRequestToastAt = now
            Toast.makeText(this, "Wi-Fi chat request from " + peerName, Toast.LENGTH_SHORT).show()
        }
        if (currentScreen == Screen.NEARBY || currentScreen == Screen.CHATS) render() else refreshNav()
    }

    private fun acceptWifiChatRequest(requestId: String) {
        val request = wifiIncomingChatRequests[requestId] ?: return
        if (request.expiresAt <= System.currentTimeMillis()) {
            wifiIncomingChatRequests.remove(requestId)
            renderIfBrowseScreen()
            return
        }
        if (!isWifiRadioOn() || !hasWifiDirectRuntimePermission()) {
            if (!hasWifiDirectRuntimePermission()) ensureBluetoothPermissionsAndStart() else promptEnableWifi()
            return
        }
        val payload = JSONObject().put("kind", "wifi_chat_accept").put("requestId", requestId)
        if (!sendWifiChatControl(request.peerNodeId, payload)) {
            Toast.makeText(this, "The Bluetooth control link is no longer ready. Re-scan and try again.", Toast.LENGTH_LONG).show()
            return
        }
        wifiIncomingChatRequests.remove(requestId)
        wifiAcceptedChatRequests[requestId] = request.peerNodeId
        while (wifiAcceptedChatRequests.size > 8) {
            wifiAcceptedChatRequests.remove(wifiAcceptedChatRequests.keys.first())
        }
        pendingWifiChatAutoOpenNodeId = request.peerNodeId
        Toast.makeText(this, "Accepted. Setting up Wi-Fi Direct…", Toast.LENGTH_SHORT).show()
        renderIfBrowseScreen()
    }

    private fun declineWifiChatRequest(requestId: String) {
        val request = wifiIncomingChatRequests.remove(requestId) ?: return
        sendWifiChatControl(
            request.peerNodeId,
            JSONObject().put("kind", "wifi_chat_decline").put("requestId", requestId)
        )
        renderIfBrowseScreen()
    }

    private fun handleIncomingWifiChatAcceptance(fromNodeId: String, payload: JSONObject) {
        val request = wifiOutgoingChatRequests[fromNodeId] ?: return
        if (request.requestId != payload.optString("requestId") ||
            request.expiresAt <= System.currentTimeMillis()) return
        wifiOutgoingChatRequests.remove(fromNodeId)
        Toast.makeText(this, request.peerName + " accepted. Establishing Wi-Fi Direct…", Toast.LENGTH_SHORT).show()
        startWifiChatGroup(request)
    }

    private fun handleIncomingWifiChatDecline(fromNodeId: String, payload: JSONObject) {
        val request = wifiOutgoingChatRequests[fromNodeId] ?: return
        if (request.requestId != payload.optString("requestId")) return
        wifiOutgoingChatRequests.remove(fromNodeId)
        Toast.makeText(this, request.peerName + " declined the Wi-Fi chat request.", Toast.LENGTH_SHORT).show()
        renderIfBrowseScreen()
    }

    @SuppressLint("MissingPermission")
    private fun startWifiChatGroup(request: PendingWifiChatRequest) {
        if (Build.VERSION.SDK_INT < 29) {
            sendWifiChatControl(
                request.peerNodeId,
                JSONObject().put("kind", "wifi_chat_failed")
                    .put("requestId", request.requestId).put("reason", "requires_android_10")
            )
            Toast.makeText(this, "Credential-based Wi-Fi chat requires Android 10 or newer. Bluetooth remains available.", Toast.LENGTH_LONG).show()
            return
        }
        val manager = wifiP2pManager
        val channel = wifiP2pChannel
        if (manager == null || channel == null || !hasWifiDirectRuntimePermission() || !isWifiRadioOn()) {
            Toast.makeText(this, "Wi-Fi Direct is not ready. Turn on Wi-Fi and retry.", Toast.LENGTH_LONG).show()
            return
        }
        if (wifiP2pConnectRequestPending || wifiConnectingChatId != null || wifiGroupFormed ||
            wifiSocketClientConnecting || wifiSockets.isNotEmpty()) {
            Toast.makeText(this, "Another Wi-Fi Direct session is already active. Finish it before connecting to another phone.", Toast.LENGTH_LONG).show()
            return
        }

        val chatId = wifiNodeChatId(request.peerNodeId)
        val ssid = "DIRECT-mm-" + randomAlnum(4)
        val passphrase = randomAlnum(16)
        val config = try {
            WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(passphrase).enablePersistentMode(false).build()
        } catch (_: Exception) {
            Toast.makeText(this, "This Android version cannot create a credential-based Wi-Fi Direct group.", Toast.LENGTH_LONG).show()
            return
        }
        wifiConnectingChatId = chatId
        wifiP2pConnectRequestPending = true
        wifiChatGroupRequestId = request.requestId
        wifiChatGroupPeerNodeId = request.peerNodeId
        pendingWifiChatAutoOpenNodeId = request.peerNodeId
        scheduleWifiP2pConnectTimeout(chatId)
        try {
            manager.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    val offer = JSONObject()
                        .put("kind", "wifi_chat_offer")
                        .put("requestId", request.requestId)
                        .put("ssid", ssid)
                        .put("passphrase", passphrase)
                        .put("expiresAt", System.currentTimeMillis() + 120_000L)
                    if (!sendWifiChatControl(request.peerNodeId, offer)) {
                        failWifiChatSession(request.peerNodeId, request.requestId,
                            "The secure Bluetooth link dropped before Wi-Fi credentials could be delivered.")
                    }
                }
                override fun onFailure(reason: Int) {
                    failWifiChatSession(request.peerNodeId, request.requestId, wifiP2pConnectFailureMessage(reason))
                }
            })
        } catch (_: Exception) {
            failWifiChatSession(request.peerNodeId, request.requestId, "Could not create the Wi-Fi Direct group.")
        }
    }

    private fun handleIncomingWifiChatOffer(
        chatId: String,
        fromNodeId: String,
        payload: JSONObject
    ) {
        val requestId = payload.optString("requestId")
        if (wifiAcceptedChatRequests[requestId] != fromNodeId) return
        val ssid = payload.optString("ssid")
        val passphrase = payload.optString("passphrase")
        val expiresAt = payload.optLong("expiresAt", 0L)
        val now = System.currentTimeMillis()
        if (expiresAt <= now || expiresAt - now > 180_000L ||
            !Regex("DIRECT-[A-Za-z0-9]{2}-[A-Za-z0-9]{1,32}").matches(ssid) ||
            passphrase.length !in 8..63 || !passphrase.all { it.isLetterOrDigit() }) return
        if (!isWifiRadioOn() || !hasWifiDirectRuntimePermission()) {
            sendWifiChatControl(
                fromNodeId,
                JSONObject().put("kind", "wifi_chat_failed").put("requestId", requestId).put("reason", "wifi_not_ready"),
                preferredBleHop = chatId
            )
            return
        }
        if (Build.VERSION.SDK_INT < 29 || wifiP2pManager == null || wifiP2pChannel == null ||
            wifiP2pConnectRequestPending || wifiConnectingChatId != null || wifiGroupFormed ||
            wifiSocketClientConnecting || wifiSockets.isNotEmpty()) {
            sendWifiChatControl(
                fromNodeId,
                JSONObject().put("kind", "wifi_chat_failed").put("requestId", requestId).put("reason", "p2p_busy"),
                preferredBleHop = chatId
            )
            return
        }
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        val peerChatId = wifiNodeChatId(fromNodeId)
        val config = try {
            WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(passphrase).enablePersistentMode(false).build()
        } catch (_: Exception) {
            failWifiChatSession(fromNodeId, requestId, "This Android version cannot join a credential-based Wi-Fi Direct group.", chatId)
            return
        }
        wifiConnectingChatId = peerChatId
        wifiP2pConnectRequestPending = true
        wifiChatGroupRequestId = requestId
        wifiChatGroupPeerNodeId = fromNodeId
        pendingWifiChatAutoOpenNodeId = fromNodeId
        scheduleWifiP2pConnectTimeout(peerChatId)
        try {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(this@MainActivity, "Joining the accepted Wi-Fi chat…", Toast.LENGTH_SHORT).show()
                }
                override fun onFailure(reason: Int) {
                    failWifiChatSession(fromNodeId, requestId, wifiP2pConnectFailureMessage(reason), chatId)
                }
            })
        } catch (_: Exception) {
            failWifiChatSession(fromNodeId, requestId, "Could not start the Wi-Fi Direct join request.", chatId)
        }
    }

    private fun handleIncomingWifiChatFailure(fromNodeId: String, payload: JSONObject) {
        val request = wifiOutgoingChatRequests[fromNodeId]
        val requestId = payload.optString("requestId")
        if (request != null && request.requestId == requestId) {
            wifiOutgoingChatRequests.remove(fromNodeId)
            Toast.makeText(this, request.peerName + " could not start Wi-Fi Direct. Bluetooth is still available.", Toast.LENGTH_LONG).show()
            renderIfBrowseScreen()
        }
    }

    private fun failWifiChatSession(
        peerNodeId: String,
        requestId: String,
        message: String,
        preferredBleHop: String? = null
    ) {
        wifiP2pConnectRequestPending = false
        clearWifiConnectTimeout()
        wifiConnectingChatId = null
        wifiChatGroupRequestId = null
        wifiChatGroupPeerNodeId = null
        pendingWifiChatAutoOpenNodeId = null
        sendWifiChatControl(
            peerNodeId,
            JSONObject().put("kind", "wifi_chat_failed").put("requestId", requestId).put("reason", message.take(120)),
            preferredBleHop
        )
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        refreshChatHeaderOrNav()
        if (uiVisible && !isFinishing && !isDestroyed) {
            mainHandler.postDelayed({ startWifiDiscoverySilently() }, 1800L)
        }
    }


    // ---- Automatic Wi-Fi Direct (no taps) -------------------------------------------------------
    // A queued video creates a short-lived Wi-Fi Direct group with random credentials. The offer is
    // sent over the existing authenticated, encrypted Bluetooth link; the recipient joins directly.
    // Both phones must keep Morta Mesh open because the Wi-Fi receiver is lifecycle-bound.
    @Volatile private var uiVisible = false
    private var autoStartAttempted = false
    private var lastWifiAutoDiscoveryAt = 0L
    private var lastWifiOfferAt = 0L
    private var lastWifiJoinAt = 0L
    private var weCreatedWifiGroupAt = 0L
    private var activeOfferFrame: String? = null
    private var activeOfferRecipient: String? = null
    private var activeOfferExpiry = 0L

    private fun isWifiRadioOn(): Boolean =
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true

    /** Android 9–12 may require Location services to be enabled for Wi-Fi Direct discovery. */
    private fun wifiLocationServicesEnabled(): Boolean {
        if (Build.VERSION.SDK_INT !in 28..32) return true
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return locationManager?.isLocationEnabled != false
    }

    /** First launch: request Bluetooth and Nearby Wi-Fi permissions, then start available transports. */
    private fun autoStartTransportsOnce() {
        if (autoStartAttempted) return
        autoStartAttempted = true
        ensureBluetoothPermissionsAndStart()
    }

    private fun randomAlnum(length: Int): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = java.security.SecureRandom()
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun promptEnableWifi() {
        Toast.makeText(this, "Turn on Wi-Fi so your video can send over Wi-Fi Direct.", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(
                if (Build.VERSION.SDK_INT >= 29) android.provider.Settings.Panel.ACTION_WIFI
                else android.provider.Settings.ACTION_WIFI_SETTINGS
            ))
        } catch (_: Exception) { }
    }

    /** Called after a video is saved to the encrypted queue. */
    private fun prepareWifiForVideo() {
        if (!hasWifiDirectRuntimePermission()) { ensureBluetoothPermissionsAndStart(); return }
        if (!isWifiRadioOn()) { promptEnableWifi(); return }
        if (!wifiLocationServicesEnabled()) {
            Toast.makeText(
                this,
                "Enable Location services for Wi-Fi Direct discovery on this Android version. Morta Mesh does not collect your GPS location; Bluetooth remains available.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        startWifiDiscoverySilently()
        mainHandler.postDelayed({ wifiAutoTick() }, 1500)
    }

    @SuppressLint("MissingPermission")
    private fun startWifiDiscoverySilently() {
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        if (!hasWifiDirectRuntimePermission() || !isWifiRadioOn() || !wifiLocationServicesEnabled()) return
        if (wifiP2pConnectRequestPending || wifiConnectingChatId != null || wifiSocketClientConnecting ||
            wifiGroupFormed || wifiSockets.isNotEmpty() || weCreatedWifiGroupAt != 0L) return
        val now = System.currentTimeMillis()
        if (now - lastWifiAutoDiscoveryAt < 10_000L) return
        lastWifiAutoDiscoveryAt = now
        startWifiIdentityServiceDiscovery()
    }

    private fun pendingVideoTransfers(): List<PrivateVault.Item> = try {
        privateVault.pendingTransfers().filter { it.mimeType.startsWith("video/") && it.recipientId != null }
    } catch (_: Exception) { emptyList() }

    /** Runs on resume and the mesh timer; work is gated by lifecycle, permission and radio state. */
    private fun wifiAutoTick() {
        if (!::privateVault.isInitialized || isFinishing || isDestroyed || !uiVisible) return
        if (!hasWifiDirectRuntimePermission() || !isWifiRadioOn()) return
        val locationReady = wifiLocationServicesEnabled()
        val now = System.currentTimeMillis()
        // Wi-Fi P2P discovery and connection negotiation must not fight each other. Android
        // stops discovery when a connection begins; do not restart it until the socket is ready
        // or the bounded connection attempt has been cleaned up.
        val p2pSessionBusy = wifiP2pConnectRequestPending || wifiConnectingChatId != null ||
            wifiSocketClientConnecting || wifiGroupFormed || wifiSockets.isNotEmpty() ||
            weCreatedWifiGroupAt != 0L
        if (locationReady && !p2pSessionBusy && !wifiDiscovering) startWifiDiscoverySilently()

        val pending = pendingVideoTransfers()
        if (weCreatedWifiGroupAt != 0L) {
            // A group that never forms is stale. Remove it instead of leaving Wi-Fi Direct busy.
            if (!wifiGroupFormed && now - weCreatedWifiGroupAt > 30_000L) {
                releaseAutoWifiGroup()
                lastWifiOfferAt = now
                return
            }
            // Once this recipient has no queued video left, release the group so the next
            // recipient in the encrypted vault queue can receive its own short-lived group.
            val activeRecipientStillQueued = activeOfferRecipient?.let { recipient ->
                pending.any { it.recipientId == recipient }
            } == true
            if (activeMediaTransfers.isEmpty() && !activeRecipientStillQueued) {
                releaseAutoWifiGroup()
                lastWifiOfferAt = now
            }
        }

        if (!locationReady) return
        if (pending.isNotEmpty()) {
            offerWifiLinkForQueuedVideo(pending)
        } else if (weCreatedWifiGroupAt != 0L && activeMediaTransfers.isEmpty()) {
            releaseAutoWifiGroup()
        }
    }

    private fun releaseAutoWifiGroup() {
        weCreatedWifiGroupAt = 0L
        activeOfferFrame = null
        activeOfferRecipient = null
        activeOfferExpiry = 0L
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        try { manager.removeGroup(channel, null) } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun offerWifiLinkForQueuedVideo(pending: List<PrivateVault.Item>) {
        if (Build.VERSION.SDK_INT < 29) return
        val now = System.currentTimeMillis()
        if (weCreatedWifiGroupAt != 0L) {
            val recipient = activeOfferRecipient
            val recipientStillQueued = recipient != null && pending.any { it.recipientId == recipient }

            // Expired offers are removed and retried with fresh credentials; do not strand the queue.
            if (now >= activeOfferExpiry && activeMediaTransfers.isEmpty()) {
                releaseAutoWifiGroup()
                lastWifiOfferAt = now
                return
            }
            // The active recipient may have completed while other recipients are still queued.
            if (!recipientStillQueued && activeMediaTransfers.isEmpty()) {
                releaseAutoWifiGroup()
                lastWifiOfferAt = now
                return
            }
            if (wifiSockets.isEmpty()) {
                val frame = activeOfferFrame
                if (frame != null && recipient != null && now < activeOfferExpiry && now - lastWifiOfferAt >= 20_000L) {
                    val bleLink = chooseMediaTransport(recipient, "image/jpeg") ?: return
                    lastWifiOfferAt = now
                    Thread { sendMediaWireText(recipient, bleLink, frame) }.start()
                }
            }
            return
        }
        if (wifiP2pConnectRequestPending || wifiGroupFormed || wifiSockets.isNotEmpty() ||
            wifiConnectingChatId != null || wifiSocketClientConnecting) return
        if (now - lastWifiOfferAt < 25_000L) return
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        val item = pending.firstOrNull { candidate ->
            !activeMediaTransfers.contains(candidate.id) &&
                candidate.recipientId?.let { chooseMediaTransport(it, "image/jpeg") } != null
        } ?: return
        val recipient = item.recipientId ?: return
        val bleLink = chooseMediaTransport(recipient, "image/jpeg") ?: return
        lastWifiOfferAt = now
        val ssid = "DIRECT-mm-" + randomAlnum(4)
        val passphrase = randomAlnum(16)
        val config = try {
            WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(passphrase).enablePersistentMode(false).build()
        } catch (_: Exception) { return }
        manager.createGroup(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                val created = System.currentTimeMillis()
                weCreatedWifiGroupAt = created
                activeOfferExpiry = created + 120_000L
                val frame = "${MEDIA_WIRE_PREFIX}W|${encodeWire(ssid)}|${encodeWire(passphrase)}|$activeOfferExpiry|${encodeWire(wifiLocalAddress)}"
                activeOfferFrame = frame
                activeOfferRecipient = recipient
                Thread { sendMediaWireText(recipient, bleLink, frame) }.start()
            }
            override fun onFailure(reason: Int) {
                // BUSY commonly indicates a stale group; clear it so a later tick can retry.
                if (reason == WifiP2pManager.BUSY) {
                    try { manager.removeGroup(channel, null) } catch (_: Exception) { }
                }
            }
        })
    }

    /** Offer is accepted only from the authenticated peer on its direct Bluetooth link. */
    @SuppressLint("MissingPermission")
    private fun handleWifiLinkOffer(peerId: String, parts: List<String>, fromNodeId: String?) {
        if (Build.VERSION.SDK_INT < 29 || fromNodeId == null || parts.size < 6) return
        if (!uiVisible || wifiP2pConnectRequestPending || wifiGroupFormed ||
            wifiSockets.isNotEmpty() || wifiConnectingChatId != null || wifiSocketClientConnecting) return
        val now = System.currentTimeMillis()
        if (now - lastWifiJoinAt < 15_000L) return
        val ssid = decodeWire(parts[2])
        val passphrase = decodeWire(parts[3])
        val expiry = parts[4].toLongOrNull() ?: return
        val fallbackAddress = decodeWire(parts[5])
        if (now > expiry || expiry - now > 180_000L) return
        if (!Regex("DIRECT-[A-Za-z0-9]{2}-[A-Za-z0-9]{1,32}").matches(ssid)) return
        if (passphrase.length !in 8..63 || !passphrase.all { it.isLetterOrDigit() }) return
        if (!hasWifiDirectRuntimePermission() || !isWifiRadioOn() || !wifiLocationServicesEnabled()) {
            runOnUiThread {
                when {
                    !hasWifiDirectRuntimePermission() -> ensureBluetoothPermissionsAndStart()
                    !isWifiRadioOn() -> promptEnableWifi()
                    else -> Toast.makeText(
                        this,
                        "Enable Location services for Wi-Fi Direct on this Android version. Morta Mesh does not collect your GPS location.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            return
        }
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        lastWifiJoinAt = now
        runOnUiThread {
            Toast.makeText(this, "Joining ${nameFor(peerId)} over Wi-Fi Direct to receive a video…", Toast.LENGTH_SHORT).show()
            try {
                val config = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(passphrase).enablePersistentMode(false).build()
                wifiP2pConnectRequestPending = true
                scheduleWifiP2pConnectTimeout(null)
                manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { }
                    override fun onFailure(reason: Int) {
                        wifiP2pConnectRequestPending = false
                        clearWifiConnectTimeout()
                        // Older/vendor builds may reject credential-based joining; use the address invitation.
                        if (fallbackAddress.isNotBlank()) connectWifiToPeer(fallbackAddress, wifiChatId(fallbackAddress))
                        else if (uiVisible) mainHandler.postDelayed({ startWifiDiscoverySilently() }, 1800L)
                    }
                })
            } catch (_: Exception) {
                wifiP2pConnectRequestPending = false
                clearWifiConnectTimeout()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWifiDirectDiscovery(forceRescan: Boolean = false): Boolean {
        val manager = wifiP2pManager ?: return false
        val channel = wifiP2pChannel ?: return false
        if (!hasWifiDirectRuntimePermission()) return false
        if (wifiP2pConnectRequestPending || wifiConnectingChatId != null || wifiSocketClientConnecting ||
            wifiGroupFormed || wifiSockets.isNotEmpty() || weCreatedWifiGroupAt != 0L) {
            Toast.makeText(this, "A Wi-Fi Direct session is already connecting or active. Keep both apps open and try again after it finishes.", Toast.LENGTH_LONG).show()
            return false
        }
        // A scan button can be tapped repeatedly. Reuse the active discovery instead of
        // issuing overlapping discoverPeers() requests to the framework.
        if (wifiDiscovering) {
            requestWifiPeers()
            return true
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi?.isWifiEnabled != true) {
            Toast.makeText(this, "Turn on Wi-Fi to use the faster Wi-Fi Direct connection. Bluetooth remains available.", Toast.LENGTH_LONG).show()
            return false
        }
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (Build.VERSION.SDK_INT in 28..32 && locationManager?.isLocationEnabled == false) {
            Toast.makeText(this, "Android may require Location services enabled for Wi-Fi Direct discovery. Morta Mesh does not collect your location; Bluetooth remains available.", Toast.LENGTH_LONG).show()
            return false
        }
        try {
            if (forceRescan) wifiDiscovering = false
            startWifiIdentityServiceDiscovery()
            return true
        } catch (e: SecurityException) {
            Toast.makeText(this, "Nearby Wi-Fi permission is required for Wi-Fi Direct.", Toast.LENGTH_LONG).show()
            return false
        } catch (_: Exception) {
            Toast.makeText(this, "Wi-Fi Direct is unavailable. Bluetooth remains available.", Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xFF) }

    private fun localNodeHintBytes(): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(localNodeId.toByteArray(Charsets.UTF_8)).copyOfRange(0, 6)

    private fun localNodeHint(): String = localNodeHintBytes().toHexString()

    private fun isValidNodeId(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun startWifiIdentityServiceDiscovery() {
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        if (!hasWifiDirectRuntimePermission() || !isWifiRadioOn() || !wifiLocationServicesEnabled()) return
        if (wifiP2pConnectRequestPending || wifiConnectingChatId != null || wifiSocketClientConnecting ||
            wifiGroupFormed || wifiSockets.isNotEmpty() || weCreatedWifiGroupAt != 0L || wifiServiceDiscoveryStarting) return
        wifiServiceDiscoveryStarting = true
        try {
            manager.setDnsSdResponseListeners(channel, wifiDnsSdServiceListener, wifiDnsSdTxtListener)
            if (wifiLocalServiceRegistered) {
                startWifiIdentityServiceRequest(manager, channel)
            } else {
                val record = linkedMapOf(
                    "node" to localNodeId,
                    "hint" to localNodeHint(),
                    "name" to nickname.filter { it.code in 32..126 }.take(32).ifBlank { "Morta user" },
                    "version" to "2"
                )
                val service = WifiP2pDnsSdServiceInfo.newInstance("_morta", "_presence._tcp", record)
                manager.addLocalService(channel, service, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        wifiLocalServiceRegistered = true
                        startWifiIdentityServiceRequest(manager, channel)
                    }
                    override fun onFailure(reason: Int) {
                        wifiServiceDiscoveryStarting = false
                        startWifiPeerDiscoveryFallback(manager, channel)
                    }
                })
            }
        } catch (_: SecurityException) {
            wifiServiceDiscoveryStarting = false
            Toast.makeText(this, "Nearby Wi-Fi permission is required for Wi-Fi Direct discovery.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            wifiServiceDiscoveryStarting = false
            startWifiPeerDiscoveryFallback(manager, channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWifiIdentityServiceRequest(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ) {
        try {
            manager.clearServiceRequests(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { addWifiIdentityServiceRequest(manager, channel) }
                override fun onFailure(reason: Int) { addWifiIdentityServiceRequest(manager, channel) }
            })
        } catch (_: Exception) {
            wifiServiceDiscoveryStarting = false
            startWifiPeerDiscoveryFallback(manager, channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun addWifiIdentityServiceRequest(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ) {
        try {
            val request = WifiP2pDnsSdServiceRequest.newInstance()
            manager.addServiceRequest(channel, request, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    try {
                        manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                wifiServiceDiscoveryStarting = false
                                wifiDiscovering = true
                                requestWifiPeers()
                                renderIfBrowseScreen()
                            }
                            override fun onFailure(reason: Int) {
                                wifiServiceDiscoveryStarting = false
                                wifiDiscovering = false
                                startWifiPeerDiscoveryFallback(manager, channel)
                            }
                        })
                    } catch (_: Exception) {
                        wifiServiceDiscoveryStarting = false
                        startWifiPeerDiscoveryFallback(manager, channel)
                    }
                }
                override fun onFailure(reason: Int) {
                    wifiServiceDiscoveryStarting = false
                    startWifiPeerDiscoveryFallback(manager, channel)
                }
            })
        } catch (_: Exception) {
            wifiServiceDiscoveryStarting = false
            startWifiPeerDiscoveryFallback(manager, channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWifiPeerDiscoveryFallback(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ) {
        try {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    wifiDiscovering = true
                    requestWifiPeers()
                    renderIfBrowseScreen()
                }
                override fun onFailure(reason: Int) {
                    wifiDiscovering = false
                    renderIfBrowseScreen()
                }
            })
        } catch (_: Exception) {
            wifiDiscovering = false
        }
    }

    private val wifiDnsSdTxtListener = WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
        val address = normalizeWifiAddress(device.deviceAddress)
        val nodeId = record["node"]?.trim().orEmpty()
        val nodeHint = record["hint"]?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val displayName = record["name"]?.trim()?.take(32).orEmpty()
        if (address.isBlank() || !isValidNodeId(nodeId) || !Regex("[0-9a-f]{12}").matches(nodeHint)) return@DnsSdTxtRecordListener
        runOnUiThread {
            wifiPeers[address] = device
            wifiPeerNodeIds[address] = nodeId
            wifiPeerNodeHints[address] = nodeHint
            if (displayName.isNotBlank()) wifiPeerDisplayNames[address] = displayName
            val chatId = wifiChatId(address)
            wifiConnectAddresses[chatId] = address
            if (displayName.isNotBlank()) peerNames[chatId] = displayName
            renderIfBrowseScreen()
        }
    }

    private val wifiDnsSdServiceListener = WifiP2pManager.DnsSdServiceResponseListener { instanceName, _, device ->
        val address = normalizeWifiAddress(device.deviceAddress)
        if (address.isBlank()) return@DnsSdServiceResponseListener
        runOnUiThread {
            wifiPeers[address] = device
            if (wifiPeerDisplayNames[address].isNullOrBlank()) {
                wifiPeerDisplayNames[address] = instanceName.removePrefix("_").take(32).ifBlank { "Morta Mesh user" }
            }
            renderIfBrowseScreen()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestWifiPeers() {
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        if (!hasWifiDirectRuntimePermission()) return
        try {
            manager.requestPeers(channel) { deviceList: WifiP2pDeviceList ->
                val devices = deviceList.deviceList
                runOnUiThread {
                    wifiPeers.clear()
                    devices.forEach { device ->
                        val address = normalizeWifiAddress(device.deviceAddress)
                        if (address.isNotBlank() && address != normalizeWifiAddress(wifiLocalAddress)) {
                            wifiPeers[address] = device
                            val chatId = wifiChatId(address)
                            wifiConnectAddresses[chatId] = address
                            if (peerNames[chatId].isNullOrBlank()) peerNames[chatId] = device.deviceName?.takeIf { it.isNotBlank() } ?: "Wi-Fi Direct device"
                        }
                    }
                    renderIfBrowseScreen()
                }
            }
        } catch (_: Exception) { }
    }

    private val wifiDirectReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    wifiP2pEnabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (wifiP2pEnabled) { requestWifiPeers(); wifiAutoTick() }
                }
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    // Android stops discovery after about two minutes; restart it quietly while the app is open.
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED)
                    wifiDiscovering = state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                    if (!wifiDiscovering && !wifiP2pConnectRequestPending && wifiConnectingChatId == null &&
                        !wifiSocketClientConnecting && !wifiGroupFormed && wifiSockets.isEmpty()) {
                        mainHandler.postDelayed({ wifiAutoTick() }, 1500)
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestWifiPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val manager = wifiP2pManager
                    val channel = wifiP2pChannel
                    if (manager != null && channel != null && hasWifiDirectRuntimePermission()) {
                        try { manager.requestConnectionInfo(channel, wifiConnectionInfoListener) } catch (_: Exception) { }
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    if (device != null) wifiLocalAddress = device.deviceAddress.orEmpty()
                }
            }
        }
    }

    private val wifiConnectionInfoListener = WifiP2pManager.ConnectionInfoListener { info -> handleWifiConnectionInfo(info) }

    @SuppressLint("MissingPermission")
    private fun handleWifiConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed) {
            wifiSocketClientConnecting = false
            if (wifiGroupFormed) {
                wifiGroupFormed = false
                wifiP2pConnectRequestPending = false
                clearWifiConnectTimeout()
                wifiConnectingChatId = null
                // The group-owned TCP service is only valid while this P2P group exists.
                try { wifiServerSocket?.close() } catch (_: Exception) { }
                wifiServerSocket = null
                wifiServerThread = null
                val staleSockets = wifiSockets.toMap()
                wifiSockets.clear()
                wifiWriters.clear()
                staleSockets.forEach { (peerId, socket) ->
                    synchronized(physicalNodeByTransport) { physicalNodeByTransport.remove(peerId) }
                    try { socket.close() } catch (_: Exception) { }
                    onTransportLost(peerId)
                }
                renderIfBrowseScreen()
                refreshChatHeaderOrNav()
            }
            return
        }
        wifiGroupFormed = true
        if (info.isGroupOwner) {
            // Bind only to the group-owner's Wi-Fi Direct address; never listen on all interfaces.
            val groupOwnerAddress = info.groupOwnerAddress ?: return
            startWifiSocketServer(groupOwnerAddress)
        } else {
            val owner = info.groupOwnerAddress ?: return
            if (!wifiSocketClientConnecting && wifiSockets.isEmpty()) {
                wifiSocketClientConnecting = true
                connectWifiSocket(owner)
            }
        }
    }

    private fun startWifiSocketServer(bindAddress: java.net.InetAddress) {
        val current = wifiServerSocket
        if (wifiServerThread?.isAlive == true && current != null && !current.isClosed &&
            current.inetAddress == bindAddress) return
        try { current?.close() } catch (_: Exception) { }
        wifiServerThread = Thread {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindAddress, wifiPort), 8)
                }
                wifiServerSocket = server
                while (!server.isClosed) {
                    val socket = server.accept()
                    val expectedPeerId = wifiConnectingChatId
                    Thread { attachWifiSocket(socket, expectedPeerId) }.apply {
                        name = "MortaMesh-WiFiPeer"
                        isDaemon = true
                        start()
                    }
                }
            } catch (_: Exception) { }
        }.apply { name = "MortaMesh-WiFiServer"; isDaemon = true; start() }
    }

    private fun connectWifiSocket(owner: java.net.InetAddress) {
        val expectedPeerId = wifiConnectingChatId
        wifiConnectingChatId = null
        Thread {
            try {
                var connectedSocket: Socket? = null
                var lastError: Exception? = null
                repeat(8) {
                    if (connectedSocket != null) return@repeat
                    try {
                        val candidate = Socket()
                        candidate.connect(InetSocketAddress(owner, wifiPort), 1_800)
                        connectedSocket = candidate
                    } catch (e: Exception) {
                        lastError = e
                        try { Thread.sleep(350) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    }
                }
                val socket = connectedSocket ?: throw (lastError ?: java.io.IOException("Could not connect to Wi-Fi Direct peer"))
                attachWifiSocket(socket, expectedPeerId)
            } catch (_: Exception) {
                runOnUiThread {
                    wifiSocketClientConnecting = false
                    Toast.makeText(this, "Wi-Fi Direct link could not be opened. Try again or use Bluetooth.", Toast.LENGTH_LONG).show()
                    refreshChatHeaderOrNav()
                }
            }
        }.apply { name = "MortaMesh-WiFiClient"; isDaemon = true; start() }
    }

    private fun attachWifiSocket(socket: Socket, expectedPeerId: String?) {
        var resolvedPeerId: String? = expectedPeerId
        var writer: BufferedWriter? = null
        var remoteNodeId: String? = null
        var remoteNonce: String? = null
        var remoteIdentity: JSONObject? = null
        var remoteName = "Nearby user"
        var remoteAddress = ""
        var authenticated = false
        val localNonce = UUID.randomUUID().toString()
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = WIFI_HELLO_TIMEOUT_MS // close sockets that never complete mutual authentication
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val socketWriter = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            writer = socketWriter
            writeWifiFrame(socketWriter, JSONObject()
                .put("type", "hello")
                .put("address", wifiLocalAddress)
                .put("name", nickname)
                .put("node", localNodeId)
                .put("nonce", localNonce)
                .put("identity", meshSecurity.identityRecord(nickname)))
            while (socket.isConnected) {
                val line = readBoundedLine(reader, WIFI_MAX_FRAME_CHARS) ?: break
                val frame = try { JSONObject(line) } catch (_: Exception) { continue }
                when (frame.optString("type")) {
                    "hello" -> {
                        val identity = frame.optJSONObject("identity") ?: throw java.io.IOException("Wi-Fi hello omitted signed identity")
                        val nodeId = frame.optString("node").trim()
                        val nonce = frame.optString("nonce")
                        if (!meshSecurity.isValidWifiNonce(nonce) ||
                            !meshSecurity.verifyIdentityRecord(identity) ||
                            identity.optString("node") != nodeId) {
                            throw java.io.IOException("Wi-Fi hello identity or nonce was invalid")
                        }
                        if (remoteNodeId != null && (remoteNodeId != nodeId || remoteNonce != nonce)) {
                            throw java.io.IOException("Wi-Fi peer changed identity during the handshake")
                        }
                        val previouslyBoundNode = resolvedPeerId?.let { peer ->
                            synchronized(physicalNodeByTransport) { physicalNodeByTransport[peer] }
                        }
                        if (previouslyBoundNode != null && previouslyBoundNode != nodeId) {
                            throw java.io.IOException("Wi-Fi transport identity changed unexpectedly")
                        }
                        if (resolvedPeerId?.startsWith(WIFI_NODE_PREFIX) == true &&
                            resolvedPeerId?.removePrefix(WIFI_NODE_PREFIX) != nodeId) {
                            throw java.io.IOException("Wi-Fi peer did not match the accepted Morta Mesh identity")
                        }
                        remoteNodeId = nodeId
                        remoteNonce = nonce
                        remoteIdentity = identity
                        remoteName = identity.optString("name", frame.optString("name", "Nearby user")).ifBlank { "Nearby user" }.take(64)
                        remoteAddress = frame.optString("address").trim()
                        if (resolvedPeerId.isNullOrBlank()) {
                            val addressIdentity = if (remoteAddress.isNotBlank() && remoteAddress != "02:00:00:00:00:00") remoteAddress else nodeId
                            resolvedPeerId = if (addressIdentity == nodeId) MESH_PREFIX + nodeId else wifiChatId(addressIdentity)
                        }
                        val proof = meshSecurity.signWifiHelloProof(nodeId, localNonce, nonce)
                            ?: throw java.io.IOException("Could not sign Wi-Fi hello proof")
                        writeWifiFrame(socketWriter, JSONObject()
                            .put("type", "hello_ack")
                            .put("from", localNodeId)
                            .put("to", nodeId)
                            .put("nonce", localNonce)
                            .put("echo", nonce)
                            .put("proof", proof))
                    }
                    "hello_ack" -> {
                        val nodeId = remoteNodeId ?: throw java.io.IOException("Received Wi-Fi proof before hello")
                        val nonce = remoteNonce ?: throw java.io.IOException("Wi-Fi peer nonce is missing")
                        val identity = remoteIdentity ?: throw java.io.IOException("Wi-Fi peer identity is missing")
                        if (frame.optString("from") != nodeId ||
                            frame.optString("to") != localNodeId ||
                            frame.optString("nonce") != nonce ||
                            frame.optString("echo") != localNonce ||
                            !meshSecurity.verifyWifiHelloProof(
                                identity,
                                nodeId,
                                localNodeId,
                                nonce,
                                localNonce,
                                frame.optString("proof")
                            )) {
                            throw java.io.IOException("Wi-Fi peer failed proof-of-possession")
                        }
                        when (meshSecurity.acceptIdentity(identity)) {
                            MeshSecurity.IdentityResult.ACCEPTED -> Unit
                            MeshSecurity.IdentityResult.KEY_CHANGED -> {
                                recordKeyChanged(identity.optString("node"))
                                throw java.io.IOException("Wi-Fi peer identity key changed")
                            }
                            MeshSecurity.IdentityResult.INVALID -> throw java.io.IOException("Wi-Fi peer identity could not be pinned")
                        }
                        val peerId = resolvedPeerId ?: throw java.io.IOException("Authenticated Wi-Fi peer has no transport ID")
                        if (!authenticated) {
                            authenticated = true
                            socket.soTimeout = 0
                            val ready = CountDownLatch(1)
                            mainHandler.post {
                                try {
                                    wifiSockets[peerId] = socket
                                    wifiWriters[peerId] = socketWriter
                                    wifiP2pConnectRequestPending = false
                                    clearWifiConnectTimeout()
                                    wifiSocketClientConnecting = false
                                    if (wifiConnectingChatId == peerId) wifiConnectingChatId = null
                                    peerNames[peerId] = remoteName
                                    if (remoteAddress.isNotBlank()) wifiConnectAddresses[peerId] = normalizeWifiAddress(remoteAddress)
                                    synchronized(physicalNodeByTransport) { physicalNodeByTransport[peerId] = nodeId }
                                    identityRecords[nodeId] = JSONObject(identity.toString())
                                    persistPeerNames()
                                    flushPendingWifiMessages(peerId)
                                    onTransportReady(peerId)
                                    applyIdentityAdvertisement(peerId, MESH_ROUTE_TTL, UUID.randomUUID().toString(), 0, identity)
                                    if (pendingWifiChatAutoOpenNodeId == nodeId) {
                                        pendingWifiChatAutoOpenNodeId = null
                                        wifiChatGroupRequestId = null
                                        wifiChatGroupPeerNodeId = null
                                        activeChatId = peerId
                                        currentScreen = Screen.CHAT
                                        render()
                                    } else if (currentScreen == Screen.CHAT && activeChatId == peerId) {
                                        render()
                                    } else {
                                        refreshNav()
                                    }
                                    renderIfBrowseScreen()
                                } finally {
                                    ready.countDown()
                                }
                            }
                            if (!ready.await(5, TimeUnit.SECONDS)) throw java.io.IOException("Wi-Fi authentication registration timed out")
                        }
                    }
                    "message" -> {
                        if (!authenticated) throw java.io.IOException("Wi-Fi message arrived before authentication")
                        val peerId = resolvedPeerId ?: throw java.io.IOException("Authenticated Wi-Fi peer has no transport ID")
                        val text = frame.optString("text")
                        if (text.isNotEmpty()) handleIncomingWireText(peerId, text)
                    }
                    else -> {
                        // Unknown frame types are ignored, but never grant transport readiness.
                    }
                }
            }
        } catch (_: Exception) {
            // A bad or unauthenticated hello is intentionally dropped without adding a peer or route.
        } finally {
            val peerId = resolvedPeerId
            runOnUiThread {
                if (peerId != null && wifiSockets[peerId] === socket) {
                    wifiSockets.remove(peerId)
                    wifiWriters.remove(peerId)
                    synchronized(physicalNodeByTransport) { physicalNodeByTransport.remove(peerId) }
                    onTransportLost(peerId)
                    refreshChatHeaderOrNav()
                }
                if (expectedPeerId != null) wifiSocketClientConnecting = false
            }
            try { socket.close() } catch (_: Exception) { }
        }
    }

    /** readLine() without the unbounded-memory risk: over-long frames abort the connection. */
    private fun readBoundedLine(reader: BufferedReader, maxChars: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code) {
                if (sb.length >= maxChars) throw java.io.IOException("Wi-Fi frame exceeds the maximum size")
                sb.append(c.toChar())
            }
        }
    }

    private fun writeWifiFrame(writer: BufferedWriter, frame: JSONObject) {
        synchronized(writer) {
            writer.write(frame.toString())
            writer.newLine()
            writer.flush()
        }
    }

    private fun flushPendingWifiMessages(peerId: String) {
        val writer = wifiWriters[peerId] ?: return
        val queue = pendingWifiMessages[peerId] ?: return
        while (queue.isNotEmpty()) {
            val text = queue.removeFirst()
            try { writeWifiFrame(writer, JSONObject().put("type", "message").put("text", text)) }
            catch (_: Exception) { queue.addFirst(text); break }
        }
        if (queue.isEmpty()) pendingWifiMessages.remove(peerId)
    }

    private fun sendWifiText(peerId: String, text: String) {
        val writer = wifiWriters[peerId]
        if (writer == null) {
            pendingWifiMessages.getOrPut(peerId) { ArrayDeque() }.addLast(text)
            val address = wifiConnectAddresses[peerId] ?: peerId.removePrefix(WIFI_PREFIX)
            if (wifiConnectingChatId != peerId && !wifiSocketClientConnecting && !wifiSockets.containsKey(peerId)) connectWifiToPeer(address, peerId)
            runOnUiThread { Toast.makeText(this, "Waiting for the Wi-Fi Direct link…", Toast.LENGTH_SHORT).show() }
            return
        }
        try { writeWifiFrame(writer, JSONObject().put("type", "message").put("text", text)) }
        catch (_: Exception) {
            pendingWifiMessages.getOrPut(peerId) { ArrayDeque() }.addLast(text)
            runOnUiThread { Toast.makeText(this, "Wi-Fi Direct link interrupted. Try again or use Bluetooth.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun clearWifiConnectTimeout() {
        wifiConnectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        wifiConnectTimeoutRunnable = null
    }

    private fun scheduleWifiP2pConnectTimeout(chatId: String?) {
        clearWifiConnectTimeout()
        val timeout = Runnable {
            if (!wifiP2pConnectRequestPending || wifiSockets.isNotEmpty()) return@Runnable
            if (chatId != null && wifiConnectingChatId != chatId) return@Runnable

            wifiP2pConnectRequestPending = false
            wifiSocketClientConnecting = false
            if (chatId == null || wifiConnectingChatId == chatId) wifiConnectingChatId = null
            val manager = wifiP2pManager
            val channel = wifiP2pChannel
            if (manager != null && channel != null && hasWifiDirectRuntimePermission()) {
                try { manager.cancelConnect(channel, null) } catch (_: Exception) { }
                try { manager.removeGroup(channel, null) } catch (_: Exception) { }
            }
            wifiGroupFormed = false
            Toast.makeText(
                this,
                "Wi-Fi Direct did not finish connecting. The request was cleared; retry from one phone, or continue over Bluetooth.",
                Toast.LENGTH_LONG
            ).show()
            refreshChatHeaderOrNav()
            if (uiVisible && !isFinishing && !isDestroyed) {
                mainHandler.postDelayed({ startWifiDiscoverySilently() }, 1800L)
            }
        }
        wifiConnectTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, 40_000L)
    }

    private fun wifiP2pConnectFailureMessage(reason: Int): String = when (reason) {
        WifiP2pManager.BUSY ->
            "Android Wi-Fi Direct is busy. Close any other Wi-Fi Direct session, wait a few seconds, then retry."
        WifiP2pManager.P2P_UNSUPPORTED ->
            "This phone does not support Wi-Fi Direct. Bluetooth remains available."
        WifiP2pManager.ERROR ->
            "Android's Wi-Fi Direct service returned an internal error before the connection started. Keep both apps open, make sure Wi-Fi is on on both phones, and retry from one phone."
        else ->
            "Android could not start Wi-Fi Direct (reason $reason). Keep both apps open and retry; Bluetooth remains available."
    }

    @SuppressLint("MissingPermission")
    private fun connectWifiToPeer(address: String, chatId: String) {
        val manager = wifiP2pManager
        val channel = wifiP2pChannel
        if (manager == null || channel == null) {
            Toast.makeText(this, "Wi-Fi Direct is unavailable. Bluetooth remains available.", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasWifiDirectRuntimePermission()) {
            ensureBluetoothPermissionsAndStart()
            return
        }
        if (wifiP2pConnectRequestPending || wifiConnectingChatId != null || wifiSocketClientConnecting ||
            wifiGroupFormed || wifiSockets.isNotEmpty()) {
            Toast.makeText(this, "Another Wi-Fi Direct connection is already in progress. Wait for it to finish before starting a second one.", Toast.LENGTH_LONG).show()
            return
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi?.isWifiEnabled != true) {
            Toast.makeText(this, "Turn on Wi-Fi, then reconnect. Bluetooth remains available.", Toast.LENGTH_LONG).show()
            return
        }
        val normalizedAddress = normalizeWifiAddress(address)
        if (normalizedAddress.isBlank()) return
        if (normalizedAddress == "02:00:00:00:00:00") {
            Toast.makeText(
                this,
                "Android did not expose a usable Wi-Fi Direct peer address. Re-scan both phones and retry; Bluetooth remains available.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        wifiConnectingChatId = chatId
        wifiP2pConnectRequestPending = true
        scheduleWifiP2pConnectTimeout(chatId)
        wifiConnectAddresses[chatId] = normalizedAddress
        val config = WifiP2pConfig().apply {
            deviceAddress = normalizedAddress
            // Required by Android's documented peer-to-peer connect flow.
            wps.setup = WpsInfo.PBC
        }
        try {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Connecting over Wi-Fi Direct…", Toast.LENGTH_SHORT).show() }
                }
                override fun onFailure(reason: Int) {
                    if (wifiConnectingChatId == chatId) wifiConnectingChatId = null
                    wifiP2pConnectRequestPending = false
                    clearWifiConnectTimeout()
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            wifiP2pConnectFailureMessage(reason),
                            Toast.LENGTH_LONG
                        ).show()
                        refreshChatHeaderOrNav()
                        // A failed connect request stops discovery; restart it so the peer list is fresh.
                        mainHandler.postDelayed({
                            if (uiVisible && !isFinishing && !isDestroyed) startWifiDiscoverySilently()
                        }, 1800L)
                    }
                }
            })
        } catch (_: SecurityException) {
            wifiP2pConnectRequestPending = false
            clearWifiConnectTimeout()
            if (wifiConnectingChatId == chatId) wifiConnectingChatId = null
            Toast.makeText(this, "Nearby Wi-Fi permission is required.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            wifiP2pConnectRequestPending = false
            clearWifiConnectTimeout()
            if (wifiConnectingChatId == chatId) wifiConnectingChatId = null
            Toast.makeText(this, "Could not start Wi-Fi Direct. Try Bluetooth instead.", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        if (gattServer != null) return
        gattServer = bluetoothManager.openGattServer(this, serverCallback)
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            messageUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        characteristic.addDescriptor(BluetoothGattDescriptor(cccdUuid, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(characteristic)
        messageCharacteristic = characteristic
        gattServer?.addService(service)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val adv = advertiser ?: return
        if (isAdvertising || advertisingStartPending) return
        advertisingStartPending = true
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(parcelUuid).build()
        val shortName = nickname.filter { it.code in 32..126 }.take(6).ifBlank { "Morta" }.toByteArray(Charsets.UTF_8)
        // Version + 48-bit stable node hint + short name fits the legacy BLE scan-response budget.
        val servicePayload = ByteArray(7 + shortName.size).apply {
            this[0] = 2
            val hint = localNodeHintBytes()
            System.arraycopy(hint, 0, this, 1, 6)
            System.arraycopy(shortName, 0, this, 7, shortName.size)
        }
        val scanResponse = AdvertiseData.Builder().addServiceData(parcelUuid, servicePayload).build()
        try {
            adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            advertisingStartPending = false
            throw e
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartAdvertising() {
        if (meshRunning && gattServiceReady) {
            try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) { }
            isAdvertising = false
            advertisingStartPending = false
            startAdvertising()
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            runOnUiThread {
                advertisingStartPending = false
                isAdvertising = true
                meshRunning = true
                statusText = "Visible to nearby devices"
                refreshNav()
            }
        }
        override fun onStartFailure(errorCode: Int) {
            runOnUiThread {
                advertisingStartPending = false
                isAdvertising = false
                meshRunning = false
                statusText = "Advertising unavailable ($errorCode)"
                Toast.makeText(this@MainActivity, "Bluetooth advertising could not start ($errorCode).", Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        val s = scanner ?: return
        stopScanning()
        val filter = ScanFilter.Builder().setServiceUuid(parcelUuid).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        s.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
        mainHandler.removeCallbacks(stopScanRunnable)
        mainHandler.postDelayed(stopScanRunnable, 45_000)
    }

    private val stopScanRunnable = Runnable {
        stopScanning()
        runOnUiThread { renderIfBrowseScreen() }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) { }
        isScanning = false
    }

    private fun renderIfBrowseScreen() {
        if (currentScreen == Screen.NEARBY || currentScreen == Screen.HOME) render()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = try { device.address } catch (_: Exception) { return }
            val servicePayload = try { result.scanRecord?.getServiceData(parcelUuid) } catch (_: Exception) { null }
            val nodeHint = servicePayload?.takeIf { it.size >= 7 && it[0].toInt() == 2 }
                ?.copyOfRange(1, 7)?.toHexString()
            val rawName = servicePayload?.takeIf { it.size > 7 && it[0].toInt() == 2 }
                ?.copyOfRange(7, servicePayload.size)?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotBlank() }
                ?: servicePayload?.takeIf { it.isNotEmpty() && it[0].toInt() != 2 }
                    ?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotBlank() }
            val fallbackName = try { device.name?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
            val seenName = rawName ?: fallbackName ?: "Nearby Morta user ${address.takeLast(2)}"
            runOnUiThread {
                val now = System.currentTimeMillis()
                val peer = nearbyPeers[address]
                val changed = peer == null || peer.name != seenName
                if (peer == null) nearbyPeers[address] = NearbyPeer(device, seenName, nodeHint) else {
                    peer.name = seenName
                    if (nodeHint != null) peer.nodeHint = nodeHint
                    peer.lastSeen = now
                }
                if (changed) {
                    peerNames[address] = seenName
                    persistPeerNames()
                }
                if ((currentScreen == Screen.NEARBY || currentScreen == Screen.HOME) && now - lastPeerRenderAt > 700L) {
                    lastPeerRenderAt = now
                    render()
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                isScanning = false
                statusText = "Scan failed ($errorCode)"
                Toast.makeText(this@MainActivity, "Nearby scan failed ($errorCode). Try scanning again.", Toast.LENGTH_LONG).show()
                renderIfBrowseScreen()
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid == serviceUuid && status == BluetoothGatt.GATT_SUCCESS) {
                gattServiceReady = true
                if (advertisingRequested) mainHandler.post { startAdvertising() }
            } else if (service.uuid == serviceUuid) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Could not initialize nearby messaging service.", Toast.LENGTH_LONG).show() }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized(serverDevices) { serverDevices[address] = device }
                synchronized(serverMtu) { serverMtu.putIfAbsent(address, 23) }
                runOnUiThread { refreshNav() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(serverDevices) { serverDevices.remove(address) }
                synchronized(serverMtu) { serverMtu.remove(address) }
                synchronized(subscribedDevices) { subscribedDevices.remove(address) }
                synchronized(serverNotifyQueues) { serverNotifyQueues.remove(address) }
                synchronized(serverNotifyBusy) { serverNotifyBusy.remove(address) }
                runOnUiThread { onTransportLost(address); refreshNav() }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            synchronized(serverMtu) { serverMtu[device.address] = mtu.coerceAtLeast(23) }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            var status = BluetoothGatt.GATT_SUCCESS
            if (descriptor.uuid == cccdUuid && offset == 0) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribedDevices.add(device.address)
                    mainHandler.post { onTransportReady(device.address) }
                } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) subscribedDevices.remove(device.address)
                else status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            } else status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            if (responseNeeded) gattServer?.sendResponse(device, requestId, status, 0, null)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val valid = characteristic.uuid == messageUuid && offset == 0 && !preparedWrite
            if (responseNeeded) gattServer?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            if (valid) receivePacket(device.address, value)
        }

        @SuppressLint("MissingPermission")
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(serverNotifyBusy) { serverNotifyBusy.remove(device.address) }
            mainHandler.post { sendNextServerNotification(device.address) }
        }
    }

    private fun hasPeerTransport(address: String): Boolean {
        if (address.startsWith(MESH_PREFIX)) {
            val route = meshRoutes[address.removePrefix(MESH_PREFIX)] ?: return false
            return hasPeerTransport(route.nextHop)
        }
        return if (address.startsWith(WIFI_PREFIX)) wifiWriters.containsKey(address)
        else clientReady.contains(address) || (serverDevices.containsKey(address) && subscribedDevices.contains(address))
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(address: String) {
        if (clientGatts.containsKey(address)) return
        val device = nearbyPeers[address]?.device ?: return
        try {
            val gatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
                private var discoveryStarted = false
                private fun discoverOnce(g: BluetoothGatt) {
                    if (discoveryStarted) return
                    discoveryStarted = true
                    @Suppress("DEPRECATION")
                    g.discoverServices()
                }

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        clientGatts[address] = gatt
                        clientMtus[address] = 23
                        try {
                            val requested = gatt.requestMtu(185)
                            if (!requested) discoverOnce(gatt)
                        } catch (_: Exception) { discoverOnce(gatt) }
                        runOnUiThread { refreshChatHeaderOrNav() }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        clientReady.remove(address)
                        clientCharacteristics.remove(address)
                        clientGatts.remove(address)
                        clientWriteQueues.remove(address)
                        clientWriteBusy.remove(address)
                        try { gatt.close() } catch (_: Exception) { }
                        runOnUiThread { onTransportLost(address); refreshChatHeaderOrNav() }
                    } else if (status != BluetoothGatt.GATT_SUCCESS) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Could not connect to ${nameFor(address)}.", Toast.LENGTH_SHORT).show() }
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    clientMtus[address] = if (status == BluetoothGatt.GATT_SUCCESS) mtu.coerceAtLeast(23) else 23
                    discoverOnce(gatt)
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) return
                    val service = gatt.getService(serviceUuid) ?: return
                    val characteristic = service.getCharacteristic(messageUuid) ?: return
                    clientCharacteristics[address] = characteristic
                    try { gatt.setCharacteristicNotification(characteristic, true) } catch (_: Exception) { }
                    val descriptor = characteristic.getDescriptor(cccdUuid)
                    if (descriptor != null) {
                        try {
                            if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                        } catch (_: Exception) { }
                    }
                }

                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    if (descriptor.uuid == cccdUuid && status == BluetoothGatt.GATT_SUCCESS) {
                        clientReady.add(address)
                        flushPendingMessages(address)
                        writeNextClientPacket(address)
                        runOnUiThread { onTransportReady(address); refreshChatHeaderOrNav() }
                    }
                }

                @Deprecated("Deprecated by Android 13; kept for Android 8+ compatibility")
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    if (characteristic.uuid == messageUuid) characteristic.value?.let { receivePacket(address, it) }
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                    if (characteristic.uuid == messageUuid) receivePacket(address, value)
                }

                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    clientWriteBusy.remove(address)
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Message transport interrupted. Keep devices nearby.", Toast.LENGTH_SHORT).show() }
                    }
                    writeNextClientPacket(address)
                }
            }, BluetoothDevice.TRANSPORT_LE)
            clientGatts[address] = gatt
        } catch (e: SecurityException) {
            Toast.makeText(this, "Bluetooth permission is required.", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not connect to that device.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshChatHeaderOrNav() {
        if (currentScreen == Screen.CHAT) {
            // Header labels are lightweight; rebuilding keeps the connection state accurate.
            render()
        } else refreshNav()
    }

    private fun sendTextToPeer(address: String, text: String) {
        if (address.startsWith(WIFI_PREFIX)) {
            sendWifiText(address, text)
            return
        }
        if (clientReady.contains(address)) {
            queueClientPackets(address, text)
            return
        }
        if (serverDevices.containsKey(address) && subscribedDevices.contains(address)) {
            val mtu = serverMtu[address] ?: 23
            enqueueServerPackets(address, fragmentText(text, mtu))
            return
        }
        pendingMessages.getOrPut(address) { ArrayDeque() }.addLast(text)
        if (!clientGatts.containsKey(address)) connectToPeer(address)
        Toast.makeText(this, "Sending when the nearby connection is ready…", Toast.LENGTH_SHORT).show()
    }

    private fun flushPendingMessages(address: String) {
        val queue = pendingMessages[address] ?: return
        while (queue.isNotEmpty()) queueClientPackets(address, queue.removeFirst())
        pendingMessages.remove(address)
    }

    private fun queueClientPackets(address: String, text: String) {
        val mtu = clientMtus[address] ?: 23
        val packets = fragmentText(text, mtu)
        val queue = clientWriteQueues.getOrPut(address) { ArrayDeque() }
        packets.forEach { queue.addLast(it) }
        writeNextClientPacket(address)
    }

    @SuppressLint("MissingPermission")
    private fun writeNextClientPacket(address: String) {
        if (clientWriteBusy.contains(address)) return
        val queue = clientWriteQueues[address] ?: return
        if (queue.isEmpty()) return
        val gatt = clientGatts[address] ?: return
        val characteristic = clientCharacteristics[address] ?: return
        if (!clientReady.contains(address)) return
        val packet = queue.peekFirst() ?: return // remove only after the BLE stack accepts it
        clientWriteBusy.add(address)
        try {
            val result = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = packet
                @Suppress("DEPRECATION")
                if (gatt.writeCharacteristic(characteristic)) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            }
            if (result != BluetoothGatt.GATT_SUCCESS) {
                clientWriteBusy.remove(address)
                if (registerBleSendFailure("c:$address")) queue.pollFirst()
                mainHandler.postDelayed({ writeNextClientPacket(address) }, 120)
            } else {
                bleSendFailures.remove("c:$address")
                queue.pollFirst()
            }
        } catch (_: Exception) {
            clientWriteBusy.remove(address)
        }
    }

    /** Counts consecutive BLE stack failures; drops a stuck head packet after bounded retries. */
    private fun registerBleSendFailure(key: String): Boolean {
        val count = (bleSendFailures[key] ?: 0) + 1
        return if (count >= 50) {
            bleSendFailures.remove(key)
            true
        } else {
            bleSendFailures[key] = count
            false
        }
    }

    private fun enqueueServerPackets(address: String, packets: List<ByteArray>) {
        val queue = serverNotifyQueues.getOrPut(address) { ArrayDeque() }
        packets.forEach { queue.addLast(it) }
        sendNextServerNotification(address)
    }

    @SuppressLint("MissingPermission")
    private fun sendNextServerNotification(address: String) {
        if (serverNotifyBusy.contains(address)) return
        if (!subscribedDevices.contains(address)) return
        val queue = serverNotifyQueues[address] ?: return
        if (queue.isEmpty()) return
        val device = serverDevices[address] ?: return
        val characteristic = messageCharacteristic ?: return
        val packet = queue.peekFirst() ?: return // remove only after the BLE stack accepts it
        serverNotifyBusy.add(address)
        try {
            val status = if (Build.VERSION.SDK_INT >= 33) {
                gattServer?.notifyCharacteristicChanged(device, characteristic, false, packet) ?: BluetoothStatus.ERROR
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = packet
                @Suppress("DEPRECATION")
                if (gattServer?.notifyCharacteristicChanged(device, characteristic, false) == true) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                serverNotifyBusy.remove(address)
                if (registerBleSendFailure("s:$address")) queue.pollFirst()
                mainHandler.postDelayed({ sendNextServerNotification(address) }, 100)
            } else {
                bleSendFailures.remove("s:$address")
                queue.pollFirst()
            }
        } catch (_: Exception) {
            serverNotifyBusy.remove(address)
        }
    }

    private fun receivePacket(address: String, packet: ByteArray) {
        val decoded = PacketAssembler.accept(address, packet) ?: return
        handleIncomingWireText(address, decoded)
    }

    /** Start identity gossip whenever a direct transport becomes usable. */
    private fun onTransportReady(peerId: String) {
        mainHandler.post { retryQueuedMedia() }
        if (!hasPeerTransport(peerId)) return
        announceAnonymousRelayMode(peerId)
        val now = System.currentTimeMillis()
        val previous = transportIdentitySentAt[peerId] ?: 0L
        if (now - previous >= 8_000L) {
            transportIdentitySentAt[peerId] = now
            sendKnownIdentitiesTo(peerId)
        }
        val previousWorldSync = transportWorldSyncSentAt[peerId] ?: 0L
        if (now - previousWorldSync >= 30_000L) {
            transportWorldSyncSentAt[peerId] = now
            sendWorldHistoryTo(peerId)
        }
    }

    private fun anonymousRelayCarryEnabled(): Boolean =
        preferences.getBoolean("anonymous_relay_carry_enabled", false)

    private fun announceAnonymousRelayMode(peerId: String) {
        if (!hasPeerTransport(peerId)) return
        val enabled = if (anonymousRelayCarryEnabled()) "1" else "0"
        val signedCapability = meshSecurity.createWorldPost(nickname, "morta-anon-relay:v1:carry=$enabled") ?: return
        runCatching { sendTextToPeer(peerId, ANONYMOUS_RELAY_CAPABILITY_PREFIX + encodeWire(signedCapability.toString())) }
        if (enabled == "1") forwardStoredAnonymousTransfersTo(peerId)
    }

    private fun announceAnonymousRelayModeToPeers() {
        connectedTransportIds().forEach(::announceAnonymousRelayMode)
    }

    private fun connectedWifiTransportIds(): List<String> =
        wifiWriters.keys.filter { it.startsWith(WIFI_PREFIX) && hasPeerTransport(it) }

    /** Re-send persisted ciphertext to a newly connected peer that explicitly opted in. */
    private fun forwardStoredAnonymousTransfersTo(peerId: String) {
        if (anonymousRelayPeerModes[peerId] != true) return
        if (!peerId.startsWith(WIFI_PREFIX) || !hasPeerTransport(peerId)) return
        // File reads, hashes and large chunk writes must never block the main UI thread.
        // Origin-owned submissions may be sent even when this phone has not opted into
        // carrying other people's jobs. Received packages require our own Peer Mode opt-in.
        anonymousRelayExecutor.execute {
            if (anonymousRelayPeerModes[peerId] != true) return@execute
            anonymousRelayStore.listTransfers().forEach { transfer ->
                if (!anonymousRelayCarryEnabled() && !transfer.localOrigin) return@forEach
                val nextTtl = transfer.ttl - 1
                if (nextTtl >= 0) {
                    anonymousRelayStore.forEachStoredFrame(transfer.id, nextTtl) { frame ->
                        runCatching { sendTextToPeer(peerId, frame) }
                    }
                }
            }
        }
    }

    /** Called by the future configured uploader after it seals a draft to Mortalive's pinned key. */
    private fun queueAnonymousPackageForRelay(packageValue: AnonymousSubmissionCrypto.EncryptedPackage): Boolean {
        if (!anonymousRelayStore.enqueueLocalPackage(packageValue)) return false
        val targets = connectedWifiTransportIds().filter { anonymousRelayPeerModes[it] == true }
        targets.forEach { forwardStoredAnonymousTransfersTo(it) }
        return true
    }

    /**
     * Future Mortalive integration entry point. Requires a backend-published, pinned ingestion key.
     * This creates an encrypted package and queues it for consented nearby relays; it does not
     * claim server upload, moderation approval, or group delivery.
     */
    internal fun enqueueAnonymousDraftForConfiguredRelay(
        draftId: String,
        ingestionPublicKeyX509Base64: String,
        ingestionKeyId: String
    ): Boolean {
        val staged = anonymousSubmissionPackager.stageForRelay(
            draftId, ingestionPublicKeyX509Base64, ingestionKeyId, anonymousRelayStore
        )
        if (!staged) return false
        val targets = connectedWifiTransportIds().filter { anonymousRelayPeerModes[it] == true }
        targets.forEach { forwardStoredAnonymousTransfersTo(it) }
        return true
    }

    private fun onTransportLost(peerId: String) {
        anonymousRelayPeerModes.remove(peerId)
        transportIdentitySentAt.remove(peerId)
        transportWorldSyncSentAt.remove(peerId)
        physicalNodeByTransport.remove(peerId)
        meshRoutes.entries.removeAll { it.value.nextHop == peerId }
        if (currentScreen == Screen.CHAT) refreshChatHeaderOrNav() else renderIfBrowseScreen()
    }

    private fun connectedTransportIds(): List<String> {
        val values = linkedSetOf<String>()
        values.addAll(wifiWriters.keys)
        values.addAll(clientReady)
        values.addAll(subscribedDevices.filter { serverDevices.containsKey(it) })
        return values.filter { hasPeerTransport(it) }
    }

    /** Broadcast one shared signed identity advertisement so multi-hop peers can refresh routes. */
    private fun gossipLocalIdentity() {
        val neighbors = connectedTransportIds()
        if (neighbors.isEmpty()) return
        val record = meshSecurity.identityRecord(nickname)
        identityRecords[localNodeId] = record
        val advertId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        synchronized(seenIdentityAdvertisements) { seenIdentityAdvertisements[advertId] = now }
        val frame = "$MESH_ID_PREFIX$MESH_ROUTE_TTL|$advertId|0|${encodeWire(record.toString())}"
        neighbors.forEach { peer ->
            try { sendTextToPeer(peer, frame) } catch (_: Exception) { }
        }
        pruneSeenCaches()
    }

    private fun sendKnownIdentitiesTo(peerId: String) {
        if (!hasPeerTransport(peerId)) return
        val localRecord = meshSecurity.identityRecord(nickname)
        identityRecords[localNodeId] = localRecord
        sendIdentityAdvertisement(peerId, localRecord, MESH_ROUTE_TTL, 0)
        identityRecords.values.filter { it.optString("node") != localNodeId }.forEach { record ->
            val node = record.optString("node")
            val hops = meshRoutes[node]?.hops ?: return@forEach
            sendIdentityAdvertisement(peerId, record, MESH_ROUTE_TTL, hops)
        }
    }

    private fun sendIdentityAdvertisement(peerId: String, record: JSONObject, ttl: Int, hops: Int) {
        if (ttl < 0 || !hasPeerTransport(peerId)) return
        val advId = UUID.randomUUID().toString()
        seenIdentityAdvertisements[advId] = System.currentTimeMillis()
        pruneSeenCaches()
        val frame = "$MESH_ID_PREFIX$ttl|$advId|${hops.coerceAtMost(32)}|${encodeWire(record.toString())}"
        try { sendTextToPeer(peerId, frame) } catch (_: Exception) { }
    }

    private fun handleIncomingWireText(sourcePeerId: String, text: String) {
        if (text.length > 384 * 1024) return
        when {
            text.startsWith(ANONYMOUS_RELAY_CAPABILITY_PREFIX) -> {
                val signed = runCatching {
                    JSONObject(decodeWire(text.removePrefix(ANONYMOUS_RELAY_CAPABILITY_PREFIX)))
                }.getOrNull() ?: return
                if (!meshSecurity.verifyWorldPost(signed)) return
                val nodeId = signed.optString("node")
                // Do not accept a relay opt-in until the transport is bound to the same
                // key-pinned logical node through the existing Mesh identity handshake.
                val transportNode = nodeForTransport(sourcePeerId) ?: return
                if (transportNode != nodeId) return
                val messageId = signed.optString("id")
                val now = System.currentTimeMillis()
                anonymousRelayCapabilitySeen.entries.removeAll { now - it.value > 7L * 24L * 60L * 60L * 1000L }
                while (anonymousRelayCapabilitySeen.size >= 512) {
                    val oldest = anonymousRelayCapabilitySeen.minByOrNull { it.value }?.key ?: break
                    anonymousRelayCapabilitySeen.remove(oldest)
                }
                if (messageId.isBlank() || anonymousRelayCapabilitySeen.putIfAbsent(messageId, now) != null) return
                val mode = when (signed.optString("text")) {
                    "morta-anon-relay:v1:carry=1" -> true
                    "morta-anon-relay:v1:carry=0" -> false
                    else -> return
                }
                anonymousRelayPeerModes[sourcePeerId] = mode
                if (mode) mainHandler.post { forwardStoredAnonymousTransfersTo(sourcePeerId) }
            }
            text.startsWith(AnonymousRelayProtocol.PREFIX) -> meshProtocolExecutor.execute {
                if (!preferences.getBoolean("anonymous_relay_carry_enabled", false)) return@execute
                if (!sourcePeerId.startsWith(WIFI_PREFIX)) return@execute
                val header = AnonymousRelayProtocol.peekHeader(text) ?: return@execute
                if (!anonymousRelayAdmissionLimiter.allow(sourcePeerId, header.transferId, header.declaredCiphertextBytes)) return@execute
                val incoming = AnonymousRelayProtocol.parse(text) ?: return@execute
                when (anonymousRelayStore.acceptFrame(text)) {
                    AnonymousRelayStore.AcceptResult.STORED,
                    AnonymousRelayStore.AcceptResult.COMPLETE -> {
                        val nextTtl = incoming.ttl - 1
                        if (nextTtl > 0) {
                            val forwarded = AnonymousRelayProtocol.forwardWithTtl(text, nextTtl) ?: return@execute
                            mainHandler.post {
                                val targets = connectedWifiTransportIds()
                                    .filter { it != sourcePeerId && anonymousRelayPeerModes[it] == true }
                                anonymousRelayExecutor.execute {
                                    targets.forEach { peer -> runCatching { sendTextToPeer(peer, forwarded) } }
                                }
                            }
                        }
                    }
                    AnonymousRelayStore.AcceptResult.DUPLICATE,
                    AnonymousRelayStore.AcceptResult.INVALID,
                    AnonymousRelayStore.AcceptResult.QUOTA_REJECTED -> Unit
                }
            }
            text.startsWith(MESH_ID_PREFIX) -> meshProtocolExecutor.execute {
                val parts = text.removePrefix(MESH_ID_PREFIX).split('|', limit = 4)
                if (parts.size != 4) return@execute
                val ttl = parts[0].toIntOrNull()?.takeIf { it in 0..MESH_ROUTE_TTL } ?: return@execute
                val advertId = parts[1].take(80)
                val hops = parts[2].toIntOrNull()?.coerceIn(0, 32) ?: return@execute
                val record = try { JSONObject(decodeWire(parts[3])) } catch (_: Exception) { return@execute }
                if (record.optString("node") == localNodeId) return@execute
                val result = meshSecurity.acceptIdentity(record)
                mainHandler.post {
                    when (result) {
                        MeshSecurity.IdentityResult.ACCEPTED -> applyIdentityAdvertisement(sourcePeerId, ttl, advertId, hops, record)
                        MeshSecurity.IdentityResult.KEY_CHANGED -> {
                            recordKeyChanged(record.optString("node"))
                            Toast.makeText(this, "Blocked a peer whose identity key changed. Verify this contact out of band.", Toast.LENGTH_LONG).show()
                        }
                        MeshSecurity.IdentityResult.INVALID -> Unit
                    }
                }
            }
            text.startsWith(MESH_PACKET_PREFIX) -> meshProtocolExecutor.execute {
                val parts = text.removePrefix(MESH_PACKET_PREFIX).split('|', limit = 2)
                if (parts.size != 2) return@execute
                val ttl = parts[0].toIntOrNull()?.takeIf { it in 0..MESH_ROUTE_TTL } ?: return@execute
                val envelope = try { JSONObject(decodeWire(parts[1])) } catch (_: Exception) { return@execute }
                val destination = envelope.optString("to")
                val claimedSender = envelope.optString("from")
                val directlyClaimedSender = synchronized(physicalNodeByTransport) {
                    physicalNodeByTransport[sourcePeerId]
                } == claimedSender
                // Media is sent only over a direct authenticated link. Do not throttle its ordered
                // chunks here; apply a strict ingress budget to relays and unknown neighbors instead.
                if ((destination != localNodeId || !directlyClaimedSender) &&
                    !meshIngressLimiter.allow(sourcePeerId)) return@execute
                if (!meshSecurity.verifyRoutableEnvelope(envelope)) return@execute
                val messageId = envelope.optString("id")
                val opened = if (destination == localNodeId) {
                    // A failed pin check or AEAD tag must never consume a durable replay ID.
                    val decrypted = meshSecurity.open(envelope) ?: return@execute
                    if (decrypted.text.startsWith(MEDIA_WIRE_PREFIX)) {
                        // Media frames have their own authenticated transfer ID, exact chunk index,
                        // session timeout and storage quotas. Keep bulk chunks out of the chat replay DB.
                        decrypted
                    } else {
                        if (!authenticatedChatIngressLimiter.allow(decrypted.fromNodeId)) return@execute
                        if (!replayGuard.acceptIfNew(decrypted.fromNodeId, messageId)) return@execute
                        decrypted
                    }
                } else {
                    // Relays cannot decrypt; use only a bounded in-memory forwarding cache so
                    // unpinned, merely well-signed traffic cannot evict recipient replay history.
                    val relayCacheKey = "${envelope.optString("from")}|$messageId"
                    synchronized(seenMeshPackets) {
                        if (seenMeshPackets.containsKey(relayCacheKey)) return@execute
                        seenMeshPackets[relayCacheKey] = System.currentTimeMillis()
                    }
                    pruneSeenCaches()
                    null
                }
                mainHandler.post {
                    handleRoutedEnvelope(sourcePeerId, ttl, envelope, opened, deduplicated = true)
                }
            }
            text.startsWith(WORLD_CHAT_PREFIX) -> meshProtocolExecutor.execute {
                // World posts are public input: cap signature-verification work per incoming link.
                if (!worldChatIngressLimiter.allow(sourcePeerId)) return@execute
                val frameBytes = text.toByteArray(Charsets.UTF_8)
                if (frameBytes.size > MAX_WORLD_CHAT_FRAME_BYTES) return@execute
                val parts = text.removePrefix(WORLD_CHAT_PREFIX).split('|', limit = 2)
                if (parts.size != 2) return@execute
                val ttl = parts[0].toIntOrNull()?.takeIf { it in 0..WORLD_CHAT_TTL } ?: return@execute
                val postJson = try { JSONObject(parts[1]) } catch (_: Exception) { return@execute }
                if (!meshSecurity.verifyWorldPost(postJson)) return@execute
                val post = WorldChatPost.fromJson(postJson) ?: return@execute
                val accepted = worldChatStore.insertIfNew(post)
                if (accepted != WorldChatStore.InsertResult.INSERTED) return@execute
                if (ttl > 0) {
                    val forwarded = worldPostFrame(post, ttl - 1)
                    connectedTransportIds().filter { it != sourcePeerId }.forEach { neighbor ->
                        try { sendTextToPeer(neighbor, forwarded) } catch (_: Exception) { }
                    }
                }
                mainHandler.post {
                    if (currentScreen == Screen.WORLD_CHAT) renderWorldTimeline()
                    if (currentScreen == Screen.CHATS) render()
                    Toast.makeText(this, "New public World Chat post from " + post.authorName, Toast.LENGTH_SHORT).show()
                }
            }
            text.startsWith("@MORTAMESH/1/ID|") || text.startsWith("@MORTAMESH/1/PACKET|") -> {
                if (!legacyWarningShown) {
                    legacyWarningShown = true
                    mainHandler.post { Toast.makeText(this, "Morta Mesh protocol updated. Both devices must use the same app version.", Toast.LENGTH_LONG).show() }
                }
            }
            else -> {
                // Plaintext legacy frames are intentionally rejected; silently accepting them would
                // make the security label false when talking to an older build.
                if (!legacyWarningShown) {
                    legacyWarningShown = true
                    mainHandler.post { Toast.makeText(this, "This build requires E2E encryption; plaintext messages from older builds are blocked.", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun applyIdentityAdvertisement(sourcePeerId: String, ttl: Int, advertId: String, advertisedHops: Int, record: JSONObject) {
        val now = System.currentTimeMillis()
        pruneSeenCaches()
        val alreadySeen = synchronized(seenIdentityAdvertisements) {
            val seen = seenIdentityAdvertisements.containsKey(advertId)
            if (!seen) seenIdentityAdvertisements[advertId] = now
            seen
        }
        val nodeId = record.optString("node")
        val displayName = record.optString("name").ifBlank { "Morta Mesh user" }.take(64)
        if (nodeId.isBlank() || nodeId == localNodeId) return
        identityRecords[nodeId] = JSONObject(record.toString())
        if (advertisedHops == 0) {
            synchronized(physicalNodeByTransport) { physicalNodeByTransport[sourcePeerId] = nodeId }
            peerNames[sourcePeerId] = displayName
            peerNames[MESH_PREFIX + nodeId] = displayName
            persistPeerNames()
            privateVault.rebindQueuedRecipient(sourcePeerId, MESH_PREFIX + nodeId)
            // BLE can call onTransportReady before the first signed identity is exchanged.
            // Announce relay consent again only after this direct transport is identity-bound.
            announceAnonymousRelayMode(sourcePeerId)
        }
        val candidateHops = (advertisedHops + 1).coerceAtMost(32)
        val previous = meshRoutes[nodeId]
        val previousFresh = previous != null && now - previous.lastSeen <= MESH_ROUTE_MAX_AGE_MS && hasPeerTransport(previous.nextHop)
        val directRoutePreferred = advertisedHops == 0 && previous?.nextHop != sourcePeerId
        val routeImproved = previous == null || !previousFresh || candidateHops < previous.hops || directRoutePreferred
        if (routeImproved || (previous != null && previous.nextHop == sourcePeerId && candidateHops == previous.hops)) {
            meshRoutes[nodeId] = MeshRoute(nodeId, displayName, sourcePeerId, candidateHops, now)
        } else if (previous != null && previous.name != displayName) {
            meshRoutes[nodeId] = previous.copy(name = displayName)
        }
        if (advertisedHops == 0) {
            // Direct routes win over equal-cost relay routes.
            meshRoutes[nodeId] = MeshRoute(nodeId, displayName, sourcePeerId, 1, now)
        }
        // Duplicate announcements can still improve a route when they arrive over a shorter path.
        // Only forward once per advertisement unless the newly learned path is strictly better.
        val shouldForward = !alreadySeen || routeImproved
        if (ttl > 0 && shouldForward) {
            val forwarded = "$MESH_ID_PREFIX${ttl - 1}|$advertId|${(advertisedHops + 1).coerceAtMost(32)}|${encodeWire(record.toString())}"
            connectedTransportIds().filter { it != sourcePeerId }.forEach { target ->
                try { sendTextToPeer(target, forwarded) } catch (_: Exception) { }
            }
        }
        // A queued media item may become eligible after this identity/route update even though the
        // radio connection itself was already established.
        mainHandler.post { retryQueuedMedia() }
        renderIfBrowseScreen()
        if (currentScreen == Screen.CHAT) refreshChatHeaderOrNav()
    }

    private fun handleRoutedEnvelope(
        sourcePeerId: String, ttl: Int, envelope: JSONObject, opened: MeshSecurity.OpenedMessage?,
        deduplicated: Boolean = false
    ) {
        pruneSeenCaches()
        val messageId = envelope.optString("id")
        val packetCacheKey = "${opened?.fromNodeId ?: envelope.optString("from")}|$messageId"
        if (messageId.isBlank() || packetCacheKey.startsWith("|")) return
        if (!deduplicated) synchronized(seenMeshPackets) {
            if (seenMeshPackets.containsKey(packetCacheKey)) return
            seenMeshPackets[packetCacheKey] = System.currentTimeMillis()
        }
        val destination = envelope.optString("to")
        if (destination == localNodeId) {
            if (opened == null) return // invalid signature, wrong key, or failed AES-GCM tag
            val chatId = chatIdForNode(opened.fromNodeId, sourcePeerId)
            val content = opened.text
            if (content.startsWith(MEDIA_WIRE_PREFIX)) {
                mediaReceiveExecutor.execute { handleIncomingMediaWireText(chatId, content, sourcePeerId, opened.fromNodeId) }
            } else {
                handleDecryptedChatPayload(chatId, opened.fromNodeId, content)
            }
            return
        }
        if (ttl <= 0) return
        val frame = "$MESH_PACKET_PREFIX${ttl - 1}|${encodeWire(envelope.toString())}"
        val route = meshRoutes[destination]?.takeIf { System.currentTimeMillis() - it.lastSeen <= MESH_ROUTE_MAX_AGE_MS }
        val nextHop = route?.nextHop?.takeIf { it != sourcePeerId && hasPeerTransport(it) }
        if (nextHop != null) {
            try { sendTextToPeer(nextHop, frame); return } catch (_: Exception) { }
        }
        // Unknown/stale route: bounded flooding with packet-id deduplication finds an alternate path.
        connectedTransportIds().filter { it != sourcePeerId }.forEach { peer ->
            try { sendTextToPeer(peer, frame) } catch (_: Exception) { }
        }
    }

    private fun chatIdForNode(nodeId: String, incomingTransport: String?): String {
        val wifiId = wifiNodeChatId(nodeId)
        if (wifiWriters.containsKey(wifiId)) return wifiId
        if (incomingTransport != null && nodeForTransport(incomingTransport) == nodeId) return incomingTransport
        val route = meshRoutes[nodeId]
        if (route != null && route.hops == 1 && hasPeerTransport(route.nextHop)) return route.nextHop
        return MESH_PREFIX + nodeId
    }

    private fun resolveTargetNode(chatPeerId: String): String? {
        if (chatPeerId.startsWith(MESH_PREFIX)) return chatPeerId.removePrefix(MESH_PREFIX).takeIf { it.isNotBlank() }
        if (chatPeerId.startsWith(WIFI_NODE_PREFIX)) {
            return chatPeerId.removePrefix(WIFI_NODE_PREFIX).takeIf { isValidNodeId(it) }
        }
        return nodeForTransport(chatPeerId)
            ?: if (chatPeerId.startsWith(WIFI_PREFIX)) meshRoutes.values.firstOrNull { it.nextHop == chatPeerId }?.nodeId else null
    }

    private fun sendSecurePayload(
        chatPeerId: String,
        plaintext: String,
        forcedFirstHop: String? = null,
        asChatText: Boolean = false,
        strictFirstHop: Boolean = false
    ): Boolean {
        if (asChatText) chatSendRateLimited = false
        return try {
            val destination = resolveTargetNode(chatPeerId) ?: return false
            if (destination == localNodeId) return false
            val route = meshRoutes[destination]?.takeIf { System.currentTimeMillis() - it.lastSeen <= MESH_ROUTE_MAX_AGE_MS }
            val preferredForced = forcedFirstHop?.takeIf { hasPeerTransport(it) && nodeForTransport(it) == destination }
            val routedHop = route?.nextHop?.takeIf { hasPeerTransport(it) }
            // If the cached route just went stale, send to any active neighbor; bounded flooding at
            // relays can still find the recipient while a fresh identity advertisement propagates.
            val firstHop = if (strictFirstHop) {
                // Media: only the exact direct link chosen by the transport policy; never fall back.
                preferredForced ?: return false
            } else {
                preferredForced ?: routedHop ?: connectedTransportIds().firstOrNull() ?: return false
            }
            val isWifi = firstHop.startsWith(WIFI_PREFIX)
            val payloads: List<String> = if (asChatText) {
                val textBytes = plaintext.toByteArray(Charsets.UTF_8).size
                if (!isWifi && textBytes > 800) {
                    val parts = splitTextUtf8(plaintext, 800)
                    if (parts.size > MAX_TEXT_CHUNKS) return false
                    val messageId = UUID.randomUUID().toString()
                    parts.mapIndexed { index, part ->
                        JSONObject().put("kind", "text_chunk").put("mid", messageId)
                            .put("index", index).put("total", parts.size).put("text", part).toString()
                    }
                } else {
                    listOf(JSONObject().put("kind", "text").put("text", plaintext).toString())
                }
            } else listOf(plaintext)

            // Reserve the receiver-mirrored budget for the whole chat message before sending any
            // envelopes, so a split message is accepted or paused as a unit.
            if (asChatText) {
                if (!outgoingChatSendLimiter.allowBatch(destination, payloads.size)) {
                    chatSendRateLimited = true
                    return false
                }
            }

            for (payload in payloads) {
                val envelope = meshSecurity.seal(destination, payload) ?: return false
                val wire = "$MESH_PACKET_PREFIX$MESH_ROUTE_TTL|${encodeWire(envelope.toString())}"
                val wireBytes = wire.toByteArray(Charsets.UTF_8).size
                if (isWifi) {
                    if (wireBytes > 256 * 1024) return false
                } else {
                    val mtu = when {
                        clientReady.contains(firstHop) -> clientMtus[firstHop] ?: 23
                        serverDevices.containsKey(firstHop) && subscribedDevices.contains(firstHop) -> serverMtu[firstHop] ?: 23
                        else -> 23
                    }
                    val maxBleFrame = (mtu.coerceAtLeast(23) - 9) * 255
                    if (wireBytes > maxBleFrame) return false
                }
                // Outbound IDs are cached locally for loop suppression; the durable replay ledger
                // is reserved for successfully decrypted incoming messages.
                val packetCacheKey = "${envelope.optString("from")}|${envelope.optString("id")}"
                synchronized(seenMeshPackets) { seenMeshPackets[packetCacheKey] = System.currentTimeMillis() }
                pruneSeenCaches()
                if (isWifi || Looper.myLooper() == Looper.getMainLooper()) {
                    sendTextToPeer(firstHop, wire)
                } else {
                    val latch = CountDownLatch(1)
                    var sendError: Exception? = null
                    mainHandler.post {
                        try {
                            if (!hasPeerTransport(firstHop)) throw java.io.IOException("Nearby link is no longer ready")
                            sendTextToPeer(firstHop, wire)
                        } catch (e: Exception) { sendError = e } finally { latch.countDown() }
                    }
                    if (!latch.await(10, TimeUnit.SECONDS) || sendError != null) return false
                }
            }
            true
        } catch (_: Exception) { false }
    }

    private fun splitTextUtf8(text: String, maxBytes: Int): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var currentBytes = 0
        var offset = 0
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            val fragment = String(Character.toChars(codePoint))
            val size = fragment.toByteArray(Charsets.UTF_8).size
            if (current.isNotEmpty() && currentBytes + size > maxBytes) {
                parts.add(current.toString())
                current.setLength(0)
                currentBytes = 0
            }
            current.append(fragment)
            currentBytes += size
            offset += Character.charCount(codePoint)
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        return parts.ifEmpty { listOf("") }
    }

    private fun handleDecryptedChatPayload(chatId: String, fromNodeId: String, content: String) {
        val payload = try { JSONObject(content) } catch (_: Exception) { return }
        when (payload.optString("kind")) {
            "wifi_chat_request" -> handleIncomingWifiChatRequest(chatId, fromNodeId, payload)
            "wifi_chat_accept" -> handleIncomingWifiChatAcceptance(fromNodeId, payload)
            "wifi_chat_decline" -> handleIncomingWifiChatDecline(fromNodeId, payload)
            "wifi_chat_offer" -> handleIncomingWifiChatOffer(chatId, fromNodeId, payload)
            "wifi_chat_failed" -> handleIncomingWifiChatFailure(fromNodeId, payload)
            "text" -> {
                val text = payload.optString("text")
                if (text.isNotEmpty()) {
                    appendMessage(chatId, ChatMessage(text, false))
                    if (currentScreen != Screen.CHAT || activeChatId != chatId) {
                        Toast.makeText(this, "New encrypted message from ${nameFor(chatId)}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "text_chunk" -> {
                val id = payload.optString("mid")
                val index = payload.optInt("index", -1)
                val total = payload.optInt("total", -1)
                val chunk = payload.optString("text")
                if (id.isBlank() || total !in 2..MAX_TEXT_CHUNKS || index !in 0 until total || chunk.length > 1200) return
                val key = "$fromNodeId|$id"
                val now = System.currentTimeMillis()
                incomingTextFragments.entries.removeAll { now - it.value.createdAt > 60_000L }
                if (!incomingTextFragments.containsKey(key) && incomingTextFragments.size >= 32) {
                    val oldest = incomingTextFragments.minByOrNull { it.value.createdAt }?.key
                    if (oldest != null) incomingTextFragments.remove(oldest)
                }
                val assembly = incomingTextFragments.getOrPut(key) { IncomingTextFragments(total, linkedMapOf(), now) }
                if (assembly.total != total) { incomingTextFragments.remove(key); return }
                assembly.parts.putIfAbsent(index, chunk)
                if (assembly.parts.size == total) {
                    val combined = (0 until total).joinToString("") { assembly.parts[it] ?: "" }
                    incomingTextFragments.remove(key)
                    if (combined.isNotEmpty() && combined.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_CHUNKS * 800) {
                        appendMessage(chatId, ChatMessage(combined, false))
                        if (currentScreen != Screen.CHAT || activeChatId != chatId) {
                            Toast.makeText(this, "New encrypted message from ${nameFor(chatId)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun pruneSeenCaches() {
        val cutoff = System.currentTimeMillis() - 120_000L
        synchronized(seenIdentityAdvertisements) { seenIdentityAdvertisements.entries.removeAll { it.value < cutoff } }
        synchronized(seenMeshPackets) { seenMeshPackets.entries.removeAll { it.value < cutoff } }
    }

    private fun fragmentText(text: String, mtu: Int): List<ByteArray> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val payloadSize = max(1, mtu.coerceAtLeast(23) - 3 - PacketAssembler.HEADER_BYTES)
        val calculatedTotal = max(1, (bytes.size + payloadSize - 1) / payloadSize)
        if (calculatedTotal > 255) throw java.io.IOException("Encrypted BLE frame exceeds the transport limit")
        val total = calculatedTotal
        val id = synchronized(this) { nextMessageId = (nextMessageId + 1) and 0xFFFF; nextMessageId }
        val result = ArrayList<ByteArray>(total)
        for (index in 0 until total) {
            val start = index * payloadSize
            val end = minOf(bytes.size, start + payloadSize)
            val part = bytes.copyOfRange(start, end)
            val packet = ByteArray(PacketAssembler.HEADER_BYTES + part.size)
            packet[0] = PacketAssembler.MAGIC
            packet[1] = PacketAssembler.VERSION
            packet[2] = ((id shr 8) and 0xFF).toByte()
            packet[3] = (id and 0xFF).toByte()
            packet[4] = index.toByte()
            packet[5] = total.toByte()
            System.arraycopy(part, 0, packet, PacketAssembler.HEADER_BYTES, part.size)
            result.add(packet)
        }
        return result
    }

    private object BluetoothStatus { const val ERROR = 257 }

    private object PacketAssembler {
        const val MAGIC: Byte = 0x4D
        const val VERSION: Byte = 1
        const val HEADER_BYTES = 6
        private const val MAX_PARTIALS = 64
        private data class Partial(val total: Int, val parts: MutableMap<Int, ByteArray>, val createdAt: Long)
        private val pending = linkedMapOf<String, Partial>()

        @Synchronized fun accept(address: String, packet: ByteArray): String? {
            if (packet.size < HEADER_BYTES || packet[0] != MAGIC || packet[1] != VERSION) return null
            val id = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
            val partIndex = packet[4].toInt() and 0xFF
            val total = packet[5].toInt() and 0xFF
            if (total !in 1..255 || partIndex !in 0 until total) return null
            val key = "$address:$id"
            val now = System.currentTimeMillis()
            pending.entries.removeAll { now - it.value.createdAt > 60_000 }
            if (!pending.containsKey(key) && pending.size >= MAX_PARTIALS) {
                pending.keys.firstOrNull()?.let { pending.remove(it) } // evict oldest to bound memory use
            }
            val partial = pending.getOrPut(key) { Partial(total, linkedMapOf(), now) }
            if (partial.total != total) { pending.remove(key); return null }
            partial.parts[partIndex] = packet.copyOfRange(HEADER_BYTES, packet.size)
            if (partial.parts.size != total) return null
            val size = partial.parts.values.sumOf { it.size }
            // Secure BLE envelopes and encrypted media frames are larger than plaintext chat.
            if (size > 64 * 1024) { pending.remove(key); return null }
            val output = ByteArray(size)
            var offset = 0
            for (index in 0 until total) {
                val part = partial.parts[index] ?: return null
                System.arraycopy(part, 0, output, offset, part.size)
                offset += part.size
            }
            pending.remove(key)
            return output.toString(Charsets.UTF_8)
        }
    }

    private fun loadHistory() {
        try {
            val encryptedHistory = encryptedChatStore.readText()
            if (encryptedHistory != null) preferences.edit().remove("history").apply()
            val legacyHistory = if (encryptedHistory == null) preferences.getString("history", null) else null
            val serialized = encryptedHistory ?: legacyHistory ?: "[]"
            val array = JSONArray(serialized)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val id = o.getString("peer")
                val item = ChatMessage(
                    text = o.getString("text"),
                    mine = o.getBoolean("mine"),
                    timestamp = o.optLong("time", System.currentTimeMillis()),
                    mediaMimeType = o.optString("mediaMimeType").takeIf { it.isNotBlank() },
                    mediaSize = o.optLong("mediaSize", 0L),
                    deliveryState = o.optString("deliveryState").takeIf { it.isNotBlank() },
                    requiredTransport = o.optString("requiredTransport").takeIf { it.isNotBlank() },
                    transferId = o.optString("transferId").takeIf { it.isNotBlank() }
                )
                chatHistory.getOrPut(id) { mutableListOf() }.add(item)
            }
            if (encryptedHistory == null && legacyHistory != null) {
                if (encryptedChatStore.writeText(legacyHistory)) {
                    preferences.edit().remove("history").apply()
                } else {
                    // Continue showing the in-memory history but never keep writing it in plaintext.
                    preferences.edit().remove("history").apply()
                    chatHistoryStorageLocked = true
                    mainHandler.post { Toast.makeText(this, "History is available for this session, but encryption failed. The plaintext copy was removed; history won't be saved until encrypted storage works.", Toast.LENGTH_LONG).show() }
                }
            }
        } catch (_: Exception) {
            // Preserve an unreadable encrypted file and disable writes instead of silently replacing it.
            chatHistory.clear()
            chatHistoryStorageLocked = true
            mainHandler.post { Toast.makeText(this, "Encrypted chat history couldn't be unlocked. The existing file was preserved; history saving is disabled.", Toast.LENGTH_LONG).show() }
        }
        try {
            val obj = JSONObject(preferences.getString("peer_names", "{}") ?: "{}")
            obj.keys().forEach { peerNames[it] = obj.optString(it) }
        } catch (_: Exception) { }
    }

    private fun saveHistory() {
        if (chatHistoryStorageLocked) return
        val array = JSONArray()
        chatHistory.forEach { (peer, messages) -> messages.takeLast(250).forEach { msg ->
            array.put(
                JSONObject()
                    .put("peer", peer)
                    .put("text", msg.text)
                    .put("mine", msg.mine)
                    .put("time", msg.timestamp)
                    .put("mediaMimeType", msg.mediaMimeType ?: "")
                    .put("mediaSize", msg.mediaSize)
                    .put("deliveryState", msg.deliveryState ?: "")
                    .put("requiredTransport", msg.requiredTransport ?: "")
                    .put("transferId", msg.transferId ?: "")
            )
        } }
        if (encryptedChatStore.writeText(array.toString())) {
            // Remove the old plaintext SharedPreferences copy after the encrypted file is committed.
            preferences.edit().remove("history").apply()
        } else {
            mainHandler.post { Toast.makeText(this, "Encrypted chat history could not be saved. The previous saved copy remains intact.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun persistPeerNames() {
        val obj = JSONObject()
        peerNames.forEach { (id, name) -> obj.put(id, name) }
        preferences.edit().putString("peer_names", obj.toString()).apply()
    }

    private class MeshMark(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = resources.displayMetrics.density
            val size = minOf(width, height).toFloat()
            val rect = android.graphics.RectF(0f, 0f, size, size)
            p.color = 0xFF1877F2.toInt(); p.style = Paint.Style.FILL
            canvas.drawRoundRect(rect, 14f * d, 14f * d, p)
            p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = 2.2f * d; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            val cx = size * .5f; val cy = size * .5f
            val pts = arrayOf(floatArrayOf(cx, cy - 8*d), floatArrayOf(cx - 8*d, cy + 6*d), floatArrayOf(cx + 8*d, cy + 6*d))
            val path = Path().apply { moveTo(pts[0][0], pts[0][1]); lineTo(pts[1][0], pts[1][1]); lineTo(pts[2][0], pts[2][1]); lineTo(pts[0][0], pts[0][1]) }
            canvas.drawPath(path, p)
            p.style = Paint.Style.FILL
            pts.forEach { canvas.drawCircle(it[0], it[1], 3.4f*d, p) }
        }
    }

    private class AvatarView(context: Context, name: String) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "M"
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = minOf(width, height).toFloat()
            paint.color = 0xFFEAF2FF.toInt(); paint.style = Paint.Style.FILL
            canvas.drawCircle(width / 2f, height / 2f, s * .49f, paint)
            paint.color = 0xFF1877F2.toInt(); paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); paint.textSize = s * .42f
            val fm = paint.fontMetrics
            canvas.drawText(initial, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, paint)
        }
    }
}
