package com.newoether.agora.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.api.decisions.DecisionsProvider
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.ui.settings.PillTabSwitcher

fun CustomEndpointProtocol.displayName(): String = when (this) {
    CustomEndpointProtocol.OPENAI -> "OpenAI"
    CustomEndpointProtocol.GOOGLE -> "Google"
    CustomEndpointProtocol.ANTHROPIC -> "Anthropic"
    CustomEndpointProtocol.DECISIONS -> "Jev-Compatible"
    CustomEndpointProtocol.UNKNOWN -> "Unsupported"
}

@Composable
fun CustomEndpointProtocolSelector(
    selected: CustomEndpointProtocol,
    onSelected: (CustomEndpointProtocol) -> Unit,
    modifier: Modifier = Modifier,
) {
    val protocols = CustomEndpointProtocol.selectable
    Column(modifier = modifier.fillMaxWidth()) {
        PillTabSwitcher(
            tabs = protocols.map(CustomEndpointProtocol::displayName),
            selectedIndex = protocols.indexOf(selected).coerceAtLeast(0),
            onSelect = { index -> protocols.getOrNull(index)?.let(onSelected) },
            modifier = Modifier.fillMaxWidth(),
            allowLabelOverflow = true,
        )
        if (selected == CustomEndpointProtocol.DECISIONS) {
            // The three hosts disagree on the path, so naming them saves a guess the user has no
            // way to make: none of them serves a model list that says which endpoint it is.
            Text(
                stringResource(
                    R.string.protocol_decisions_hosts,
                    DecisionsProvider.TYPE_SAFE_URL,
                    DecisionsProvider.INCEPTION_URL,
                    DecisionsProvider.OPENROUTER_URL,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
