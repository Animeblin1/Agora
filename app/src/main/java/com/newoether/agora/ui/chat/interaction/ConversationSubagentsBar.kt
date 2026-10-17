package com.newoether.agora.ui.chat.interaction

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.SubagentSummary
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy

/** Navigation only: conversations and the ordinary runtime own the capsule's content/status. */
@Composable
internal fun ConversationSubagentsBar(
    conversationId: String?,
    agents: List<SubagentSummary>,
    onSelect: (String) -> Unit,
    onHeightChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val desired = (conversationId to agents).takeIf { conversationId != null && agents.isNotEmpty() }
    var shown by remember { mutableStateOf(desired) }
    val progress = remember { Animatable(0f) }
    val motion = LocalAgoraMotionPolicy.current
    val latestDesired by rememberUpdatedState(desired)
    val latestHeightChanged by rememberUpdatedState(onHeightChanged)
    var measuredHeight by remember { mutableFloatStateOf(0f) }
    val active = desired != null && shown?.first == conversationId
    val displayedAgents = if (active) agents else shown?.second.orEmpty()
    LaunchedEffect(conversationId, desired != null) {
        if (shown != null && !active) {
            progress.animateTo(0f, tween(180))
            shown = null
        }
        latestDesired?.let { target ->
            shown = target
            progress.animateTo(1f, tween(180))
        }
    }
    SideEffect { if (active) shown = desired }
    LaunchedEffect(motion.allowSpatialTransitions, active) {
        snapshotFlow {
            if (shown == null) 0f else measuredHeight *
                if (motion.allowSpatialTransitions) progress.value else if (active) 1f else 0f
        }.collect { latestHeightChanged(it) }
    }
    DisposableEffect(Unit) { onDispose { latestHeightChanged(0f) } }
    if (shown == null) return
    LazyRow(
        modifier = modifier.fillMaxWidth().onSizeChanged { measuredHeight = it.height.toFloat() }
            .padding(bottom = 8.dp).graphicsLayer {
                alpha = progress.value
                val scale = if (motion.allowSpatialTransitions) 0.96f + 0.04f * progress.value else 1f
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 1f)
            },
        userScrollEnabled = active,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        items(displayedAgents, key = { it.id }) { agent ->
            val selected = agent.id == shown?.first
            Surface(
                onClick = { if (active) onSelect(agent.id) }, enabled = active,
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
