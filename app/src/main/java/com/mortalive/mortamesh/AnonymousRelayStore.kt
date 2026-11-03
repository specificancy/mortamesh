package com.mortalive.mortamesh

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * Persistent store-carry-forward cache for already-encrypted anonymous packages.
 * It stores ciphertext only and never opens the submitted media.
 */
internal class AnonymousRelayStore(context: Context) {
    data class Transfer(
        val id: String,
        val manifest: JSONObject,
        val ttl: Int,
        val totalChunks: Int,
        val receivedChunks: Int,
        val completeFile: File?,
        val localOrigin: Boolean
    )

    enum class AcceptResult { STORED, COMPLETE, DUPLICATE, INVALID, QUOTA_REJECTED }

    private val root = File(context.applicationContext.filesDir, "anonymous_relay_queue").apply { mkdirs() }

    @Synchronized
    fun enqueueLocalPackage(packageValue: AnonymousSubmissionCrypto.EncryptedPackage): Boolean {
        if (!AnonymousSubmissionCrypto.verifyManifest(packageValue.manifest) ||
            packageValue.manifest.optString("transferId") != packageValue.transferId ||
            !packageValue.ciphertextFile.isFile ||
            packageValue.ciphertextFile.length() != packageValue.manifest.optLong("ciphertextBytes") ||
            AnonymousRelayProtocol.sha256(packageValue.ciphertextFile) != packageValue.manifest.optString("ciphertextSha256")) return false
        pruneExpired()
        if (listTransfers().size >= MAX_TRANSFERS) return false
        if (directoryBytes(root) + packageValue.ciphertextFile.length() > MAX_BYTES) return false
        val dir = transferDir(packageValue.transferId)
        if (!dir.mkdirs()) return false
        return try {
            check(writeAtomic(File(dir, "manifest.json"), packageValue.manifest.toString().toByteArray(Charsets.UTF_8)))
            check(writeAtomic(File(dir, "state.json"), JSONObject()
                .put("ttl", AnonymousSubmissionPolicy.MAX_RELAY_HOPS)
                .put("localOrigin", true)
                .put("updatedAt", System.currentTimeMillis())
                .toString().toByteArray(Charsets.UTF_8)))
            val temporary = File(dir, "ready.tmp")
            FileInputStream(packageValue.ciphertextFile).use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output, 64 * 1024); output.fd.sync() }
            }
            check(temporary.renameTo(File(dir, "ready.bin")))
            true
        } catch (_: Exception) {
            dir.deleteRecursively()
            false
        }
    }

    @Synchronized
    fun acceptFrame(text: String): AcceptResult {
        val frame = AnonymousRelayProtocol.parse(text) ?: return AcceptResult.INVALID
        pruneExpired()
        val dir = transferDir(frame.transferId)
        val manifestFile = File(dir, "manifest.json")
        val stateFile = File(dir, "state.json")
        val chunkFile = chunkFile(dir, frame.index)
        if (!dir.exists()) {
            if (listTransfers().size >= MAX_TRANSFERS) return AcceptResult.QUOTA_REJECTED
            if (directoryBytes(root) + frame.chunk.size > MAX_BYTES) return AcceptResult.QUOTA_REJECTED
            if (!dir.mkdirs()) return AcceptResult.QUOTA_REJECTED
            if (!writeAtomic(manifestFile, frame.manifest.toString().toByteArray(Charsets.UTF_8))) {
                dir.deleteRecursively()
                return AcceptResult.QUOTA_REJECTED
            }
        } else {
            val saved = runCatching { JSONObject(manifestFile.readText()) }.getOrNull()
                ?: return AcceptResult.INVALID
            if (saved.optString("originSignature") != frame.manifest.optString("originSignature") ||
                saved.optString("ciphertextSha256") != frame.manifest.optString("ciphertextSha256")) {
                return AcceptResult.INVALID
            }
        }
        if (File(dir, "ready.bin").isFile || chunkFile.exists()) return AcceptResult.DUPLICATE
        if (directoryBytes(root) + frame.chunk.size > MAX_BYTES) return AcceptResult.QUOTA_REJECTED
        if (!writeAtomic(chunkFile, frame.chunk)) return AcceptResult.QUOTA_REJECTED

        val previousTtl = runCatching { JSONObject(stateFile.readText()).optInt("ttl", 0) }.getOrDefault(0)
        val savedState = JSONObject().put("ttl", maxOf(previousTtl, frame.ttl)).put("localOrigin", false).put("updatedAt", System.currentTimeMillis())
        if (!writeAtomic(stateFile, savedState.toString().toByteArray(Charsets.UTF_8))) {
            chunkFile.delete()
            return AcceptResult.QUOTA_REJECTED
        }
        val present = chunkFiles(dir).count { it.isFile }
        if (present == frame.total) {
            val ready = File(dir, "ready.bin")
            val temporary = File(dir, "ready.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    for (index in 0 until frame.total) {
                        val part = chunkFile(dir, index)
                        if (!part.isFile) throw java.io.IOException("A relay chunk is missing.")
                        FileInputStream(part).use { input -> input.copyTo(output, 32 * 1024) }
                    }
                    output.fd.sync()
                }
                val expectedBytes = frame.manifest.optLong("ciphertextBytes")
                if (temporary.length() != expectedBytes ||
                    AnonymousRelayProtocol.sha256(temporary) != frame.manifest.optString("ciphertextSha256")) {
                    temporary.delete()
                    dir.deleteRecursively()
                    return AcceptResult.INVALID
                }
                check(temporary.renameTo(ready))
                chunkFiles(dir).forEach { it.delete() }
                return AcceptResult.COMPLETE
            } catch (_: Exception) {
                temporary.delete()
                return AcceptResult.INVALID
            }
        }
        return AcceptResult.STORED
    }

    @Synchronized
    fun listTransfers(): List<Transfer> {
        pruneDirectoriesOnly()
        return root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val manifestFile = File(dir, "manifest.json")
            val stateFile = File(dir, "state.json")
            val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrNull() ?: return@mapNotNull null
            if (!AnonymousSubmissionCrypto.verifyManifest(manifest)) return@mapNotNull null
            val id = manifest.optString("transferId")
            if (!isCanonicalUuid(id) || dir.name != id) return@mapNotNull null
            val state = runCatching { JSONObject(stateFile.readText()) }.getOrDefault(JSONObject())
            val ttl = state.optInt("ttl", 0).coerceIn(0, AnonymousSubmissionPolicy.MAX_RELAY_HOPS)
            val localOrigin = state.optBoolean("localOrigin", false)
            val total = kotlin.math.ceil(manifest.optLong("ciphertextBytes").toDouble() / AnonymousRelayProtocol.CHUNK_BYTES).toInt()
            val ready = File(dir, "ready.bin").takeIf { it.isFile }
            val received = if (ready != null) total else chunkFiles(dir).count { it.isFile }
            Transfer(id, manifest, ttl, total, received, ready, localOrigin)
        }?.sortedBy { it.id } ?: emptyList()
    }

    @Synchronized
    fun forEachStoredFrame(transferId: String, ttl: Int, action: (String) -> Unit) {
        if (!isCanonicalUuid(transferId) || ttl !in 0..AnonymousSubmissionPolicy.MAX_RELAY_HOPS) return
        val dir = transferDir(transferId)
        val transfer = listTransfers().firstOrNull { it.id == transferId } ?: return
        if (transfer.completeFile != null) {
            AnonymousRelayProtocol.forEachFrame(transferId, transfer.manifest, transfer.completeFile, ttl, action)
            return
        }
        for (index in 0 until transfer.totalChunks) {
            val file = chunkFile(dir, index)
            if (!file.isFile) continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            runCatching {
                action(AnonymousRelayProtocol.encodeFrame(transferId, ttl, index, transfer.totalChunks, transfer.manifest, bytes))
            }
        }
    }

    @Synchronized
    fun queuedBytes(): Long = directoryBytes(root)

    @Synchronized
    fun clearAll(): Boolean {
        var success = true
        root.listFiles()?.forEach { entry ->
            if (!entry.deleteRecursively()) success = false
        }
        return success
    }

    @Synchronized
    fun pruneExpired() {
        val now = System.currentTimeMillis()
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val manifest = runCatching { JSONObject(File(dir, "manifest.json").readText()) }.getOrNull()
            val expires = manifest?.optLong("expiresAt", 0L) ?: 0L
            if (expires <= now || now - dir.lastModified() > AnonymousSubmissionPolicy.RETENTION_MS) {
                dir.deleteRecursively()
            }
        }
    }

    private fun pruneDirectoriesOnly() {
        pruneExpired()
    }

    private fun transferDir(id: String) = File(root, id)
    private fun chunkFile(dir: File, index: Int) = File(dir, "chunk_%04d.bin".format(index))
    private fun chunkFiles(dir: File) = dir.listFiles { file -> file.name.startsWith("chunk_") && file.name.endsWith(".bin") } ?: emptyArray()

    private fun writeAtomic(target: File, bytes: ByteArray): Boolean {
        val temporary = File(target.parentFile, target.name + ".tmp")
        return try {
            FileOutputStream(temporary).use { out -> out.write(bytes); out.fd.sync() }
            if (target.exists()) target.delete()
            check(temporary.renameTo(target))
            true
        } catch (_: Exception) {
            temporary.delete()
            false
        }
    }

    private fun directoryBytes(directory: File): Long =
        directory.listFiles()?.sumOf { if (it.isDirectory) directoryBytes(it) else it.length() } ?: 0L

    private fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: Exception) { false }

    companion object {
        private const val MAX_TRANSFERS = 20
        private const val MAX_BYTES = 250L * 1024L * 1024L
    }
}
