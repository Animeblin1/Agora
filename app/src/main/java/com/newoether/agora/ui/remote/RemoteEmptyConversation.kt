package com.newoether.agora.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.remote.RemoteState

/** Empty content reuses the ordinary chat's measured top/composer range without owning scroll or focus. */
@Composable
internal fun RemoteEmptyConversation(
    state: RemoteState,
    active: Boolean,
    topBarHeight: Dp,
    bottomBarHeight: Dp,
    onRetry: () -> Unit,
    opening: Boolean = false,
) {
    val hint = state.remoteEmptyConversationHint() ?: return
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(top = topBarHeight, bottom = bottomBarHeight),
    ) {
        Column(
            modifier = Modifier.align(if (opening) Alignment.TopCenter else Alignment.Center)
                // The original 48dp opening indicator stays centered. This passive hint starts
                // 16dp below its lower edge and cannot claim input from the original cover.
                .padding(top = if (opening) maxHeight / 2 + 24.dp + 16.dp else 0.dp)
                .widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 24.dp)
                .testTag("remote-empty-conversation"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!opening && state.canRetryEmptyConnection()) {
                TextButton(enabled = active, onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}
