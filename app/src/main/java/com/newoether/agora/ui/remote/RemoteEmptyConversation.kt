package com.newoether.agora.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.remote.RemoteState

/** The WebUI welcome hierarchy stays inside chat's existing top/composer range. */
@Composable
internal fun RemoteEmptyConversation(
    state: RemoteState,
    active: Boolean,
    topBarHeight: Dp,
    bottomBarHeight: Dp,
    onRetry: () -> Unit,
    opening: Boolean = false,
) {
    val headline = state.remoteEmptyConversationHint() ?: return
    val headlineText = stringResource(headline)
    val headlineStyle = if (opening) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.headlineMedium.copy(
        fontWeight = FontWeight.Bold, lineHeight = 35.sp,
    )
    val retryVisible = !opening && state.canRetryEmptyConnection()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(top = topBarHeight, bottom = bottomBarHeight),
    ) {
        val headlineHeight = with(density) {
            textMeasurer.measure(
                text = headlineText,
                style = headlineStyle,
                constraints = Constraints(maxWidth = (minOf(360.dp, maxWidth) - 48.dp).coerceAtLeast(0.dp).roundToPx()),
            ).size.height.toDp()
        }
        // Preserve the reference's 112dp mark whenever it fits. Only the decorative mark gives
        // space back to wrapped/localized text and the 48dp retry target above a tall IME.
        val markSize = minOf(112.dp,
            (maxHeight - headlineHeight - if (retryVisible) 80.dp else 16.dp).coerceAtLeast(0.dp))
        Column(
            modifier = Modifier.align(if (opening) Alignment.TopCenter else Alignment.Center)
                // Opening retains the original centered 48dp ring and its gesture barrier.
                // Only passive text appears below it; the welcome mark never duplicates that ring.
                .padding(top = if (opening) maxHeight / 2 + 24.dp + 16.dp else 0.dp)
                .widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 24.dp)
                .testTag("remote-empty-conversation"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!opening && markSize > 0.dp) {
                Icon(
                    painter = painterResource(R.drawable.provider_openai),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(markSize)
                        .testTag("remote-welcome-mark"),
                )
            }
            Text(
                text = headlineText,
                modifier = Modifier.semantics { heading() }.testTag("remote-welcome-headline"),
                style = headlineStyle,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            if (retryVisible) {
                TextButton(enabled = active, onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}
