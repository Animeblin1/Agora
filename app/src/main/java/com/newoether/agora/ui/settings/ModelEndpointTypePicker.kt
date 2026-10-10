package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ModelEndpointType
import com.newoether.agora.ui.components.PillTabSwitcher

@Composable
fun ModelEndpointType.displayName(): String = when (this) {
    ModelEndpointType.COMPLETIONS -> stringResource(R.string.endpoint_type_completions)
    ModelEndpointType.RESPONSES -> stringResource(R.string.endpoint_type_responses)
    ModelEndpointType.DECISIONS -> stringResource(R.string.endpoint_type_decisions)
    ModelEndpointType.CUSTOM -> stringResource(R.string.endpoint_type_custom)
}

/**
 * Endpoint choice for one model.
 *
 * The four tabs are the transports Agora knows how to speak; [ModelEndpointType.CUSTOM] unlocks a
 * free-form path because a relay can mount a compatible endpoint anywhere, where nothing about
 * the base URL says which one it is.
 */
@Composable
fun ModelEndpointTypePicker(
    selected: ModelEndpointType,
    onSelected: (ModelEndpointType) -> Unit,
    customPath: String,
    onCustomPathChange: (String) -> Unit,
    questions: String,
    onQuestionsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val types = ModelEndpointType.selectable
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        PillTabSwitcher(
            tabs = types.map { it.displayName() },
            selectedIndex = types.indexOf(selected).coerceAtLeast(0),
            onSelect = { index -> types.getOrNull(index)?.let(onSelected) },
            modifier = Modifier.fillMaxWidth(),
            allowLabelOverflow = true,
        )
        Text(
            stringResource(R.string.endpoint_type_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (selected == ModelEndpointType.CUSTOM) {
            OutlinedTextField(
                value = customPath,
                onValueChange = onCustomPathChange,
                singleLine = true,
                label = { Text(stringResource(R.string.endpoint_path)) },
                supportingText = { Text(stringResource(R.string.endpoint_path_desc)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        if (selected == ModelEndpointType.DECISIONS) {
            OutlinedTextField(
                value = questions,
                onValueChange = onQuestionsChange,
                minLines = 4,
                label = { Text(stringResource(R.string.decision_questions)) },
                supportingText = { Text(stringResource(R.string.decision_questions_desc)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}
