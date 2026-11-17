package com.wakeforce.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Large in-app number pad. Used on the alarm screen instead of the system keyboard so
 * input works reliably over the lock screen and is easy to hit while half asleep.
 */
@Composable
fun NumberKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = listOf("123", "456", "789")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { digit -> DigitKey(digit, onDigit, Modifier.weight(1f)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onBackspace, modifier = Modifier.weight(1f).height(KeyHeight)) {
                Icon(WfIcons.Backspace, contentDescription = "Delete")
            }
            DigitKey('0', onDigit, Modifier.weight(1f))
            Button(onClick = onSubmit, modifier = Modifier.weight(1f).height(KeyHeight)) {
                Text("OK", fontSize = 22.sp)
            }
        }
    }
}

private val KeyHeight = 64.dp

@Composable
private fun DigitKey(digit: Char, onDigit: (Char) -> Unit, modifier: Modifier) {
    FilledTonalButton(onClick = { onDigit(digit) }, modifier = modifier.height(KeyHeight)) {
        Text(digit.toString(), fontSize = 28.sp)
    }
}
