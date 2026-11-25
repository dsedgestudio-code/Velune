package com.nikhil.yt.together

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The one code people share for a Listen Together session, used for both LAN and online.
 * 8 characters of Crockford base32 (no I, L, O, U): easy to read aloud, ~40 bits of entropy.
 * A new code is made for every session.
 */
object TogetherCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 8
    private val random = SecureRandom()

    fun generate(): String = buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** Accepts what people actually type: lower case, dashes, spaces, and O/I/L in place of 0/1. */
    fun normalize(raw: String): String? {
        val cleaned = raw.uppercase()
            .filter { it.isLetterOrDigit() }
            .map { c -> when (c) { 'O' -> '0'; 'I', 'L' -> '1'; else -> c } }
            .joinToString("")
        return cleaned.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }

    fun display(code: String): String = "${code.take(4)}-${code.drop(4)}"

    fun link(code: String): String = "spectra://together?code=$code"

    /** A code typed or pasted, or a spectra:// / velune:// join link carrying one. */
    fun parse(raw: String): String? {
        val trimmed = raw.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        if (uri?.scheme != null && isJoinLink(uri.scheme, uri.host ?: uri.authority)) {
            val code = uri.rawQuery.orEmpty().split("&")
                .map { it.split("=", limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == "code" }
                ?.let { URLDecoder.decode(it[1], "UTF-8") }
            return code?.let(::normalize)
        }
        return normalize(trimmed)
    }

    fun isJoinLink(scheme: String?, host: String?): Boolean =
        (scheme.equals("spectra", true) || scheme.equals("velune", true)) && host.equals("together", true)
}

/**
 * Everything derived from a session code. One slow PBKDF2 run (do it off the main thread) yields:
 * - [roomId]: the relay topic id, so the relay never sees the code;
 * - [lanKey]: the session key LAN guests present in their hello;
 * - an AES-256-GCM key that seals every relay message.
 *
 * shortcut: 40-bit codes + 20k PBKDF2 rounds cost an eavesdropper on the public relay weeks of GPU time
 * per session; fine for a code that dies with the session. Raise the code length if sessions ever persist.
 */
class TogetherSecret private constructor(
    val roomId: String,
    val lanKey: String,
    private val key: SecretKeySpec,
) {
    fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    /** Null when the bytes were not sealed with this session's key (wrong room, tampering, noise). */
    fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size <= IV_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, IV_BYTES))
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }.getOrNull()
    }

    companion object {
        private const val IV_BYTES = 12
        private const val ROUNDS = 20_000
        private val random = SecureRandom()

        fun fromCode(code: String): TogetherSecret {
            val spec = PBEKeySpec(code.toCharArray(), "spectra-together-v1".toByteArray(), ROUNDS, 64 * 8)
            val master = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return TogetherSecret(
                roomId = master.copyOfRange(32, 48).toHex(),
                lanKey = master.copyOfRange(48, 64).toHex(),
                key = SecretKeySpec(master.copyOfRange(0, 32), "AES"),
            )
        }

        /** A private inbox for one install, keyed by its client id (only shared with people you listened with). */
        fun inbox(clientId: String): TogetherSecret {
            val digest = MessageDigest.getInstance("SHA-256").digest("spectra-inbox|$clientId".toByteArray())
            val topic = MessageDigest.getInstance("SHA-256").digest("spectra-inbox-topic|$clientId".toByteArray())
            return TogetherSecret(roomId = topic.copyOfRange(0, 16).toHex(), lanKey = "", key = SecretKeySpec(digest, "AES"))
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
