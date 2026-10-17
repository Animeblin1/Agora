package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.SubagentSummary

/** Navigation only: conversations and the ordinary runtime own the capsule's content/status. */
@Composable
internal fun ConversationSubagentsBar(
    conversationId: String?,
    agents: List<SubagentSummary>,
    onSelect: (String) -> Unit,
    onHeightChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(Unit) { onDispose { onHeightChanged(0f) } }
    if (conversationId == null || agents.isEmpty()) {
        SideEffect { onHeightChanged(0f) }
        return
    }
    LazyRow(
        modifier = modifier.fillMaxWidth().onSizeChanged { onHeightChanged(it.height.toFloat()) }
            .padding(bottom = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        items(agents, key = { it.id }) { agent ->
            val selected = agent.id == conversationId
            Surface(
                onClick = { onSelect(agent.id) },
                shape = RoundedCornerShape(24.dp),
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    Modifier.heightIn(min = 48.dp).widthIn(max = 240.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (agent.status == "running") {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else Icon(
                        when (agent.status) {
                            "completed" -> Icons.Default.CheckCircle
                            "failed" -> Icons.Default.ErrorOutline
                            "stopped" -> Icons.Default.PauseCircleOutline
                            else -> Icons.Default.SmartToy
                        },
                        contentDescription = stringResource(when (agent.status) {
                            "completed" -> R.string.subagent_completed
                            "failed" -> R.string.subagent_failed
                            "stopped" -> R.string.subagent_stopped
                            else -> R.string.subagent_idle
                        }),
                        modifier = Modifier.size(18.dp),
                    )
                    Text(agent.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
