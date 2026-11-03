package com.mortalive.mortamesh

import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Builds the sender-to-ingestion encrypted package for the future Mortalive publishing API.
 *
 * Nearby relays only ever receive ciphertext and the signed manifest. The ingestion service
 * can unwrap the per-submission key, inspect the media in its restricted moderation boundary,
 * and (only after approval) re-encrypt approved content for the selected Mortalive group.
 *
 * This is deliberately not the Private Chat E2E protocol or a group-message encryption scheme.
 */
internal object AnonymousSubmissionCrypto {
    data class EncryptedPackage(
        val transferId: String,
        val manifest: JSONObject,
        val ciphertextFile: File
    )

    fun sealForIngestion(
        groupId: String,
        text: String,
        mimeType: String?,
        attachment: File?,
        ingestionPublicKeyX509Base64: String,
        ingestionKeyId: String,
        outputFile: File
    ): EncryptedPackage {
        val mime = mimeType?.let(AnonymousSubmissionPolicy::normalizeMime)
        val attachmentBytes = attachment?.length() ?: 0L
        val validation = AnonymousSubmissionPolicy.validate(groupId, text, mime, attachmentBytes)
        require(validation == null) { validation ?: "Invalid anonymous submission." }
        require(attachment == null || attachment.isFile && attachment.canRead()) { "Attachment is not readable." }
        require(ingestionKeyId.isNotBlank() && ingestionKeyId.length <= 128) { "A valid ingestion key ID is required." }

        val recipientKey = decodeRecipientKey(ingestionPublicKeyX509Base64)
        val transferId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val expiresAt = createdAt + AnonymousSubmissionPolicy.RETENTION_MS
        val dataKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val originKeyPair = newEcKeyPair()
        val wrapKeyPair = newEcKeyPair()
        val salt = ByteArray(32).also(SecureRandom()::nextBytes)
        val wrapIv = ByteArray(12).also(SecureRandom()::nextBytes)
        val wrapKey = deriveWrapKey(wrapKeyPair, recipientKey, salt, transferId, ingestionKeyId)
        val wrappedDek = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, wrapIv))
            cipher.updateAAD(wrapAad(transferId, ingestionKeyId))
            cipher.doFinal(dataKey)
        } finally {
            wrapKey.fill(0)
        }

        outputFile.parentFile?.mkdirs()
        val temporary = File(outputFile.parentFile, outputFile.name + ".tmp")
        try {
            val payloadCipher = Cipher.getInstance("AES/GCM/NoPadding")
            payloadCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"))
            val payloadIv = payloadCipher.iv
            payloadCipher.updateAAD(payloadAad(transferId, mime, createdAt))
            FileOutputStream(temporary).use { raw ->
                CipherOutputStream(raw, payloadCipher).use { encrypted ->
                    DataOutputStream(encrypted).use { payload ->
                        val textBytes = text.toByteArray(Charsets.UTF_8)
                        val groupBytes = groupId.toByteArray(Charsets.UTF_8)
                        payload.writeInt(groupBytes.size)
                        payload.write(groupBytes)
                        payload.writeInt(textBytes.size)
                        payload.write(textBytes)
                        payload.writeLong(attachmentBytes)
                        if (attachment != null) {
                            FileInputStream(attachment).use { input -> input.copyTo(payload, 32 * 1024) }
                        }
                    }
                }
            }
            check(temporary.renameTo(outputFile)) { "Could not finalize the encrypted package." }

            val manifest = JSONObject()
                .put("v", 1)
                .put("transferId", transferId)
                .put("destinationType", "mort-alive-group")
                .put("contentType", mime ?: "text/plain")
                .put("textCodePoints", text.codePointCount(0, text.length))
                .put("attachmentBytes", attachmentBytes)
                .put("plainBytes", text.toByteArray(Charsets.UTF_8).size + 8L + attachmentBytes)
                .put("createdAt", createdAt)
                .put("expiresAt", expiresAt)
                .put("ingestionKeyId", ingestionKeyId)
                .put("keyWrap", "P256-ECDH-HKDF-SHA256+A256GCM")
                .put("payloadCipher", "A256GCM")
                .put("wrapEphemeralPublicKey", b64(wrapKeyPair.public.encoded))
                .put("wrapSalt", b64(salt))
                .put("wrapIv", b64(wrapIv))
                .put("wrappedDek", b64(wrappedDek))
                .put("payloadIv", b64(payloadIv))
                .put("ciphertextBytes", outputFile.length())
                .put("ciphertextSha256", sha256(outputFile))
                .put("anonymousOriginPublicKey", b64(originKeyPair.public.encoded))
                .put("senderIdentity", "ephemeral-per-submission")
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(originKeyPair.private)
                update(canonicalManifest(manifest))
                b64(sign())
            }
            manifest.put("originSignature", signature)
            EncryptedPackage(transferId, manifest, outputFile)
        } catch (error: Exception) {
            temporary.delete()
            outputFile.delete()
            throw error
        } finally {
            dataKey.fill(0)
            salt.fill(0)
            wrapIv.fill(0)
        }
    }

    fun verifyManifest(manifest: JSONObject): Boolean = try {
        if (manifest.optInt("v") != 1) return false
        val transferId = manifest.optString("transferId")
        val contentType = manifest.optString("contentType")
        val expiresAt = manifest.optLong("expiresAt", -1L)
        if (!isCanonicalUuid(transferId) || manifest.optString("destinationType") != "mort-alive-group") return false
        if (contentType != "text/plain" && !AnonymousSubmissionPolicy.isAcceptedMime(contentType)) return false
        if (expiresAt <= System.currentTimeMillis() || expiresAt - manifest.optLong("createdAt", 0L) > AnonymousSubmissionPolicy.RETENTION_MS) return false
        if (manifest.optLong("ciphertextBytes", -1L) !in 16L..(AnonymousSubmissionPolicy.MAX_ATTACHMENT_BYTES + 1024L * 1024L)) return false
        if (!manifest.optString("ciphertextSha256").matches(Regex("^[a-f0-9]{64}$"))) return false
        if (manifest.optString("ingestionKeyId").isBlank()) return false
        val publicEncoded = manifest.getString("anonymousOriginPublicKey")
        val signatureEncoded = manifest.getString("originSignature")
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(publicEncoded)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(canonicalManifest(manifest))
            verify(unb64(signatureEncoded))
        }
    } catch (_: Exception) { false }

    private fun decodeRecipientKey(value: String): PublicKey {
        val encoded = runCatching { unb64(value.trim()) }.getOrElse {
            throw IllegalArgumentException("The configured Mortalive ingestion key is invalid.", it)
        }
        return try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        } catch (error: Exception) {
            throw IllegalArgumentException("The ingestion key must be an X.509 P-256 public key.", error)
        }
    }

    private fun deriveWrapKey(
        ephemeral: KeyPair,
        recipient: PublicKey,
        salt: ByteArray,
        transferId: String,
        keyId: String
    ): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(ephemeral.private)
        agreement.doPhase(recipient, true)
        val shared = agreement.generateSecret()
        return try {
            hkdf(shared, salt, "MortaMesh-Anonymous-Ingestion-v1|$transferId|$keyId".toByteArray(Charsets.UTF_8), 32)
        } finally {
            shared.fill(0)
        }
    }

    private fun hkdf(input: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = extract.doFinal(input)
        val output = ByteArray(size)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < size) {
            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(prk, "HmacSHA256"))
            expand.update(previous)
            expand.update(info)
            expand.update(counter.toByte())
            previous = expand.doFinal()
            val count = minOf(previous.size, size - offset)
            System.arraycopy(previous, 0, output, offset, count)
            offset += count
            counter++
        }
        prk.fill(0)
        previous.fill(0)
        return output
    }

    private fun canonicalManifest(manifest: JSONObject): ByteArray {
        val keys = manifest.keys().asSequence().filter { it != "originSignature" }.toList().sorted()
        val canonical = StringBuilder("MORTA-MESH-ANONYMOUS-SUBMISSION-V1\n")
        keys.forEach { key ->
            val value = manifest.get(key).toString()
            canonical.append(key.length).append(':').append(key)
                .append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value).append('\n')
        }
        return canonical.toString().toByteArray(Charsets.UTF_8)
    }

    private fun payloadAad(transferId: String, mime: String?, createdAt: Long): ByteArray =
        listOf("MORTA-MESH-ANON-PAYLOAD-V1", transferId, mime ?: "text/plain", createdAt.toString())
            .joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun wrapAad(transferId: String, keyId: String): ByteArray =
        listOf("MORTA-MESH-ANON-WRAP-V1", transferId, keyId).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun newEcKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
    }.generateKeyPair()

    private fun sha256(file: File): String {
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

    private fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: Exception) { false }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unb64(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
