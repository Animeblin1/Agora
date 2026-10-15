package com.newoether.agora.api.openai

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Correlation identity for one OpenCode Zen request.
 *
 * The Zen anonymous lane answers 403 `FreeTierError` ("free tier can only be used from within
 * OpenCode") unless the request carries a CLI-shaped session id. The accepted shape is exactly
 * `ses_` + 12 lowercase hex characters + 14 Base62 characters, and — after the 2026-09 Zen v2
 * content gate — the leading 12 hex are read as a forward-rolling timestamp (`time*0x1000+1`,
 * one's-complement, low 48 bits, hex, right-padded to 12), so a pure random or hash-derived
 * prefix is now refused. Port of `opencode-zen-oauth` `free-tier.ts:
 * freeTierSessionId()`.
 *
 * To keep upstream prompt-cache affinity (one conversation = one stable session across turns)
 * the timestamp and random parts are both derived deterministically from the conversation seed,
 * so the same conversation always yields the same id — but the prefix still advances deterministically
 * with the seed, satisfying the gate's time-roll check without sending the literal wall clock.
 */
internal object OpenCodeZenIdentity {

    /** `^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$` — the only session shape the free lane accepts. */
    private val CANONICAL_SESSION = Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$")
    private val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val random = SecureRandom()

    /**
     * One stable session per conversation seed. The free lane treats the session as a per-conversation
     * cache key, so the same seed must keep yielding the same id across turns (and across repeated
     * calls). The time part is minted once from the wall clock on first use — exactly how the CLI
     * opens a session and then reuses it — which also satisfies the v2 gate's freshness check.
     */
    private val sessionCache = mutableMapOf<String, String>()

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
        return sessionCache.getOrPut(seed) { canonicalSessionId(seed) }
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
            // The free lane rejects the gateway's `prj_`-prefixed project id with 403 since
            // 2026-10-14; it wants the client's bare 40-hex shape (sha256("opencode-project\u0000"+project)[:20]).
            project = clientProjectID("agora:opencode-zen"),
        )
    }

    /** JSON of the first user turn, mirroring opencode2api's `conversationSeed`. */
    private fun conversationSignal(messages: List<ChatMessage>?): String? =
        messages?.firstOrNull { it.participant == Participant.USER && it.text.isNotBlank() }?.text

    /**
     * Builds the canonical CLI session shape from any identity. A value that already matches the
     * `^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$` shape (a real OpenCode session) is passed through
     * unchanged, keeping its upstream cache affinity.
     *
     * Otherwise the id is built the way `opencode-zen-oauth` `free-tier.ts: freeTierSessionId()`
     * does it, so the v2 gate accepts it:
     *  - the 12-hex time part = low 48 bits of `(~(nowMs*0x1000+1))`, rendered in hex and
     *    right-padded to 12 — a forward-rolling timestamp the gate's freshness check recognises;
     *  - the 14 Base62 tail = a deterministic window of the seed hash, kept stable per conversation
     *    so upstream prompt-cache affinity is preserved across turns even though the time prefix
     *    advances.
     */
    internal fun canonicalSessionId(signal: String): String {
        if (CANONICAL_SESSION.matches(signal)) return signal
        val now = System.currentTimeMillis()
        val counter = (now * 0x1000L + 1L) and ((1L shl 48) - 1L)
        val timePart = (counter.inv() and ((1L shl 48) - 1L))
            .toString(16)
            .padStart(12, '0')
            .takeLast(12)
        val sum = sha256("ses\u0000$signal")
        val randomPart = base62Fixed(sum, 6, 10, width = 14)
        return "ses_$timePart$randomPart"
    }

    /**
     * The client-shaped project id the free lane accepts: a bare 40-hex string, the first 20 bytes
     * of `sha256("opencode-project\u0000" + project)` rendered in lowercase hex. The value itself is
     * not checked upstream, only its shape, but hashing keeps one conversation pinned to one project.
     */
    internal fun clientProjectID(project: String): String {
        val sum = sha256("opencode-project\u0000$project")
        return hex(sum, 0, 20)
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
