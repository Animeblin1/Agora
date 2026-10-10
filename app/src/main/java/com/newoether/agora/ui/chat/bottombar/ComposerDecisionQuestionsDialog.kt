package com.newoether.agora.ui.chat.bottombar

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.settings.DecisionQuestionsEditor

/**
 * The questions the selected model answers, editable without leaving the conversation.
 *
 * A decisions model is useless with the wrong questions, and the questions depend on what this
 * conversation is about — so editing them belongs where the conversation is, not two screens away
 * in Settings.
 */
@Composable
internal fun ComposerDecisionQuestionsDialog(
    questions: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var edited by remember(questions) { mutableStateOf(questions) }
    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.decision_questions)) },
        text = {
            DecisionQuestionsEditor(
                questions = edited,
                onQuestionsChange = { edited = it },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(edited)
                onDismissRequest()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.cancel)) }
        },
    )
}
