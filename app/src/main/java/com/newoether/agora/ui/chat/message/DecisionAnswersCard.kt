package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.api.decisions.DecisionAnswer
import com.newoether.agora.api.decisions.DecisionQuestionType
import com.newoether.agora.api.decisions.DecisionsResponse
import com.newoether.agora.model.MessageSegment
import kotlinx.serialization.json.Json

/**
 * One decision answer as a card.
 *
 * The endpoint answers in probabilities, not prose, so the numbers are the point: the winner,
 * the confidence behind it and the spread it beat. Writing them as a sentence would bury exactly
 * what the caller asked for.
 */
@Composable
internal fun DecisionAnswersCard(
    segment: MessageSegment,
    modifier: Modifier = Modifier,
) {
    val response = rememberDecisionAnswers(segment.decisionAnswers)
    if (response == null) {
        Text(
            segment.content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        response.answers?.forEach { (name, answer) ->
            AnswerRow(name = name, answer = answer)
        }
    }
}

@Composable
private fun AnswerRow(name: String, answer: DecisionAnswer) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            when (answer.type) {
                DecisionQuestionType.NOUL -> NoulBody(answer)
                DecisionQuestionType.CHOICE -> ChoiceBody(answer)
                DecisionQuestionType.SCORE -> ScoreBody(answer)
                else -> Text(
                    stringResource(R.string.decision_answer_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NoulBody(answer: DecisionAnswer) {
    val yes = answer.noul
    if (yes == null) {
        Text(answerText(answer), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (yes >= 0.5) stringResource(R.string.decision_answer_yes)
            else stringResource(R.string.decision_answer_no),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        )
        Text(
            "  ${percent(yes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    LinearProgressIndicator(
        progress = { yes.toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun ChoiceBody(answer: DecisionAnswer) {
    val picked = answer.choice
    if (picked == null) {
        Text(answerText(answer), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(
        picked,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
    )
    answer.confidence?.let {
        Text(
            stringResource(R.string.decision_answer_confidence, percent(it)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val spread = answer.probabilities
        ?.filterKeys { it != picked }
        ?.filterValues { it > 0.0 }
        ?.toList()
        ?.sortedByDescending(Pair<String, Double>::second)
        .orEmpty()
    spread.forEach { (option, p) ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(option, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(percent(p), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ScoreBody(answer: DecisionAnswer) {
    val value = answer.score
    if (value == null) {
        Text(answerText(answer), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val label = answer.legend?.get(value.toInt().toString())
    Text(
        label?.let { "${trimmed(value)} — $it" } ?: trimmed(value),
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
    )
    answer.confidence?.let {
        Text(
            stringResource(R.string.decision_answer_confidence, percent(it)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val levels = answer.probabilities
        ?.mapNotNull { (index, p) -> answer.legend?.get(index)?.let { it to p } }
        ?.filter { (_, p) -> p > 0.0 }
        .orEmpty()
    levels.forEach { (text, p) ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(percent(p), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun answerText(answer: DecisionAnswer): String =
    stringResource(R.string.decision_answer_missing)

@Composable
private fun rememberDecisionAnswers(raw: String?): DecisionsResponse? {
    if (raw.isNullOrBlank()) return null
    return remember(raw) {
        runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString<DecisionsResponse>(raw)
        }.getOrNull()
    }
}

private fun percent(value: Double): String = "${(value * 100).toInt()}%"

private fun trimmed(value: Double): String {
    val rounded = Math.round(value * 100.0) / 100.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}
