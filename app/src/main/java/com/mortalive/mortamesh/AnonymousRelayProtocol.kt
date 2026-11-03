package com.mortalive.mortamesh

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.ceil

/**
 * Chunk framing for opaque sender-to-ingestion ciphertext. Only Wi-Fi Direct is used for payload
 * chunks; BLE remains available for discovery/control, not bulk anonymous uploads.
 */
internal object AnonymousRelayProtocol {
    const val PREFIX = "@MORTAMESH/2/ANON|"
    const val MAX_FRAME_BYTES = 380 * 1024
    const val CHUNK_BYTES = AnonymousSubmissionPolicy.RELAY_CHUNK_BYTES
    const val MAX_CHUNKS = 1_200

    private val verifiedManifestCache = object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 128
    }

    private fun verifyManifestCached(manifest: JSONObject): Boolean {
        val fingerprint = sha256(manifest.toString().toByteArray(Charsets.UTF_8))
        synchronized(verifiedManifestCache) {
            if (verifiedManifestCache[fingerprint] == true) return true
        }
        if (!AnonymousSubmissionCrypto.verifyManifest(manifest)) return false
        synchronized(verifiedManifestCache) { verifiedManifestCache[fingerprint] = true }
        return true
    }

    data class Frame(
        val transferId: String,
        val ttl: Int,
        val index: Int,
        val total: Int,
        val manifest: JSONObject,
        val chunk: ByteArray
    )

    fun forEachFrame(
        transferId: String,
        manifest: JSONObject,
        ciphertextFile: File,
        ttl: Int,
        action: (String) -> Unit
    ) {
        require(ttl in 0..AnonymousSubmissionPolicy.MAX_RELAY_HOPS)
        require(verifyManifestCached(manifest))
        require(manifest.optString("transferId") == transferId)
        require(ciphertextFile.isFile)
        require(ciphertextFile.length() == manifest.optLong("ciphertextBytes"))
        require(sha256(ciphertextFile) == manifest.optString("ciphertextSha256"))
        val total = ceil(ciphertextFile.length().toDouble() / CHUNK_BYTES).toInt()
        require(total in 1..MAX_CHUNKS) { "Anonymous package exceeds the chunk-count limit." }

        FileInputStream(ciphertextFile).use { input ->
            for (index in 0 until total) {
                val expected = minOf(CHUNK_BYTES.toLong(), ciphertextFile.length() - index.toLong() * CHUNK_BYTES).toInt()
                val chunk = ByteArray(expected)
                var offset = 0
                while (offset < expected) {
                    val count = input.read(chunk, offset, expected - offset)
                    if (count < 0) throw java.io.EOFException("Encrypted package ended before all chunks were read.")
                    offset += count
                }
                action(encodeFrame(transferId, ttl, index, total, manifest, chunk))
            }
        }
    }

    fun encodeFrame(
        transferId: String,
        ttl: Int,
        index: Int,
        total: Int,
        manifest: JSONObject,
        chunk: ByteArray
    ): String {
        require(ttl in 0..AnonymousSubmissionPolicy.MAX_RELAY_HOPS)
        require(index in 0 until total && total in 1..MAX_CHUNKS)
        require(chunk.isNotEmpty() && chunk.size <= CHUNK_BYTES)
        require(AnonymousSubmissionCrypto.verifyManifest(manifest))
        require(manifest.optString("transferId") == transferId)
        require(manifest.optJSONArray("chunkSha256s")?.optString(index) == sha256(chunk)) {
            "Chunk does not match the origin-signed manifest."
        }
        val json = JSONObject()
            .put("v", 1)
            .put("id", transferId)
            .put("ttl", ttl)
            .put("index", index)
            .put("total", total)
            .put("manifest", manifest.toString())
            .put("chunkSha256", sha256(chunk))
            .put("data", Base64.getUrlEncoder().withoutPadding().encodeToString(chunk))
        val frame = PREFIX + json.toString()
        require(frame.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES)
        return frame
    }

    fun parse(frameText: String): Frame? {
        return try {
            if (!frameText.startsWith(PREFIX) || frameText.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) return null
            val json = JSONObject(frameText.removePrefix(PREFIX))
            if (json.optInt("v") != 1) return null
            val transferId = json.optString("id")
            val ttl = json.optInt("ttl", -1)
            val index = json.optInt("index", -1)
            val total = json.optInt("total", -1)
            if (!isCanonicalUuid(transferId) || ttl !in 0..AnonymousSubmissionPolicy.MAX_RELAY_HOPS) return null
            if (total !in 1..MAX_CHUNKS || index !in 0 until total) return null
            val manifest = JSONObject(json.getString("manifest"))
            if (!verifyManifestCached(manifest) || manifest.optString("transferId") != transferId) return null
            val expectedTotal = ceil(manifest.optLong("ciphertextBytes").toDouble() / CHUNK_BYTES).toInt()
            if (expectedTotal != total) return null
            val start = index.toLong() * CHUNK_BYTES
            val expectedBytes = minOf(CHUNK_BYTES.toLong(), manifest.optLong("ciphertextBytes") - start).toInt()
            if (expectedBytes !in 1..CHUNK_BYTES) return null
            val chunk = Base64.getUrlDecoder().decode(json.getString("data"))
            if (chunk.size != expectedBytes || sha256(chunk) != json.optString("chunkSha256")) return null
            if (manifest.optJSONArray("chunkSha256s")?.optString(index) != sha256(chunk)) return null
            Frame(transferId, ttl, index, total, manifest, chunk)
        } catch (_: Exception) { null }
    }


    fun forwardWithTtl(frameText: String, ttl: Int): String? {
        val parsed = parse(frameText) ?: return null
        if (ttl !in 0 until parsed.ttl) return null
        return encodeFrame(parsed.transferId, ttl, parsed.index, parsed.total, parsed.manifest, parsed.chunk)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun isCanonicalUuid(value: String): Boolean = try {
        java.util.UUID.fromString(value).toString() == value
    } catch (_: Exception) { false }
}
