package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.api.openai.OpenCodeZenCoreRelease
import com.newoether.agora.api.openai.OpenCodeZenProvider
import kotlinx.coroutines.launch

/**
 * Status and one-tap update for the OpenCode CLI identity the Zen free lane runs with.
 *
 * The lane authenticates the User-Agent shape, not the number, so "updating the core" means
 * tracking the version the reference gateway (opencode2api) currently claims: checking reads it
 * from that repo, and applying stores it in settings, where [OpenCodeZenProvider] picks it up
 * for every request until the built-in pin is restored or overwritten again.
 */
@Composable
fun OpenCodeZenCoreCard(
    override: String?,
    onApply: (String) -> Unit,
) {
    val applied = override?.takeIf(String::isNotBlank) ?: OpenCodeZenProvider.OPENCODE_CLI_VERSION
    var checking by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
    }
}
