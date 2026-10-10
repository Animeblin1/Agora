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
 * answers 401 `AuthError`. The cost therefore comes from the OpenCode model metadata, which is the
 * same source the reference gateways use.
 *
 * A model is free when its metadata input and output cost are both zero. The id is not consulted:
 * `big-pickle` costs nothing yet carries no hint in its name, while a `-free` suffix is only a naming
 * habit and is not what makes a model reachable.
 *
 * The metadata is fetched once per process and cached in memory only. A model the metadata does not
 * cover is dropped rather than assumed free, because offering a paid id here produces a 401 the first
 * time it is used.
 */
internal object OpenCodeZenFreeModels {
    private const val METADATA_URL = "https://models.dev/api.json"
    private const val METADATA_TIMEOUT_MILLIS = 30_000L
    private const val PROVIDER_KEY = "opencode"
    private const val TAG = "AgoraAPI"

    private val lock = Mutex()

    /** Zero-cost ids known from the last successful metadata fetch; null until one lands. */
    @Volatile
    private var freeIds: Set<String>? = null

    /**
     * Whether [modelId] may be offered anonymously.
     *
     * Called from model discovery, so it runs off the main thread and never blocks the UI: a metadata
     * fetch happens at most once and only on the first call that needs it.
     */
    suspend fun isFree(modelId: String): Boolean {
        val known = freeIds ?: refresh() ?: return false
        return modelId in known
    }

    private suspend fun refresh(): Set<String>? = withContext(Dispatchers.IO) {
        lock.withLock {
            freeIds?.let { return@withLock it }
            val fetched = runCatching { fetch() }
                .onFailure {
                    DebugLog.w(TAG, "Zen: model metadata unavailable; no model offered as free")
                }
                .getOrNull()
            if (fetched != null) freeIds = fetched
            fetched
        }
    }

    private fun fetch(): Set<String> {
        val body = HttpClient.fetchModels(METADATA_URL, callTimeoutMillis = METADATA_TIMEOUT_MILLIS)
            ?: return emptySet()
        return parse(body)
    }

    /**
     * Reads the `opencode` provider section and keeps the ids priced at zero.
     *
     * The document is several megabytes and lists hundreds of vendors, so it is walked as a tree
     * instead of being mapped onto a generated model: only this one section and the `cost` object of
     * each entry are touched.
     */
    internal fun parse(body: String): Set<String> {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }
            .getOrNull() ?: return emptySet()
        val models = root[PROVIDER_KEY]?.jsonObject?.get("models")?.jsonObject
            ?: return emptySet()

        val result = LinkedHashSet<String>()
        for ((id, entry) in models) {
            if (entry !is JsonObject) continue
            val cost = entry["cost"]?.jsonObject ?: continue
            val input = cost["input"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
            val output = cost["output"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
            if (input == 0.0 && output == 0.0) result += id
        }
        return result
    }
}
