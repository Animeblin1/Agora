package com.newoether.agora.api.openai

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Correlation identity for one OpenCode Zen request.
 *
 * The Zen anonymous lane answers 403 `FreeTierError` ("free tier can only be used from within
 * OpenCode") unless the request carries a CLI-shaped session id. Since 2026-09-16 the accepted
 * shape is exactly `ses_` + 12 lowercase hex characters + 14 Base62 characters; anything else
 * (including the plain conversation id OpenCode Go sends) is refused. So the app's own session id
 * is hashed here into that shape instead of being forwarded verbatim.
 *
 * Port of opencode2api `internal/identity` and opencode2dsh `adapter/ids.ts`: a conversation
 * signal is hashed into the canonical shape, which keeps one conversation stable across turns
 * (and therefore keeps upstream prompt-cache affinity) without pretending to be a real CLI id.
 */
internal object OpenCodeZenIdentity {

    /** `^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$` — the only session shape the free lane accepts. */
    private val CANONICAL_SESSION = Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$")
    private val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val random = SecureRandom()

    /** One upstream request's correlation ids. */
    data class RequestIds(
        val session: String,
        val request: String,
        val project: String,
    )

    /**
     * The conversation signal. In opencode2api this is the JSON of the first user turn's content,
     * which stays stable as the history grows; the app's own conversation id is the same kind of
     * stable per-conversation value, so it is used directly when present.
     */
    fun sessionIdFor(conversationSeed: String?): String {
        val seed = conversationSeed?.trim().takeIf { !it.isNullOrEmpty() }
            ?: randomId("fallback", 16)
        return canonicalSessionId(seed)
    }

    /**
     * Derive the ids for one request. [messages] is only a last-resort signal: Agora always has a
     * conversation id by the time a request is dispatched, so the callers pass it explicitly.
     */
    fun derive(conversationSeed: String?, messages: List<ChatMessage>? = null): RequestIds {
        val session = sessionIdFor(conversationSeed ?: conversationSignal(messages))
        return RequestIds(
            session = session,
            request = randomId("req", 16),
            project = stableId("prj", "agora:opencode-zen"),
        )
    }

    /** JSON of the first user turn, mirroring opencode2api's `conversationSeed`. */
    private fun conversationSignal(messages: List<ChatMessage>?): String? =
        messages?.firstOrNull { it.participant == Participant.USER && it.text.isNotBlank() }?.text

    /**
     * Hashes any identity into the canonical CLI session shape. A value that already matches is
     * passed through unchanged, so a real OpenCode session keeps its upstream cache affinity.
     */
    internal fun canonicalSessionId(signal: String): String {
        if (CANONICAL_SESSION.matches(signal)) return signal
        val sum = sha256("ses\u0000$signal")
        val timePart = hex(sum, 0, 6)
        val randomPart = base62Fixed(sum, 6, 10, width = 14)
        return "ses_$timePart$randomPart"
    }

    internal fun stableId(prefix: String, value: String): String {
        val sum = sha256("$prefix\u0000$value")
        return "${prefix}_${hex(sum, 0, 12)}"
    }

    private fun randomId(prefix: String, size: Int): String {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return "${prefix}_${bytes.joinToString("") { "%02x".format(it) }}"
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun hex(bytes: ByteArray, from: Int, length: Int): String =
        bytes.copyOfRange(from, from + length).joinToString("") { "%02x".format(it) }

    /** Big-endian fixed-width Base62 of one byte window, matching ids.ts `base62Fixed`. */
    private fun base62Fixed(bytes: ByteArray, from: Int, length: Int, width: Int): String {
        var value = 0.toBigInteger()
        for (index in from until (from + length)) {
            value = value.shiftLeft(8) or (bytes[index].toInt() and 0xFF).toBigInteger()
        }
        val base = 62.toBigInteger()
        val out = CharArray(width)
        for (index in width - 1 downTo 0) {
            out[index] = BASE62[(value % base).toInt()]
            value /= base
        }
        return out.concatToString()
    }
}
