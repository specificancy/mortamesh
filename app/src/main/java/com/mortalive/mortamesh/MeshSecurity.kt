package com.mortalive.mortamesh

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local identity and end-to-end message protection for Morta Mesh.
 *
 * Each message uses an ephemeral P-256 ECDH key, HKDF-SHA256, and AES-256-GCM; its canonical
 * envelope is signed with the sender's persistent P-256 identity key. Identity private material is
 * wrapped with an Android Keystore AES-GCM key and kept in app-private storage.
 *
 * First contact uses trust-on-first-use (TOFU). It is not a substitute for comparing fingerprints
 * out of band, nor for an independently audited ratchet protocol in a production messenger.
 */
internal class MeshSecurity(
    context: Context,
    private val localNodeId: String,
    private val preferences: SharedPreferences
) {
    data class OpenedMessage(val fromNodeId: String, val messageId: String, val text: String)

    enum class IdentityResult { ACCEPTED, KEY_CHANGED, INVALID }

    private val appContext = context.applicationContext
    private val identityFile = File(appContext.filesDir, "morta_mesh_identity.enc")
    private val wrapKeyAlias = "morta_mesh_identity_wrap_v1"
    private val random = SecureRandom()
    private val identityPair: KeyPair = loadOrCreateIdentity()
    private val publicKeyEncoded = b64(identityPair.public.encoded)
    private val knownPublicKeys = linkedMapOf<String, String>()

    init {
        preferences.all.forEach { (key, value) ->
            if (key.startsWith(PINNED_KEY_PREFIX) && value is String) {
                val node = key.removePrefix(PINNED_KEY_PREFIX)
                if (node.isNotBlank() && value.isNotBlank()) knownPublicKeys[node] = value
            }
        }
    }

    /** Stable UUIDv8 identifier derived from SHA-256 of this installation's public key. */
    @Synchronized
    fun derivedLocalNodeId(): String = deriveNodeIdFromEncodedPublicKey(publicKeyEncoded)
        ?: throw IllegalStateException("Could not derive node ID from the local public key")

    @Synchronized
    fun identityRecord(displayName: String): JSONObject {
        val name = displayName.take(64).ifBlank { "Morta user" }
        check(derivedLocalNodeId() == localNodeId) { "Local node ID is not bound to its identity key" }
        val unsigned = JSONObject()
            .put("v", 2)
            .put("node", localNodeId)
            .put("name", name)
            .put("pub", publicKeyEncoded)
        unsigned.put("sig", sign(identityCanonical(unsigned)))
        return unsigned
    }

    /** Verify an identity advertisement without pinning it. Wi-Fi hello calls this before proof-of-possession. */
    @Synchronized
    fun verifyIdentityRecord(record: JSONObject): Boolean {
        return try {
            if (record.optInt("v") != 2) return false
            val node = record.optString("node")
            val name = record.optString("name")
            val pub = record.optString("pub")
            val signature = record.optString("sig")
            if (!isValidNodeId(node) || node == localNodeId || name.isBlank() || name.length > 64 ||
                pub.length !in 64..512 || signature.length !in 40..512) return false
            val publicKey = decodePublicKey(pub) ?: return false
            deriveNodeIdFromEncodedPublicKey(pub) == node && verify(publicKey, identityCanonical(record), signature)
        } catch (_: Exception) {
            false
        }
    }

    /** Pin a valid identity only after the transport has proved possession of its private key. */
    @Synchronized
    fun acceptIdentity(record: JSONObject): IdentityResult {
        if (!verifyIdentityRecord(record)) return IdentityResult.INVALID
        val node = record.optString("node")
        val name = record.optString("name")
        val pub = record.optString("pub")
        synchronized(knownPublicKeys) {
            val previous = knownPublicKeys[node]
            if (previous != null && previous != pub) return IdentityResult.KEY_CHANGED
            if (previous == null) {
                knownPublicKeys[node] = pub
                preferences.edit().putString(PINNED_KEY_PREFIX + node, pub).apply()
            }
        }
        preferences.edit().putString(PINNED_NAME_PREFIX + node, name).apply()
        return IdentityResult.ACCEPTED
    }


    /** Create a deliberately public post signed by this installation's identity key. */
    @Synchronized
    fun createWorldPost(displayName: String, text: String): JSONObject? {
        if (!WorldChatPolicy.isValidPostText(text) || derivedLocalNodeId() != localNodeId) return null
        return try {
            val rawName = displayName.trim()
            val name = if (rawName.codePointCount(0, rawName.length) > 64) rawName.substring(0, rawName.offsetByCodePoints(0, 64)) else rawName
            val post = JSONObject()
                .put("v", 1)
                .put("id", UUID.randomUUID().toString())
                .put("node", localNodeId)
                .put("name", name.ifBlank { "Morta user" })
                .put("ts", System.currentTimeMillis())
                .put("text", text)
                .put("pub", publicKeyEncoded)
            post.put("sig", sign(worldPostCanonical(post)))
            post
        } catch (_: Exception) { null }
    }

    /** Verify the self-contained public key, node binding, freshness, content bound and signature. */
    @Synchronized
    fun verifyWorldPost(post: JSONObject): Boolean {
        return try {
            if (post.optInt("v") != 1) return false
            val id = post.optString("id")
            val node = post.optString("node")
            val name = post.optString("name")
            val timestamp = post.optLong("ts", -1L)
            val text = post.optString("text")
            val pub = post.optString("pub")
            val signature = post.optString("sig")
            val now = System.currentTimeMillis()
            if (!isValidNonce(id) || !isValidNodeId(node) ||
                name.isBlank() || name.codePointCount(0, name.length) > 64 || name.any { it.isISOControl() } ||
                timestamp < now - MAX_ENVELOPE_AGE_MS || timestamp > now + MAX_FUTURE_SKEW_MS ||
                !WorldChatPolicy.isValidPostText(text) || text.toByteArray(Charsets.UTF_8).size > 2_100 ||
                pub.length !in 64..512 || signature.length !in 40..512) return false
            val expectedNode = deriveNodeIdFromEncodedPublicKey(pub) ?: return false
            if (expectedNode != node) return false
            val pinned = knownPublicKeys[node] ?: preferences.getString(PINNED_KEY_PREFIX + node, null)
            if (pinned != null && pinned != pub) return false
            val publicKey = decodePublicKey(pub) ?: return false
            verify(publicKey, worldPostCanonical(post), signature)
        } catch (_: Exception) { false }
    }

    /** Sign a fresh Wi-Fi Direct challenge transcript with this installation's identity key. */
    @Synchronized
    fun signWifiHelloProof(toNodeId: String, senderNonce: String, recipientNonce: String): String? {
        if (!isValidNodeId(toNodeId) || toNodeId == localNodeId ||
            !isValidNonce(senderNonce) || !isValidNonce(recipientNonce) || senderNonce == recipientNonce ||
            derivedLocalNodeId() != localNodeId) return null
        return try { sign(wifiHelloCanonical(localNodeId, toNodeId, senderNonce, recipientNonce)) }
        catch (_: Exception) { null }
    }

    /** Verify the challenge proof against the exact identity record received on this socket. */
    @Synchronized
    fun verifyWifiHelloProof(
        identityRecord: JSONObject,
        fromNodeId: String,
        toNodeId: String,
        senderNonce: String,
        recipientNonce: String,
        encodedProof: String
    ): Boolean {
        if (toNodeId != localNodeId || !isValidNodeId(fromNodeId) ||
            !isValidNonce(senderNonce) || !isValidNonce(recipientNonce) || senderNonce == recipientNonce ||
            identityRecord.optString("node") != fromNodeId || !verifyIdentityRecord(identityRecord) ||
            encodedProof.length !in 40..512) return false
        val publicKey = decodePublicKey(identityRecord.optString("pub")) ?: return false
        return verify(publicKey, wifiHelloCanonical(fromNodeId, toNodeId, senderNonce, recipientNonce), encodedProof)
    }

    @Synchronized
    fun isValidWifiNonce(value: String): Boolean = isValidNonce(value)

    @Synchronized
    fun publicKeyFor(nodeId: String): String? = knownPublicKeys[nodeId]
        ?: preferences.getString(PINNED_KEY_PREFIX + nodeId, null)

    @Synchronized
    fun displayNameFor(nodeId: String): String? = preferences.getString(PINNED_NAME_PREFIX + nodeId, null)

    @Synchronized
    fun fingerprintFor(nodeId: String): String? {
        val encoded = publicKeyFor(nodeId) ?: if (nodeId == localNodeId) publicKeyEncoded else return null
        val bytes = try { Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
            catch (_: Exception) { return null }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { String.format(java.util.Locale.ROOT, "%02X", it.toInt() and 0xFF) }
            .chunked(4).joinToString(" ")
    }

    /** Encrypt and sign one message for the recipient's pinned public key. */
    @Synchronized
    fun seal(toNodeId: String, plaintext: String): JSONObject? {
        return try {
            val recipientEncoded = publicKeyFor(toNodeId) ?: return null
            val recipientPublic = decodePublicKey(recipientEncoded) ?: return null
            val ephemeral = newEcKeyPair()
            val messageId = java.util.UUID.randomUUID().toString()
            val salt = ByteArray(16).also(random::nextBytes)
            val iv = ByteArray(12).also(random::nextBytes)
            val ephemeralEncoded = b64(ephemeral.public.encoded)
            val saltEncoded = b64(salt)
            val ivEncoded = b64(iv)
            val timestamp = System.currentTimeMillis()
            val aad = aad(localNodeId, toNodeId, messageId, timestamp, ephemeralEncoded, saltEncoded)
            val shared = deriveShared(ephemeral.private, recipientPublic)
            val keyBytes = hkdf(shared, salt, "MortaMesh-E2E-v2|$localNodeId|$toNodeId|$messageId".toByteArray(Charsets.UTF_8), 32)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val ciphertext = b64(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
            val envelope = JSONObject()
                .put("v", 2)
                .put("id", messageId)
                .put("from", localNodeId)
                .put("to", toNodeId)
                .put("ts", timestamp)
                .put("epk", ephemeralEncoded)
                .put("salt", saltEncoded)
                .put("iv", ivEncoded)
                .put("ct", ciphertext)
                .put("pub", publicKeyEncoded)
            envelope.put("sig", sign(envelopeCanonical(envelope)))
            envelope
        } catch (_: Exception) { null }
    }

    /**
     * Validate a signed envelope before a relay spends cache/routing resources on it.
     * Relays may not know the sender yet, so this verifies the embedded signature but never
     * pins an unknown key. If a sender key is already pinned, any different key is rejected.
     */
    @Synchronized
    fun verifyRoutableEnvelope(envelope: JSONObject): Boolean {
        return try {
            if (envelope.optInt("v") != 2) return false
            val messageId = envelope.optString("id")
            val from = envelope.optString("from")
            val to = envelope.optString("to")
            val timestamp = envelope.optLong("ts", -1L)
            val now = System.currentTimeMillis()
            if (timestamp <= 0L || timestamp > now + MAX_FUTURE_SKEW_MS || now - timestamp > MAX_ENVELOPE_AGE_MS) return false
            val senderEncoded = envelope.optString("pub")
            val signature = envelope.optString("sig")
            if (!isValidNodeId(messageId) || !isValidNodeId(from) || !isValidNodeId(to) || from == to ||
                senderEncoded.length !in 64..512 || signature.length !in 40..512 ||
                envelope.optString("epk").length !in 64..512 || envelope.optString("salt").length !in 16..64 ||
                envelope.optString("iv").length !in 16..64 || envelope.optString("ct").length !in 22..220_000) return false
            if (deriveNodeIdFromEncodedPublicKey(senderEncoded) != from) return false
            val pinned = publicKeyFor(from)
            if (pinned != null && pinned != senderEncoded) return false
            val senderPublic = decodePublicKey(senderEncoded) ?: return false
            verify(senderPublic, envelopeCanonical(envelope), signature)
        } catch (_: Exception) { false }
    }

    /** Validate the previously pinned sender identity and AES-GCM tag before exposing plaintext. */
    @Synchronized
    fun open(envelope: JSONObject): OpenedMessage? {
        return try {
            if (envelope.optInt("v") != 2) return null
            val messageId = envelope.optString("id")
            val from = envelope.optString("from")
            val to = envelope.optString("to")
            val timestamp = envelope.optLong("ts", -1L)
            val now = System.currentTimeMillis()
            if (timestamp <= 0L || timestamp > now + MAX_FUTURE_SKEW_MS || now - timestamp > MAX_ENVELOPE_AGE_MS) return null
            val senderEncoded = envelope.optString("pub")
            val signature = envelope.optString("sig")
            if (!isValidNodeId(messageId) || !isValidNodeId(from) || !isValidNodeId(to) || to != localNodeId || from == localNodeId ||
                senderEncoded.length !in 64..512 || signature.length !in 40..512) return null
            if (deriveNodeIdFromEncodedPublicKey(senderEncoded) != from) return null
            val senderPublic = decodePublicKey(senderEncoded) ?: return null
            val pinned = publicKeyFor(from) ?: return null
            if (pinned != senderEncoded) return null
            if (!verify(senderPublic, envelopeCanonical(envelope), signature)) return null
            val ephemeralEncoded = envelope.getString("epk")
            if (ephemeralEncoded.length !in 64..512 || envelope.optString("salt").length !in 16..64 ||
                envelope.optString("iv").length !in 16..64 || envelope.optString("ct").length !in 22..220_000) return null
            val ephemeralPublic = decodePublicKey(ephemeralEncoded) ?: return null
            val salt = unb64(envelope.getString("salt")) ?: return null
            val iv = unb64(envelope.getString("iv")) ?: return null
            val ciphertext = unb64(envelope.getString("ct")) ?: return null
            if (iv.size != 12 || salt.size != 16 || ciphertext.size < 16 || ciphertext.size > MAX_CIPHERTEXT_BYTES) return null
            val shared = deriveShared(identityPair.private, ephemeralPublic)
            val keyBytes = hkdf(shared, salt, "MortaMesh-E2E-v2|$from|$to|$messageId".toByteArray(Charsets.UTF_8), 32)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(from, to, messageId, timestamp, envelope.getString("epk"), envelope.getString("salt")))
            val plain = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
            OpenedMessage(from, messageId, plain)
        } catch (_: Exception) { null }
    }

    private fun worldPostCanonical(post: JSONObject): ByteArray {
        val fields = listOf(
            "MORTA-MESH-WORLD-POST-V1", post.optString("v"), post.optString("id"),
            post.optString("node"), post.optString("name"), post.optString("ts"),
            post.optString("text"), post.optString("pub")
        )
        val canonical = StringBuilder()
        fields.forEach { field ->
            canonical.append(field.toByteArray(Charsets.UTF_8).size).append(':').append(field)
        }
        return canonical.toString().toByteArray(Charsets.UTF_8)
    }

    private fun identityCanonical(record: JSONObject): ByteArray {
        return listOf("MORTA-MESH-IDENTITY-V2", record.optString("node"), record.optString("name"), record.optString("pub"))
            .joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    private fun envelopeCanonical(envelope: JSONObject): ByteArray {
        return listOf(
            "MORTA-MESH-ENVELOPE-V2", envelope.optString("v"), envelope.optString("id"),
            envelope.optString("from"), envelope.optString("to"), envelope.optString("ts"), envelope.optString("epk"),
            envelope.optString("salt"), envelope.optString("iv"), envelope.optString("ct"), envelope.optString("pub")
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    private fun aad(from: String, to: String, id: String, timestamp: Long, epk: String, salt: String): ByteArray =
        listOf("MORTA-MESH-AAD-V2", from, to, id, timestamp.toString(), epk, salt).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun wifiHelloCanonical(from: String, to: String, senderNonce: String, recipientNonce: String): ByteArray =
        listOf("MORTA-MESH-WIFI-HELLO-V2", from, to, senderNonce, recipientNonce)
            .joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun isValidNonce(value: String): Boolean = try {
        val uuid = UUID.fromString(value)
        uuid.toString() == value && uuid.version() == 4
    } catch (_: Exception) { false }

    private fun sign(bytes: ByteArray): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(identityPair.private, random)
        signature.update(bytes)
        return b64(signature.sign())
    }

    private fun verify(publicKey: java.security.PublicKey, bytes: ByteArray, encodedSignature: String): Boolean {
        val decoded = unb64(encodedSignature) ?: return false
        return try {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(bytes)
            signature.verify(decoded)
        } catch (_: Exception) { false }
    }

    private fun decodePublicKey(encoded: String): java.security.PublicKey? {
        return try {
            val bytes = unb64(encoded) ?: return null
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        } catch (_: Exception) { null }
    }

    private fun deriveNodeIdFromEncodedPublicKey(encoded: String): String? {
        val encodedBytes = unb64(encoded) ?: return null
        if (encodedBytes.size !in 40..512) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(encodedBytes)
            val uuidBytes = digest.copyOfRange(0, 16)
            // RFC 9562 UUIDv8 custom layout: hash-derived ID with version/variant bits set.
            uuidBytes[6] = ((uuidBytes[6].toInt() and 0x0F) or 0x80).toByte()
            uuidBytes[8] = ((uuidBytes[8].toInt() and 0x3F) or 0x80).toByte()
            val buffer = ByteBuffer.wrap(uuidBytes)
            UUID(buffer.long, buffer.long).toString()
        } catch (_: Exception) { null }
    }

    private fun deriveShared(privateKey: java.security.PrivateKey, publicKey: java.security.PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputBytes: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = extract.doFinal(ikm)
        val out = ByteArray(outputBytes)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < outputBytes) {
            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(prk, "HmacSHA256"))
            expand.update(previous)
            expand.update(info)
            expand.update(counter.toByte())
            previous = expand.doFinal()
            val count = minOf(previous.size, outputBytes - offset)
            System.arraycopy(previous, 0, out, offset, count)
            offset += count
            counter++
        }
        return out
    }

    private fun newEcKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), random)
    }.generateKeyPair()

    private fun loadOrCreateIdentity(): KeyPair {
        if (identityFile.exists()) {
            return try {
                val bytes = identityFile.readBytes()
                if (bytes.size <= FILE_HEADER.size + 12 + 16 || !bytes.copyOfRange(0, FILE_HEADER.size).contentEquals(FILE_HEADER)) {
                    throw IllegalStateException("Stored identity file has an invalid header")
                }
                val iv = bytes.copyOfRange(FILE_HEADER.size, FILE_HEADER.size + 12)
                val ciphertext = bytes.copyOfRange(FILE_HEADER.size + 12, bytes.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getExistingWrappingKey(), GCMParameterSpec(128, iv))
                val identityJson = JSONObject(cipher.doFinal(ciphertext).toString(Charsets.UTF_8))
                val privateEncoded = unb64(identityJson.getString("private"))
                    ?: throw IllegalStateException("Stored private key encoding is invalid")
                val publicEncoded = unb64(identityJson.getString("public"))
                    ?: throw IllegalStateException("Stored public key encoding is invalid")
                val factory = KeyFactory.getInstance("EC")
                val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateEncoded))
                val publicKey = factory.generatePublic(X509EncodedKeySpec(publicEncoded))
                KeyPair(publicKey, privateKey)
            } catch (e: Exception) {
                // Do not silently replace an existing identity: doing so would create a new key
                // for the same node ID and make pinned contacts see an unexpected key change.
                throw IllegalStateException("Morta Mesh cannot unlock its saved identity. Do not delete or reset identity data; restore the device/key state first.", e)
            }
        }
        val keyPair = newEcKeyPair()
        saveIdentity(keyPair)
        return keyPair
    }

    private fun saveIdentity(keyPair: KeyPair) {
        try {
            val data = JSONObject()
                .put("private", b64(keyPair.private.encoded))
                .put("public", b64(keyPair.public.encoded))
                .toString().toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // Android Keystore keys configured with randomizedEncryptionRequired=true must
            // generate the GCM IV themselves. Supplying our own IV makes first launch fail.
            cipher.init(Cipher.ENCRYPT_MODE, getWrappingKey())
            val iv = cipher.iv ?: throw IllegalStateException("Keystore did not return a GCM IV")
            check(iv.size == 12) { "Unexpected GCM IV size" }
            val encrypted = cipher.doFinal(data)
            val tempFile = File(identityFile.absolutePath + ".tmp")
            try {
                FileOutputStream(tempFile).use { output ->
                    output.write(FILE_HEADER)
                    output.write(iv)
                    output.write(encrypted)
                    output.fd.sync()
                }
                // Atomic replacement keeps a prior valid identity if the process dies mid-write.
                Os.rename(tempFile.absolutePath, identityFile.absolutePath)
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        } catch (e: Exception) {
            throw IllegalStateException("Could not persist the local identity safely", e)
        }
    }

    private fun getExistingWrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(wrapKeyAlias, null) as? SecretKey
            ?: throw IllegalStateException("Android Keystore identity wrapping key is missing")
    }

    private fun getWrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(wrapKeyAlias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                wrapKeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun isValidNodeId(value: String): Boolean = try {
        java.util.UUID.fromString(value).toString() == value
    } catch (_: Exception) { false }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun unb64(value: String): ByteArray? = try { Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP) } catch (_: Exception) { null }

    companion object {
        private const val PINNED_KEY_PREFIX = "mesh_pinned_key_"
        private const val PINNED_NAME_PREFIX = "mesh_pinned_name_"
        private const val MAX_CIPHERTEXT_BYTES = 160 * 1024
        private const val MAX_ENVELOPE_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_FUTURE_SKEW_MS = 10L * 60 * 1000
        private val FILE_HEADER = byteArrayOf(0x4D, 0x4D, 0x49, 0x44, 0x31) // MMID1
    }
}
