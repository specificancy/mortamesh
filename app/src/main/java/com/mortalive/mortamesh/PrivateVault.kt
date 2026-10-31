package com.mortalive.mortamesh

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants

private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
    override fun write(value: Int) = delegate.write(value)
    override fun write(bytes: ByteArray, offset: Int, length: Int) = delegate.write(bytes, offset, length)
    override fun flush() = delegate.flush()
    override fun close() = flush()
}

/**
 * App-private encrypted media storage. Files never enter MediaStore, Downloads, or shared storage.
 * Content is encrypted with AES-GCM using a key protected by Android Keystore. A separate PIN gate
 * controls access to the in-app viewer; the PIN is never stored in clear text.
 */
class PrivateVault(private val context: Context) {
    data class Item(
        val id: String,
        val fileName: String,
        val mimeType: String,
        val size: Long,
        val createdAt: Long,
        val direction: String,
        val peerName: String,
        val recipientId: String? = null,
        val chatPeerId: String? = null,
        val transferId: String? = null,
        val transferState: String = "",
        val senderNodeId: String? = null
    )

    inner class IncomingWrite internal constructor(
        val transferId: String,
        private val itemId: String,
        private val fileName: String,
        private val mimeType: String,
        private val expectedBytes: Long,
        private val peerName: String,
        private val destination: File,
        private val rawOutput: FileOutputStream,
        private val cipherOutput: CipherOutputStream,
        private val senderNodeId: String
    ) : Closeable {
        var writtenBytes: Long = 0L
            private set
        private var finished = false

        @Synchronized fun write(bytes: ByteArray): Boolean {
            if (finished || bytes.isEmpty()) return !finished
            if (writtenBytes + bytes.size > expectedBytes || writtenBytes + bytes.size > MAX_MEDIA_BYTES) {
                abort()
                return false
            }
            return try {
                cipherOutput.write(bytes)
                writtenBytes += bytes.size
                updateIncomingReservation(itemId, (expectedBytes - writtenBytes).coerceAtLeast(0L))
                true
            } catch (_: Exception) {
                abort()
                false
            }
        }

        @Synchronized fun finish(): Item? {
            if (finished) return null
            finished = true
            try {
                // Finalize the GCM tag first, then force the encrypted bytes to stable storage
                // before committing an index row or acknowledging delivery to the sender.
                cipherOutput.close()
                rawOutput.fd.sync()
            } catch (_: Exception) {
                destination.delete()
                try { rawOutput.close() } catch (_: Exception) { }
                releaseIncomingReservation(itemId)
                return null
            }
            try { rawOutput.close() } catch (_: Exception) {
                destination.delete()
                releaseIncomingReservation(itemId)
                return null
            }
            if (writtenBytes != expectedBytes || writtenBytes <= 0L) {
                destination.delete()
                releaseIncomingReservation(itemId)
                return null
            }
            val item = Item(itemId, fileName, mimeType, writtenBytes, System.currentTimeMillis(), "Received", peerName,
                transferId = transferId, transferState = "RECEIVED", senderNodeId = senderNodeId)
            synchronized(this@PrivateVault) {
                if (!canStoreReceivedLocked(writtenBytes, senderNodeId, itemId)) {
                    destination.delete()
                    releaseIncomingReservation(itemId)
                    return null
                }
                items[item.id] = item
                if (!saveIndex()) {
                    items.remove(item.id)
                    destination.delete()
                    releaseIncomingReservation(itemId)
                    return null
                }
                releaseIncomingReservation(itemId)
            }
            return item
        }

        @Synchronized fun abort() {
            if (finished) return
            finished = true
            try { cipherOutput.close() } catch (_: Exception) { }
            try { rawOutput.close() } catch (_: Exception) { }
            destination.delete()
            releaseIncomingReservation(itemId)
        }

        override fun close() = abort()
    }

    private val vaultDir = File(context.filesDir, "private_media_vault").apply { mkdirs() }
    private val indexFile = File(vaultDir, "vault_index.enc")
    private val indexTempFile = File(vaultDir, "vault_index.enc.tmp")
    private val pinPrefs = context.getSharedPreferences("morta_vault_pin", Context.MODE_PRIVATE)
    private val items = linkedMapOf<String, Item>()
    private data class IncomingReservation(val expectedBytes: Long, var remainingBytes: Long, val senderNodeId: String)
    private val incomingReservations = linkedMapOf<String, IncomingReservation>()
    // Set when the index could not be read for a possibly transient reason (e.g. Keystore). While
    // set, nothing is written, so a good index is never replaced by an empty one.
    @Volatile private var indexUnavailable = false
    private var indexLoadedOk = false

    private class InvalidVaultIndexFormatException : Exception()

    init {
        loadIndex()
        cleanupOrphanFiles()
    }

    @Synchronized fun isUnavailable(): Boolean = indexUnavailable

    /** Re-attempt reading the index after a transient failure. Returns true when the vault is usable. */
    @Synchronized fun retryLoad(): Boolean {
        if (!indexUnavailable) return true
        indexUnavailable = false
        items.clear()
        loadIndex()
        cleanupOrphanFiles()
        return !indexUnavailable
    }

    /** Remove encrypted files that no readable index references (crash leftovers). Never runs after an index error. */
    private fun cleanupOrphanFiles() {
        if (!indexLoadedOk || indexUnavailable) return
        val cutoff = System.currentTimeMillis() - ORPHAN_MIN_AGE_MS
        try {
            vaultDir.listFiles { f -> f.isFile && f.name.endsWith(".mmv") }?.forEach { f ->
                if (!items.containsKey(f.name.removeSuffix(".mmv")) && f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Exception) { }
    }

    fun hasPin(): Boolean = !pinPrefs.getString("hash", null).isNullOrBlank() && !pinPrefs.getString("salt", null).isNullOrBlank()

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pinHash(pin, salt)
        pinPrefs.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    /** Milliseconds until another PIN attempt is allowed (0 when not locked out). */
    fun lockoutRemainingMs(): Long {
        val until = pinPrefs.getLong("locked_until", 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun verifyPin(pin: String): Boolean {
        if (lockoutRemainingMs() > 0L) return false
        val salt = decode(pinPrefs.getString("salt", null)) ?: return false
        val expected = decode(pinPrefs.getString("hash", null)) ?: return false
        val actual = pinHash(pin, salt)
        val ok = MessageDigest.isEqual(expected, actual)
        if (ok) {
            pinPrefs.edit().remove("failed").remove("locked_until").apply()
        } else {
            // 5 free attempts, then 30 s doubling up to 1 h. This is a UI deterrent; wall-clock
            // changes can affect it, so it is not a defense against a compromised app process.
            val failed = pinPrefs.getInt("failed", 0) + 1
            val editor = pinPrefs.edit().putInt("failed", failed)
            if (failed >= 5) {
                val delay = (30_000L shl (failed - 5).coerceAtMost(8)).coerceAtMost(3_600_000L)
                editor.putLong("locked_until", System.currentTimeMillis() + delay)
            }
            editor.apply()
        }
        return ok
    }

    private fun pinHash(pin: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 150_000, 256)
        return try { javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    @Synchronized fun allItems(): List<Item> = items.values.sortedByDescending { it.createdAt }

    @Synchronized fun item(id: String): Item? = items[id]

    @Synchronized fun pendingTransfers(): List<Item> = items.values
        .filter { it.direction == "Sent" && !it.recipientId.isNullOrBlank() && !it.transferId.isNullOrBlank() && it.transferState != "DELIVERED" }
        .sortedBy { it.createdAt }

    @Synchronized fun transferById(transferId: String): Item? = items.values.firstOrNull { it.transferId == transferId }

    @Synchronized fun receivedTransfer(transferId: String, senderNodeId: String): Item? =
        items.values.firstOrNull {
            it.direction == "Received" && it.transferId == transferId && it.senderNodeId == senderNodeId
        }

    @Synchronized fun updateTransferState(itemId: String, state: String): Item? {
        val current = items[itemId] ?: return null
        if (current.direction != "Sent") return null
        if (current.transferState == state) return current
        val updated = current.copy(transferState = state)
        items[itemId] = updated
        if (!saveIndex()) {
            items[itemId] = current
            return null
        }
        return updated
    }

    @Synchronized fun markTransferDelivered(transferId: String, authenticatedSenderNodeId: String): Pair<Item, Boolean>? {
        // The signed ACK must come from the exact logical recipient saved with this transfer.
        val expectedRecipientId = "mesh:$authenticatedSenderNodeId"
        val current = items.values.firstOrNull {
            it.direction == "Sent" && it.transferId == transferId && it.recipientId == expectedRecipientId
        } ?: return null
        if (current.transferState == "DELIVERED") return current to false
        val updated = current.copy(transferState = "DELIVERED")
        items[updated.id] = updated
        if (!saveIndex()) {
            items[current.id] = current
            return null
        }
        return updated to true
    }

    @Synchronized fun rebindQueuedRecipient(oldRecipientId: String, newRecipientId: String): Int {
        if (oldRecipientId == newRecipientId) return 0
        val oldItems = items.values.filter {
            it.direction == "Sent" && it.recipientId == oldRecipientId && it.transferState != "DELIVERED"
        }
        if (oldItems.isEmpty()) return 0
        oldItems.forEach { items[it.id] = it.copy(recipientId = newRecipientId) }
        if (!saveIndex()) {
            oldItems.forEach { items[it.id] = it }
            return 0
        }
        return oldItems.size
    }

    @Synchronized fun attachPendingRecipient(itemId: String, recipientId: String, chatPeerId: String, transferId: String): Item? {
        val current = items[itemId] ?: return null
        if (current.direction != "Sent" || !current.recipientId.isNullOrBlank() || current.transferState.isNotBlank()) return null
        val updated = current.copy(recipientId = recipientId, chatPeerId = chatPeerId, transferId = transferId, transferState = "QUEUED")
        items[itemId] = updated
        if (!saveIndex()) {
            items[itemId] = current
            return null
        }
        return updated
    }

    @Synchronized fun delete(item: Item): Boolean {
        val file = File(vaultDir, "${item.id}.mmv")
        val old = items[item.id]
        items.remove(item.id)
        if (!saveIndex()) {
            if (old != null) items[old.id] = old
            return false
        }
        if (file.exists() && !file.delete()) {
            if (old != null) {
                items[old.id] = old
                saveIndex()
            }
            return false
        }
        return true
    }

    @Synchronized fun saveFromFile(
        source: File, originalName: String, mimeType: String, direction: String, peerName: String,
        recipientId: String? = null, chatPeerId: String? = null, transferId: String? = null, transferState: String = ""
    ): Item? {
        val reservedRemaining = incomingReservations.values.sumOf { it.remainingBytes }
        if (indexUnavailable || !source.isFile || source.length() !in 1L..MAX_MEDIA_BYTES ||
            vaultDir.usableSpace - reservedRemaining < source.length() + MIN_FREE_BYTES) return null
        val id = java.util.UUID.randomUUID().toString()
        val destination = File(vaultDir, "$id.mmv")
        return try {
            encryptFile(source, destination)
            val item = Item(
                id, safeFileName(originalName, mimeType), mimeType, source.length(),
                System.currentTimeMillis(), direction, peerName, recipientId, chatPeerId, transferId, transferState
            )
            items[id] = item
            if (!saveIndex()) {
                items.remove(id)
                destination.delete()
                null
            } else item
        } catch (_: Exception) {
            destination.delete()
            null
        }
    }

    @Synchronized fun beginIncoming(
        transferId: String, originalName: String, mimeType: String, expectedBytes: Long,
        peerName: String, senderNodeId: String
    ): IncomingWrite? {
        if (indexUnavailable || expectedBytes !in 1L..MAX_MEDIA_BYTES ||
            runCatching { java.util.UUID.fromString(senderNodeId).toString() }.getOrNull() != senderNodeId) return null
        if (!canStoreReceivedLocked(expectedBytes, senderNodeId)) return null
        // Reserve the announced plaintext size across all active sessions before opening a file.
        val reservedBytes = incomingReservations.values.sumOf { it.remainingBytes }
        if (vaultDir.usableSpace - reservedBytes < expectedBytes + MIN_FREE_BYTES) return null
        val id = java.util.UUID.randomUUID().toString()
        val destination = File(vaultDir, "$id.mmv")
        var raw: FileOutputStream? = null
        return try {
            val output = FileOutputStream(destination)
            raw = output
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // Android Keystore must generate the IV for keys requiring randomized encryption.
            cipher.init(Cipher.ENCRYPT_MODE, getKey())
            val iv = cipher.iv ?: throw IllegalStateException("Keystore did not return a GCM IV")
            check(iv.size == IV_BYTES) { "Unexpected GCM IV size" }
            output.write(MAGIC)
            output.write(iv)
            incomingReservations[id] = IncomingReservation(expectedBytes, expectedBytes, senderNodeId)
            IncomingWrite(
                transferId, id, safeFileName(originalName, mimeType), mimeType, expectedBytes,
                peerName, destination, output, CipherOutputStream(NonClosingOutputStream(output), cipher), senderNodeId
            )
        } catch (_: Exception) {
            try { raw?.close() } catch (_: Exception) { }
            destination.delete()
            incomingReservations.remove(id)
            null
        }
    }

    @Synchronized
    private fun releaseIncomingReservation(itemId: String) {
        incomingReservations.remove(itemId)
    }

    @Synchronized
    private fun updateIncomingReservation(itemId: String, remainingBytes: Long) {
        incomingReservations[itemId]?.remainingBytes = remainingBytes
    }

    /** Enforce stored and in-flight inbound quotas without silently deleting existing vault items. */
    private fun canStoreReceivedLocked(bytes: Long, senderNodeId: String, excludingReservationId: String? = null): Boolean {
        if (bytes <= 0L || bytes > MAX_MEDIA_BYTES) return false
        val storedTotal = items.values.filter { it.direction == "Received" }.sumOf { it.size }
        val storedFromSender = items.values.filter {
            it.direction == "Received" && it.senderNodeId == senderNodeId
        }.sumOf { it.size }
        val reserved = incomingReservations.entries.filter { it.key != excludingReservationId }
        val reservedTotal = reserved.sumOf { it.value.expectedBytes }
        val reservedFromSender = reserved.filter { it.value.senderNodeId == senderNodeId }.sumOf { it.value.expectedBytes }
        return storedTotal <= MAX_RECEIVED_MEDIA_BYTES - reservedTotal - bytes &&
            storedFromSender <= MAX_RECEIVED_MEDIA_PER_SENDER_BYTES - reservedFromSender - bytes
    }

    /** Opens an authenticated decryption stream for sending without creating a plaintext disk copy. */
    @Synchronized fun openDecryptedInput(item: Item): InputStream? {
        val file = File(vaultDir, "${item.id}.mmv")
        if (!file.isFile) return null
        val raw = try { FileInputStream(file) } catch (_: Exception) { return null }
        return try {
            decryptingInput(raw)
        } catch (_: Exception) {
            try { raw.close() } catch (_: Exception) { }
            null
        }
    }

    @Synchronized fun readDecrypted(item: Item): ByteArray? {
        val file = File(vaultDir, "${item.id}.mmv")
        if (!file.isFile || item.size > MAX_IMAGE_READ_BYTES) return null
        return try {
            FileInputStream(file).use { input ->
                val cipherInput = decryptingInput(input)
                cipherInput.use { cipher ->
                    val output = ByteArrayOutputStream(item.size.toInt().coerceAtMost(8 * 1024 * 1024))
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = cipher.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_READ_BYTES) throw IllegalStateException("Photo is too large to preview in memory")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
        } catch (_: Exception) { null }
    }

    @Synchronized fun decryptToTemp(item: Item, destination: File): Boolean {
        val encrypted = File(vaultDir, "${item.id}.mmv")
        if (!encrypted.isFile) return false
        return try {
            FileInputStream(encrypted).use { input ->
                decryptingInput(input).use { cipher ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val count = cipher.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > item.size || total > MAX_MEDIA_BYTES) throw IllegalStateException("Media size mismatch")
                            output.write(buffer, 0, count)
                        }
                        if (total != item.size) throw IllegalStateException("Media is incomplete")
                    }
                }
            }
            true
        } catch (_: Exception) { destination.delete(); false }
    }

    private fun encryptFile(source: File, destination: File) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore generates the IV when randomized encryption is required.
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val iv = cipher.iv ?: throw IllegalStateException("Keystore did not return a GCM IV")
        check(iv.size == IV_BYTES) { "Unexpected GCM IV size" }
        FileOutputStream(destination).use { raw ->
            raw.write(MAGIC)
            raw.write(iv)
            val output = CipherOutputStream(NonClosingOutputStream(raw), cipher)
            try {
                FileInputStream(source).use { input -> input.copyTo(output, 32 * 1024) }
                output.close()
                raw.fd.sync()
            } catch (e: Exception) {
                try { output.close() } catch (_: Exception) { }
                throw e
            }
        }
    }

    private fun decryptingInput(input: InputStream): CipherInputStream {
        val magic = ByteArray(MAGIC.size)
        readFully(input, magic)
        if (!magic.contentEquals(MAGIC)) throw IllegalStateException("Unknown vault file")
        val iv = ByteArray(IV_BYTES)
        readFully(input, iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return CipherInputStream(input, cipher)
    }

    private fun readFully(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw java.io.EOFException()
            offset += count
        }
    }

    private fun getKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized private fun saveIndex(): Boolean {
        if (indexUnavailable) return false
        return try {
            val array = JSONArray()
            items.values.forEach { item ->
                array.put(JSONObject()
                    .put("id", item.id).put("fileName", item.fileName).put("mimeType", item.mimeType)
                    .put("size", item.size).put("createdAt", item.createdAt)
                    .put("direction", item.direction).put("peerName", item.peerName)
                    .put("recipientId", item.recipientId ?: "").put("chatPeerId", item.chatPeerId ?: "")
                    .put("transferId", item.transferId ?: "").put("transferState", item.transferState)
                    .put("senderNodeId", item.senderNodeId ?: ""))
            }
            val plain = array.toString().toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getKey())
            val iv = cipher.iv ?: throw IllegalStateException("Keystore did not return a GCM IV")
            check(iv.size == IV_BYTES) { "Unexpected GCM IV size" }
            val encrypted = cipher.doFinal(plain)
            FileOutputStream(indexTempFile).use { output ->
                output.write(MAGIC)
                output.write(iv)
                output.write(encrypted)
                output.fd.sync()
            }
            // The index update is atomic: never replace a good index with a partial write.
            Os.rename(indexTempFile.absolutePath, indexFile.absolutePath)
            syncVaultDirectory()
            indexLoadedOk = true
            true
        } catch (_: Exception) {
            try { indexTempFile.delete() } catch (_: Exception) { }
            false
        }
    }

    /** Persist directory-entry changes as well as the file contents before reporting commit. */
    private fun syncVaultDirectory() {
        val directoryFd = Os.open(vaultDir.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(directoryFd)
        } finally {
            Os.close(directoryFd)
        }
    }

    private fun loadIndex() {
        if (!indexFile.isFile) return
        try {
            FileInputStream(indexFile).use { input ->
                val magic = ByteArray(MAGIC.size); readFully(input, magic)
                if (!magic.contentEquals(MAGIC)) throw InvalidVaultIndexFormatException()
                val iv = ByteArray(IV_BYTES); readFully(input, iv)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                val plain = cipher.doFinal(input.readBytes())
                val array = JSONArray(String(plain, Charsets.UTF_8))
                for (index in 0 until array.length()) {
                    val row = array.optJSONObject(index) ?: continue
                    val item = Item(
                        row.optString("id"), row.optString("fileName"), row.optString("mimeType", "application/octet-stream"),
                        row.optLong("size"), row.optLong("createdAt"), row.optString("direction"), row.optString("peerName"),
                        row.optString("recipientId").takeIf { it.isNotBlank() },
                        row.optString("chatPeerId").takeIf { it.isNotBlank() },
                        row.optString("transferId").takeIf { it.isNotBlank() },
                        row.optString("transferState"),
                        row.optString("senderNodeId").takeIf { it.isNotBlank() }
                    )
                    if (item.id.isNotBlank() && File(vaultDir, "${item.id}.mmv").isFile) items[item.id] = item
                }
            }
            indexLoadedOk = true
        } catch (e: Exception) {
            items.clear()
            val permanent = e is javax.crypto.BadPaddingException || e is InvalidVaultIndexFormatException ||
                e is java.io.EOFException || e is javax.crypto.IllegalBlockSizeException || e is org.json.JSONException
            if (!permanent) {
                // Possibly a transient Keystore/provider failure: leave the index untouched and block writes.
                indexUnavailable = true
                return
            }
            // Preserve a corrupt/unreadable index for diagnosis instead of silently overwriting it.
            if (indexFile.exists()) {
                val unreadable = File(vaultDir, "vault_index.enc.unreadable-" + System.currentTimeMillis())
                try { Os.rename(indexFile.absolutePath, unreadable.absolutePath) } catch (_: Exception) { }
            }
        }
    }

    private fun safeFileName(name: String, mime: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._ -]"), "_").take(70).trim()
        if (cleaned.isNotBlank() && cleaned != "." && cleaned != "..") return cleaned
        return if (mime.startsWith("video/")) "private_video.mp4" else "private_photo.jpg"
    }

    private fun decode(value: String?): ByteArray? = try { if (value.isNullOrBlank()) null else Base64.decode(value, Base64.NO_WRAP) } catch (_: Exception) { null }

    companion object {
        // Media is streamed to encrypted files; preview decoding is separately downsampled.
        const val MAX_MEDIA_BYTES = 2L * 1024L * 1024L * 1024L
        const val MAX_IMAGE_READ_BYTES = 20L * 1024L * 1024L
        private const val MAX_RECEIVED_MEDIA_BYTES = 8L * 1024L * 1024L * 1024L
        private const val MAX_RECEIVED_MEDIA_PER_SENDER_BYTES = 4L * 1024L * 1024L * 1024L
        private const val MIN_FREE_BYTES = 64L * 1024L * 1024L
        private const val ORPHAN_MIN_AGE_MS = 60L * 60L * 1000L
        private const val KEY_ALIAS = "morta_mesh_private_media_key_v1"
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private val MAGIC = byteArrayOf(0x4D, 0x4D, 0x56, 0x31) // MMV1
    }
}
