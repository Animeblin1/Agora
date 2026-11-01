package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.api.openai.OpenCodeZenBridge
import com.newoether.agora.api.openai.OpenCodeZenCoreRelease
import com.newoether.agora.api.openai.OpenCodeZenProvider
import kotlinx.coroutines.launch

/**
 * Status and one-tap update for the OpenCode CLI identity the Zen free lane runs with, and the
 * embedded-bridge toggle that makes the lane actually work.
 *
 * The lane authenticates the User-Agent shape, not the number, so "updating the core" means
 * tracking the version the reference gateway (opencode2api) currently claims: checking reads it
 * from that repo, and applying stores it in settings, where [OpenCodeZenProvider] picks it up
 * for every request until the built-in pin is restored or overwritten again.
 *
 * The bridge toggle routes free-lane traffic through the bundled gateway
 * ([OpenCodeZenBridge]): the direct lane answers 429 to every transport the app can produce,
 * while the gateway reproduces the official client's transport and streams 200.
 */
@Composable
fun OpenCodeZenCoreCard(
    override: String?,
    bridgeEnabled: Boolean,
    onApply: (String) -> Unit,
    onBridgeEnabled: (Boolean) -> Unit,
) {
    val applied = override?.takeIf(String::isNotBlank) ?: OpenCodeZenProvider.OPENCODE_CLI_VERSION
    var checking by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var bridgeRunning by remember { mutableStateOf(OpenCodeZenBridge.isRunning) }
    var gatewayVersion by remember { mutableStateOf<String?>(null) }
    var updatedTo by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Resume the bridge silently when the page is opened with it left enabled.
    LaunchedEffect(bridgeEnabled) {
        if (bridgeEnabled && !bridgeRunning) {
            bridgeRunning = OpenCodeZenBridge.start()
        }
        gatewayVersion = OpenCodeZenBridge.currentVersion(context)
    }

    fun check() {
        if (checking) return
        checking = true
        error = false
        scope.launch {
            latest = OpenCodeZenCoreRelease.fetchLatestVersion()
            checking = false
            error = latest == null
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.zen_core_applied, applied),
            style = MaterialTheme.typography.bodyMedium,
        )
        when {
            checking -> Text(
                stringResource(R.string.zen_core_checking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error -> Text(
                stringResource(R.string.zen_core_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            latest != null -> Surface(shape = MaterialTheme.shapes.small) {
                Text(
                    text = if (latest == applied) {
                        stringResource(R.string.zen_core_up_to_date, latest!!)
                    } else {
                        stringResource(R.string.zen_core_available, latest!!)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(6.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                checking -> {}
                latest != null && latest != applied -> TextButton(onClick = { onApply(latest!!) }) {
                    Text(stringResource(R.string.zen_core_apply, latest!!))
                }
                else -> TextButton(onClick = ::check) {
                    Text(stringResource(R.string.zen_core_check))
                }
            }
        }
        Text(
            text = stringResource(R.string.zen_bridge_title),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            ),
        )
        Text(
            text = stringResource(R.string.zen_bridge_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = bridgeEnabled,
                onCheckedChange = { enabled ->
                    onBridgeEnabled(enabled)
                    if (enabled) {
                        scope.launch { bridgeRunning = OpenCodeZenBridge.start() }
                    } else {
                        OpenCodeZenBridge.stop()
                        bridgeRunning = false
                    }
                },
            )
            Text(
                text = stringResource(
                    if (bridgeRunning) R.string.zen_bridge_running else R.string.zen_bridge_stopped,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = gatewayVersion?.let {
                    stringResource(R.string.zen_bridge_version, it)
                } ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (updating) {
                Text(
                    text = stringResource(R.string.zen_core_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            } else {
                TextButton(onClick = {
                    updating = true
                    scope.launch {
                        val latest = OpenCodeZenBridge.latestRelease()
                        if (latest != null && latest.first != gatewayVersion) {
                            val installed = OpenCodeZenBridge.update(latest.first, latest.second, latest.third)
                            if (installed != null) {
                                updatedTo = installed
                                bridgeRunning = OpenCodeZenBridge.isRunning
                                gatewayVersion = installed
                            }
                        }
                        updating = false
                    }
                }) {
                    Text(stringResource(R.string.zen_bridge_recheck))
                }
            }
        }
        updatedTo?.let {
            Text(
                text = stringResource(R.string.zen_core_updated, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
