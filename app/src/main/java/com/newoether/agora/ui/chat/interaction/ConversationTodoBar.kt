package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import com.newoether.agora.viewmodel.ChatViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.newoether.agora.R
import com.newoether.agora.model.ConversationTodoItem
import com.newoether.agora.ui.components.SmoothBottomSheet
import com.newoether.agora.ui.theme.ChatType

/** Keeps both selected-owner tools above the composer without adding another scroll owner. */
@Composable
internal fun BoxScope.ChatConversationToolsOverlay(
    viewModel: ChatViewModel,
    conversationId: String?,
    autoWrapCodeBlocks: Boolean,
    bottomBarHeight: Dp,
    onHeightChanged: (Float) -> Unit,
) {
    val todo by viewModel.currentConversationTodo.collectAsState()
    val items = todo.second.takeIf { todo.first == conversationId }.orEmpty()
    var todoHeight by remember(conversationId) { mutableStateOf(0f) }
    var interactionHeight by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    SideEffect { onHeightChanged(todoHeight + interactionHeight) }
    ChatUserInteractionBar(
        viewModel, conversationId, autoWrapCodeBlocks,
        bottomBarHeight + with(density) { todoHeight.toDp() },
        onHeightChanged = { interactionHeight = it },
    )
    ConversationTodoBar(
        conversationId, items, onHeightChanged = { todoHeight = it },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBarHeight),
    )
}
/** Read-only progress; changing the list remains an ordinary model tool operation. */
@Composable
internal fun ConversationTodoBar(
    conversationId: String?,
    items: List<ConversationTodoItem>,
    onHeightChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var opened by remember(conversationId) { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { onHeightChanged(0f) } }
    if (items.isEmpty()) {
        SideEffect {
            opened = false
            onHeightChanged(0f)
        }
        return
    }
    val title = stringResource(R.string.todo_title)
    val completed = items.count { it.status == "completed" }
    val progress = stringResource(R.string.todo_progress, completed, items.size)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .onSizeChanged { onHeightChanged(it.height.toFloat()) }
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            onClick = { opened = true }, shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 840.dp).height(48.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Checklist, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(progress, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (opened) {
        val list = rememberLazyListState()
        SmoothBottomSheet(
            onDismissRequest = { opened = false },
            contentAtTop = { !list.canScrollBackward },
            header = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title, style = ChatType.detailTitle, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        progress, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
            },
        ) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items) { _, item ->
                    val status = when (item.status) {
                        "completed" -> R.string.todo_completed
                        "in_progress" -> R.string.todo_in_progress
                        else -> R.string.todo_pending
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            when (item.status) {
                                "completed" -> Icons.Default.CheckCircle
                                "in_progress" -> Icons.Default.Schedule
                                else -> Icons.Default.RadioButtonUnchecked
                            }, stringResource(status), Modifier.size(20.dp),
                            tint = if (item.status == "in_progress") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer {
                            Text(item.content, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
