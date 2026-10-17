package com.newoether.agora.tool
import com.newoether.agora.util.SHELL_FILE_EDIT_MAX_OPERATIONS
import com.newoether.agora.util.ShellFileEdit
import com.newoether.agora.util.ShellFileEditResult
import com.newoether.agora.util.ShellGrepResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
internal fun parseToolArgs(arguments: String): Map<String, JsonElement> {
    return try {
        val argsStr = arguments.ifBlank { "{}" }
        Json.decodeFromString<Map<String, JsonElement>>(argsStr)
    } catch (_: Exception) { emptyMap() }
}
internal fun jsonError(type: String, message: String, server: String? = null, command: String? = null): String {
    return buildJsonObject {
        if (type.isNotBlank()) put("type", type)
        put("error", "error"); put("message", message)
        if (server != null) put("server", server)
        if (command != null) put("command", command)
    }.toString()
}
internal fun arg(args: Map<String, JsonElement>, key: String): String {
    return (args[key] as? JsonPrimitive)?.content ?: ""
}
internal fun boolArg(args: Map<String, JsonElement>, key: String): Boolean =
    (args[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true

/** Successful file_edit / file_multi_edit JSON; both share type "file_edit" for one detail view. */
internal fun fileEditSuccessJson(server: String, path: String, result: ShellFileEditResult, editCount: Int): String =
    buildJsonObject {
        put("type", "file_edit")
        put("server", server)
        put("path", path)
        put("replaced", result.replacements)
        if (editCount > 1) put("edits", editCount)
        if (result.sha256.isNotBlank()) put("sha256", result.sha256)
        if (result.snippets.isNotEmpty()) {
            putJsonArray("snippets") {
                result.snippets.forEach { snippet ->
                    add(buildJsonObject {
                        put("start_line", snippet.startLine)
                        putJsonArray("lines") { snippet.lines.forEach { add(JsonPrimitive(it)) } }
                    })
                }
            }
        }
        if (result.snippetsTruncated) put("snippets_truncated", true)
    }.toString()

/** file_grep tool JSON; context arrays are omitted when empty. */
internal fun fileGrepJson(pattern: String, grep: ShellGrepResult, contextRequested: Boolean): String =
    buildJsonObject {
        put("type", "file_grep"); put("pattern", pattern)
        putJsonArray("matches") {
            grep.matches.forEach { m ->
                add(buildJsonObject {
                    put("path", m.path); put("line", m.line); put("content", m.content)
                    if (m.before.isNotEmpty()) {
                        putJsonArray("before") { m.before.forEach { add(JsonPrimitive(it)) } }
                    }
                    if (m.after.isNotEmpty()) {
                        putJsonArray("after") { m.after.forEach { add(JsonPrimitive(it)) } }
                    }
                })
            }
        }
        put("truncated", grep.truncated)
        if (grep.skippedLargeFiles > 0) put("skipped_large_files", grep.skippedLargeFiles)
        if (contextRequested && !grep.contextSupported) {
            put("context_supported", false)
            put("warning", "this Conch server ignores context; upgrade Conch for context lines")
        }
    }.toString()

/**
 * file_edit: exactly one replacement from old_string / new_string / replace_all. `edits` is not
 * part of this tool; a batch sent here is rejected with a pointer to file_multi_edit instead of
 * being half-applied.
 */
internal fun parseFileEditArgs(args: Map<String, JsonElement>): Result<List<ShellFileEdit>> = runCatching {
    require(editsArray(args) == null) { "file_edit takes a single replacement; use file_multi_edit for edits" }
    val oldString = arg(args, "old_string")
    require(oldString.isNotEmpty()) { "old_string is required" }
    listOf(ShellFileEdit(oldString, arg(args, "new_string"), boolArg(args, "replace_all")))
}

/**
 * file_multi_edit: only the `edits` array (also accepted as a JSON-encoded string, which some models
 * send). Top-level old_string / new_string / replace_all are not part of this tool's schema and are
 * ignored, so placeholder values a model adds there can never conflict with the batch.
 */
internal fun parseFileMultiEditArgs(args: Map<String, JsonElement>): Result<List<ShellFileEdit>> = runCatching {
    val edits = editsArray(args) ?: throw IllegalArgumentException("edits is required")
    require(edits.isNotEmpty()) { "edits must not be empty" }
    require(edits.size <= SHELL_FILE_EDIT_MAX_OPERATIONS) {
        "edits exceeds $SHELL_FILE_EDIT_MAX_OPERATIONS operations"
    }
    edits.mapIndexed { index, element ->
        val item = element as? JsonObject
            ?: throw IllegalArgumentException("edits[$index] must be an object")
        val oldString = (item["old_string"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        require(oldString.isNotEmpty()) { "edits[$index]: old_string is required" }
        val newString = (item["new_string"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("edits[$index]: new_string is required")
        ShellFileEdit(
            oldString = oldString,
            newString = newString,
            replaceAll = (item["replace_all"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true,
        )
    }
}

/** The `edits` argument as an array, or null when absent, null or a blank string. */
private fun editsArray(args: Map<String, JsonElement>): JsonArray? = when (val raw = args["edits"]) {
    null, JsonNull -> null
    is JsonArray -> raw
    is JsonPrimitive -> if (raw.isString && raw.content.isNotBlank()) {
        runCatching { Json.parseToJsonElement(raw.content) as? JsonArray }.getOrNull()
            ?: throw IllegalArgumentException("edits must be an array")
    } else {
        null
    }
    else -> throw IllegalArgumentException("edits must be an array")
}
