package fr.yannickuhrig.vyzo.data.cloud

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement des données du compte, fait sur l'appareil : AES-256-GCM, et PBKDF2-HMAC-SHA256 pour tirer une clé du mot de passe.
 * PBKDF2 est écrit ici car Android ne propose PBKDF2WithHmacSHA256 qu'à partir de l'API 26 (l'appli démarre à l'API 23).
 */
object VaultCrypto {
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val ITERATIONS = 600_000
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val PREFIX = "v1."
    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(salt)
        val u = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        repeat(iterations - 1) {
            mac.update(u)
            mac.doFinal(u, 0)
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        return out
    }

    /** [context] lie le bloc à son emplacement : déplacé sur une autre ligne du serveur, il ne s'ouvre plus. */
    fun seal(key: ByteArray, plain: ByteArray, context: String): String {
        val iv = randomBytes(IV_BYTES)
        val cipher = cipher(Cipher.ENCRYPT_MODE, key, iv, context)
        return PREFIX + (iv + cipher.doFinal(plain)).toByteString().base64()
    }

    /** @throws GeneralSecurityException si la clé est mauvaise ou si le bloc a été modifié. */
    fun open(key: ByteArray, sealed: String, context: String): ByteArray {
        val raw = sealed.removePrefix(PREFIX).decodeBase64()?.toByteArray()
        if (!sealed.startsWith(PREFIX) || raw == null || raw.size <= IV_BYTES) throw GeneralSecurityException("Bloc chiffré invalide")
        val cipher = cipher(Cipher.DECRYPT_MODE, key, raw.copyOfRange(0, IV_BYTES), context)
        return cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toByteString().hex()

    /** Empreinte publique d'une clé : deux appareils voient s'ils ont la même, sans la révéler. */
    fun keyId(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest("vyzo-key-id".toByteArray() + key).toByteString().hex().take(16)

    fun encode(bytes: ByteArray): String = bytes.toByteString().base64()

    fun decode(text: String): ByteArray? = text.decodeBase64()?.toByteArray()

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray, context: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(context.toByteArray(Charsets.UTF_8))
        }
}
