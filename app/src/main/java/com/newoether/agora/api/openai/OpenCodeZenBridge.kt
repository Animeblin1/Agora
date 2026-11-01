package com.newoether.agora.api.openai

import android.content.Context
import android.os.Process
import com.newoether.agora.api.HttpClient
import com.newoether.agora.util.DebugLog
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Embedded free-lane transport: runs the bundled opencode2api gateway binary
 * (`libagora_oc2a.so`, an aarch64 Go executable shipped via jniLibs so the system
 * extracts it with the exec bit) on 127.0.0.1 and hands the Zen provider an
 * OpenAI-compatible endpoint.
 *
 * Why a bundled gateway: since the 2026-10 gate wave the anonymous chat lane
 * (`https://opencode.ai/inference/v1`) rejects every client whose transport does not
 * match the official OpenCode CLI — verified live: curl, Node and Bun all answer
 * 429 `FreeUsageLimitError` on fresh IPs with byte-correct headers and bodies, while
 * the real CLI (and this gateway, which reproduces its Bun TLS/framing) streams 200
 * from the same IP. The gateway also self-updates from the reference repo's releases
 * (sha256-verified, spawned from a memfd so Android's W^X does not apply).
 */
object OpenCodeZenBridge {
    private const val TAG = "AgoraAPI"
    private const val CONF_DIR = "zen-bridge"
    private const val BINARY = "libagora_oc2a.so"
    private const val PORT = 18421
    private val BASE_URL = "http://127.0.0.1:$PORT/v1"

    /** The gateway release the shipped jniLibs binary was built from. */
    const val BUNDLED_VERSION = "1.4.6"

    private val RELEASES_API = "https://api.github.com/repos/jasonxu114514/opencode2api/releases/latest"
    private val ASSET_URL_PREFIX = "https://github.com/jasonxu114514/opencode2api/releases/download/"
    private val JSON = Json { ignoreUnknownKeys = true }

    @Volatile private var appContext: Context? = null

    @Volatile private var process: java.lang.Process? = null

    /** Child pid when the gateway was spawned from a memfd (updated binary). */
    @Volatile private var childPid: Int = -1

    /** Live endpoint as (baseUrl, localToken); null while the bridge is not serving. */
    @Volatile private var endpoint: Pair<String, String>? = null

    /** Called once from AppContainer with the application context. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** The running bridge endpoint as (baseUrl, token), or null when it is not serving. */
    @JvmStatic
    fun activeEndpoint(): Pair<String, String>? = endpoint?.takeIf {
        process?.isAlive == true || childPid > 0
    }

    val isRunning: Boolean get() = activeEndpoint() != null

    /** The gateway version currently in use (updated release or the shipped one). */
    fun currentVersion(context: Context): String = versionFile(context)
        .takeIf { it.exists() && it.length() > 0 }?.readText()?.trim()?.removePrefix("v")
        ?.takeIf(String::isNotBlank) ?: BUNDLED_VERSION

    private fun versionFile(context: Context) = File(File(context.filesDir, CONF_DIR), "version.txt")

    /**
     * The newest released linux-arm64 gateway as (version, asset URL, sha256 URL),
     * or null when GitHub cannot be reached. Fails closed on any unknown asset shape.
     */
    suspend fun latestRelease(): Triple<String, String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val body = HttpClient.fetchModels(RELEASES_API, callTimeoutMillis = 20_000L)
                ?: return@runCatching null
            val root = Json.parseToJsonElement(body).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.content?.removePrefix("v") ?: return@runCatching null
            val assets = root["assets"]?.jsonArray ?: return@runCatching null
            var bin: String? = null
            var sha: String? = null
            for (asset in assets) {
                val a = asset.jsonObject
                val name = a["name"]?.jsonPrimitive?.content.orEmpty()
                val url = a["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                if (!url.startsWith(ASSET_URL_PREFIX)) continue
                if (name == "opencode2api_v${tag}_linux_arm64.tar.gz") bin = url
                if (name == "opencode2api_v${tag}_linux_arm64.tar.gz.sha256") sha = url
            }
            if (bin != null && sha != null) Triple(tag, bin, sha) else null
        }.getOrNull()
    }

    /**
     * Download the given release, verify its sha256, extract the gateway binary into
     * app files, and restart the bridge on it. Returns the installed version, or null
     * on any failure (the previously running config stays untouched until success).
     */
    suspend fun update(tag: String, assetUrl: String, shaUrl: String): String? =
        withContext(Dispatchers.IO) {
            val ctx = appContext ?: return@withContext null
            val dir = File(ctx.filesDir, CONF_DIR).apply { mkdirs() }
            runCatching {
                val bytes = download(assetUrl)
                val expected = download(shaUrl).decodeToString().trim().split(Regex("\\s+")).first()
                    .lowercase()
                val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                if (actual != expected) {
                    DebugLog.w(TAG, "Zen bridge update: sha256 mismatch ($actual != $expected)")
                    return@runCatching null
                }
                val elf = TarArchiveInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        if (!entry.isFile) continue
                        if (entry.name.substringAfterLast('/') != "opencode2api") continue
                        return@use tar.readBytes()
                    }
                    null
                } ?: return@runCatching null
                val outFile = File(dir, "gateway.bin")
                outFile.writeBytes(elf)
                versionFile(ctx).writeText(tag)
                DebugLog.w(TAG, "Zen bridge updated to $tag (${elf.size} bytes)")
                stop()
                start()
                tag
            }.getOrNull()
        }

    private fun download(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        conn.getInputStream().use { input -> return input.readBytes() }
    }

    /**
     * Start the bundled (or release-updated) gateway. Idempotent. Writes the config (a
     * fixed loopback port, one generated local token, anonymous free lane, no WebUI)
     * into app files on first run, spawns the binary and polls `/v1/models` until it
     * answers. When the network offers a newer release, updates first and then serves.
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext false
        activeEndpoint()?.let { return@withContext true }
        val dir = File(ctx.filesDir, CONF_DIR).apply { mkdirs() }
        val tokenFile = File(dir, "token.txt")
        val token = if (tokenFile.exists() && tokenFile.length() > 0) {
            tokenFile.readText().trim()
        } else {
            BigInteger(192, SecureRandom()).toString(32).also { tokenFile.writeText(it) }
        }
        val cfg = File(dir, "config.json")
        if (!cfg.exists() || cfg.length() == 0L) {
            // ponytail: fixed port 18421 (collision unlikely on a phone); switch to a
            // port-file handshake if that ever bites.
            cfg.writeText(
                """{"listen":"127.0.0.1:$PORT","server_keys":["$token"],"anonymous":true,""" +
                    """"webui":{"enabled":false}}""",
            )
        }

        // Auto-update: when a newer release exists, install it before spawning.
        latestRelease()?.let { (tag, assetUrl, shaUrl) ->
            if (isNewer(tag, currentVersion(ctx))) {
                update(tag, assetUrl, shaUrl)
            }
        }

        stop()
        val updated = File(dir, "gateway.bin")
        if (updated.exists() && updated.length() > 0) {
            // Updated binaries live in app files where exec is blocked; go through the
            // memfd spawner (see zenbridge_jni.cpp).
            childPid = runCatching {
                ZenBridgeNative.memfdSpawn(
                    updated.readBytes(),
                    arrayOf("opencode2api", "-config", cfg.absolutePath),
                    arrayOf("TMPDIR=${ctx.cacheDir.absolutePath}", "HOME=${dir.absolutePath}"),
                )
            }.getOrDefault(-1)
            if (childPid <= 0) {
                DebugLog.w(TAG, "Zen bridge: memfd spawn failed; falling back to bundled binary")
                childPid = -1
                versionFile(ctx).delete()
                updated.delete()
            }
        }
        if (childPid <= 0) {
            val bin = File(ctx.applicationInfo.nativeLibraryDir, BINARY)
            if (!bin.exists()) {
                DebugLog.w(TAG, "Zen bridge: bundled gateway binary missing")
                return@withContext false
            }
            process = try {
                ProcessBuilder(bin.absolutePath, "-config", cfg.absolutePath)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                DebugLog.w(TAG, "Zen bridge: spawn failed: ${e.message}")
                return@withContext false
            }
        }
        val client = URL("$BASE_URL/models")
        repeat(20) {
            if (!alive()) return@withContext false
            try {
                val conn = client.openConnection() as HttpURLConnection
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                conn.setRequestProperty("Authorization", "Bearer $token")
                if (conn.responseCode == 200) {
                    conn.disconnect()
                    endpoint = BASE_URL to token
                    DebugLog.w(TAG, "Zen bridge: serving on $BASE_URL")
                    return@withContext true
                }
                conn.disconnect()
            } catch (_: Exception) {
            }
            Thread.sleep(500)
        }
        DebugLog.w(TAG, "Zen bridge: gateway did not become healthy")
        stop()
        false
    }

    private fun alive(): Boolean = process?.isAlive == true || (childPid > 0 && aliveByPid(childPid))

    private fun aliveByPid(pid: Int): Boolean = java.io.File("/proc/$pid").exists()

    fun stop() {
        if (childPid > 0) {
            runCatching { Process.killProcess(childPid) }
            childPid = -1
        }
        runCatching { process?.destroy() }
        process = null
        endpoint = null
    }

    private fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
