package com.newoether.agora.api.openai

import com.newoether.agora.api.HttpClient
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which Zen catalog ids the anonymous credential can actually serve.
 *
 * `/v1/models` publishes an id and nothing else — no tier, no price — so a paid id sitting next to
 * `big-pickle` in that list is indistinguishable from a free one until it is used, at which point it
 * answers 401 `AuthError`. The verdict therefore comes from the OpenCode model metadata, the same
 * source the reference gateways use:
 *
 *  1. GET `{zen}/v1/models` — the live catalog, free and paid ids alike;
 *  2. GET `models.dev/api.json` — pricing metadata, which turns an id into a verdict;
 *  3. [VERIFIED_FREE_MODELS] — a compile-time bootstrap list, only while 1 and 2 cannot answer.
 *
 * A model is free when its metadata input and output cost are both zero. The id is not consulted:
 * `big-pickle` costs nothing yet carries no hint in its name, while a `-free` suffix is only a naming
 * habit and is not what makes a model reachable. A model the metadata marks deprecated is dropped
 * even at zero cost, because deprecation is the one verdict the lane honours before price — that is
 * what keeps `grok-code`, priced at zero yet refused as unsupported, out of the list.
 *
 * The metadata is fetched once per process and cached in memory only. When it cannot be fetched at
 * all, [VERIFIED_FREE_MODELS] is offered instead of an empty catalog, so a dead metadata host costs
 * breadth rather than the whole provider.
 */
internal object OpenCodeZenFreeModels {
    private const val METADATA_URL = "https://models.dev/api.json"
    private const val METADATA_TIMEOUT_MILLIS = 30_000L
    private const val PROVIDER_KEY = "opencode"
    private const val TAG = "AgoraAPI"

    /**
     * Ids verified against the anonymous lane with a real chat.
     *
     * A floor for a cold or broken network, and the one thing that outranks a deprecation verdict:
     * these ids are confirmed by talking to the lane itself, and the metadata is known to flag models
     * that still answer — `muse-spark-1.2-contributor-free` is marked deprecated upstream yet is not
     * refused. A verdict of *paid* is never overridden, because that one the lane does enforce.
     */
    private val VERIFIED_FREE_MODELS: Set<String> = setOf(
        "big-pickle",
        "mimo-v2.6-flash-free",
        "ling-3.0-flash-fin-free",
        "nemotron-3.5-lightning-free",
        "nemotron-3-ultra-free",
        "muse-spark-1.2-contributor-free",
    )

    private val lock = Mutex()

    /** What the metadata says about one id. */
    internal enum class Verdict { FREE, PAID, DEPRECATED }

    /** Verdicts from the last successful metadata fetch; null until one lands. */
    @Volatile
    private var verdicts: Map<String, Verdict>? = null

    /**
     * Whether [modelId] may be offered anonymously.
     *
     * Called from model discovery, so it runs off the main thread and never blocks the UI: a metadata
     * fetch happens at most once and only on the first call that needs it.
     */
    suspend fun isFree(modelId: String): Boolean {
        val known = verdicts ?: refresh() ?: return modelId in VERIFIED_FREE_MODELS
        return when (known[modelId]) {
            Verdict.FREE -> true
            // A verified id outranks deprecation, never a price.
            Verdict.DEPRECATED -> modelId in VERIFIED_FREE_MODELS
            Verdict.PAID -> false
            null -> modelId in VERIFIED_FREE_MODELS
        }
    }

    private suspend fun refresh(): Map<String, Verdict>? = withContext(Dispatchers.IO) {
        lock.withLock {
            verdicts?.let { return@withLock it }
            val fetched = runCatching { fetch() }
                .onFailure {
                    DebugLog.w(TAG, "Zen: model metadata unavailable; falling back to verified ids")
                }
                .getOrNull()
            if (fetched != null) verdicts = fetched
            fetched
        }
    }

    private fun fetch(): Map<String, Verdict> {
        val body = HttpClient.fetchModels(METADATA_URL, callTimeoutMillis = METADATA_TIMEOUT_MILLIS)
            ?: return emptyMap()
        return parse(body)
    }

    /**
     * Reads the `opencode` provider section and judges each id.
     *
     * The document is several megabytes and lists hundreds of vendors, so it is walked as a tree
     * instead of being mapped onto a generated model: only this one section and the price, status and
     * deprecation fields of each entry are touched.
     */
    internal fun parse(body: String): Map<String, Verdict> {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }
            .getOrNull() ?: return emptyMap()
        val models = root[PROVIDER_KEY]?.jsonObject?.get("models")?.jsonObject
            ?: return emptyMap()

        val result = LinkedHashMap<String, Verdict>()
        for ((id, entry) in models) {
            if (entry !is JsonObject) continue
            val cost = entry["cost"]?.jsonObject ?: continue
            val input = cost["input"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
            val output = cost["output"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
            result[id] = when {
                input != 0.0 || output != 0.0 -> Verdict.PAID
                isDeprecated(entry) -> Verdict.DEPRECATED
                else -> Verdict.FREE
            }
        }
        return result
    }

    /**
     * Deprecation is published under a shifting set of fields, so all of them are consulted.
     *
     * The upstream marks some models only with `status`, others with `deprecated_at`, and a few with
     * `retirement_date`; reading a single field is what lets a deprecated id slip through as free.
     */
    private fun isDeprecated(entry: JsonObject): Boolean {
        if (entry["deprecated"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true) {
            return true
        }
        val status = (entry["status"] ?: entry["lifecycle"])?.jsonPrimitive?.contentOrNull
            ?.lowercase()
        if (status == "deprecated" || status == "retired" || status == "disabled") return true
        return entry["deprecated_at"] != null || entry["retirement_date"] != null
    }

    /** The bootstrap roster, exposed so the catalog can be asserted without a network. */
    internal fun verifiedFallback(): Set<String> = VERIFIED_FREE_MODELS
}
