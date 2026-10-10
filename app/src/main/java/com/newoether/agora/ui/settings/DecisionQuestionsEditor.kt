package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.api.decisions.DecisionQuestionType

/**
 * Edits the questions a decision model answers.
 *
 * Each question is a row of fields rather than a JSON blob: the wire shape is an implementation
 * detail of the endpoint, and a user thinks in "is this urgent? yes or no", not in `criteria`
 * maps. The drafts are parsed once when the editor opens and serialized once when it saves.
 */
@Composable
fun DecisionQuestionsEditor(
    questions: String,
    onQuestionsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drafts = remember(questions) { mutableStateListOf(*decodeDecisionQuestions(questions).toTypedArray()) }
    var expandedIndex by remember { mutableStateOf(-1) }

    fun commit() = onQuestionsChange(encodeDecisionQuestions(drafts))

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.decision_questions),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
        Text(
            stringResource(R.string.decision_questions_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (drafts.isEmpty()) {
            Text(
                stringResource(R.string.decision_questions_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        drafts.forEachIndexed { index, draft ->
            QuestionCard(
                draft = draft,
                expanded = expandedIndex == index,
                onExpand = { expandedIndex = if (expandedIndex == index) -1 else index },
                onChange = { updated ->
                    drafts[index] = updated
                    commit()
                },
                onRemove = {
                    drafts.removeAt(index)
                    expandedIndex = -1
                    commit()
                },
            )
        }
        TextButton(
            onClick = {
                drafts.add(DecisionQuestionDraft())
                expandedIndex = drafts.lastIndex
            },
            modifier = Modifier.align(Alignment.Start),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(R.string.decision_questions_add))
        }
    }
}

@Composable
private fun QuestionCard(
    draft: DecisionQuestionDraft,
    expanded: Boolean,
    onExpand: () -> Unit,
    onChange: (DecisionQuestionDraft) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onChange(draft.copy(name = it)) },
                singleLine = true,
                label = { Text(stringResource(R.string.decision_question_name)) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                )
            }
        }
        val types = DecisionQuestionDraft.TYPES
        val labels = listOf(
            stringResource(R.string.decision_question_type_noul),
            stringResource(R.string.decision_question_type_choice),
            stringResource(R.string.decision_question_type_score),
        )
        PillTabSwitcher(
            tabs = labels,
            selectedIndex = types.indexOf(draft.type).coerceAtLeast(0),
            onSelect = { index ->
                types.getOrNull(index)?.let { onChange(draft.copy(type = it)) }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            allowLabelOverflow = true,
        )
        OutlinedTextField(
            value = draft.instructions,
            onValueChange = { onChange(draft.copy(instructions = it)) },
            minLines = 2,
            label = { Text(stringResource(R.string.decision_question_instructions)) },
            supportingText = { Text(stringResource(R.string.decision_question_instructions_desc)) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        // Only a choice or a score has options to name; a noul answers yes or no on its own.
        if (draft.type != DecisionQuestionType.NOUL) {
            OptionsEditor(
                options = draft.options,
                label = if (draft.type == DecisionQuestionType.CHOICE) {
                    stringResource(R.string.decision_question_options)
                } else {
                    stringResource(R.string.decision_question_levels)
                },
                onChange = { onChange(draft.copy(options = it)) },
            )
        }
    }
}

@Composable
private fun OptionsEditor(
    options: List<String>,
    label: String,
    onChange: (List<String>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        options.forEachIndexed { index, option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = option,
                    onValueChange = { changed ->
                        onChange(options.toMutableList().also { it[index] = changed })
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f).padding(top = 4.dp),
                )
                IconButton(onClick = {
                    onChange(options.toMutableList().also { it.removeAt(index) })
                }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                    )
                }
            }
        }
        TextButton(onClick = { onChange(options + "") }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(R.string.decision_questions_add_option))
        }
    }
}
