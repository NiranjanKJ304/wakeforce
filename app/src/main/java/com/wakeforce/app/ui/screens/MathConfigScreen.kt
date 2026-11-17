package com.wakeforce.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wakeforce.app.math.AdditionChallenge
import com.wakeforce.app.math.Difficulty
import com.wakeforce.app.math.MathSettings
import com.wakeforce.app.ui.components.WfIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MathConfigScreen(
    current: MathSettings,
    onSave: (MathSettings) -> Unit,
    onBack: () -> Unit,
) {
    var count by rememberSaveable { mutableIntStateOf(current.questionCount) }
    var minText by rememberSaveable { mutableStateOf(current.minValue.toString()) }
    var maxText by rememberSaveable { mutableStateOf(current.maxValue.toString()) }
    var difficulty by rememberSaveable { mutableStateOf(current.difficulty) }
    var previewSeed by remember { mutableIntStateOf(0) }

    val min = minText.toIntOrNull()
    val max = maxText.toIntOrNull()
    val candidate = MathSettings(count, min ?: -1, max ?: -1, difficulty)
    val error = if (min == null || max == null) "Enter whole numbers for minimum and maximum" else candidate.validationError()
    val preview = remember(candidate, previewSeed, error) {
        if (error == null) AdditionChallenge.generate(candidate.copy(questionCount = 3)) else emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Math challenge") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(WfIcons.Back, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Applies to every alarm that uses Math addition.", color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Number of questions", Modifier.weight(1f))
                FilledTonalButton(
                    onClick = { count-- },
                    enabled = count > MathSettings.MIN_QUESTIONS,
                ) { Text("−", fontSize = 20.sp) }
                Text("$count", fontSize = 22.sp, modifier = Modifier.padding(horizontal = 16.dp))
                FilledTonalButton(
                    onClick = { count++ },
                    enabled = count < MathSettings.MAX_QUESTIONS,
                ) { Text("+", fontSize = 20.sp) }
            }

            Text("Difficulty")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Difficulty.entries.forEachIndexed { index, d ->
                    SegmentedButton(
                        selected = difficulty == d,
                        onClick = { difficulty = d },
                        shape = SegmentedButtonDefaults.itemShape(index, Difficulty.entries.size),
                    ) { Text(d.label) }
                }
            }
            Text(
                "${difficulty.label}: ${difficulty.description}. Addition only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (difficulty.usesCustomRange) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField("Minimum", minText, { minText = it }, Modifier.weight(1f))
                    NumberField("Maximum", maxText, { maxText = it }, Modifier.weight(1f))
                }
            }

            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error)
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Sample questions", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                            TextButton(onClick = { previewSeed++ }) { Text("Shuffle") }
                        }
                        preview.forEach { q ->
                            Row(Modifier.padding(vertical = 4.dp)) {
                                Text(q.text, fontSize = 22.sp, modifier = Modifier.weight(1f))
                                Text(
                                    "${q.answer}",
                                    fontSize = 22.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(1.dp))
            Button(
                onClick = {
                    onSave(candidate)
                    onBack()
                },
                enabled = error == null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Save") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onChange(new.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
