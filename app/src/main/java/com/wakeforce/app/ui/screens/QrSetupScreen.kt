package com.wakeforce.app.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.print.PrintHelper
import com.wakeforce.app.data.Alarm
import com.wakeforce.app.data.AlarmJson
import com.wakeforce.app.data.RegisteredQr
import com.wakeforce.app.qr.QrCodes
import com.wakeforce.app.qr.QrRegistry
import com.wakeforce.app.settings.SystemSettings
import com.wakeforce.app.ui.components.QrScannerView
import com.wakeforce.app.ui.components.WfIcons
import com.wakeforce.app.ui.components.rememberCameraPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Registers an alarm's 1-3 QR codes, either by scanning an existing physical QR code (its
 * payload is stored) or by generating a code in the app, printing it and scanning the copy
 * back. Codes can be renamed, replaced and removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrCodesEditor(
    initial: List<RegisteredQr>,
    onDone: (List<RegisteredQr>) -> Unit,
    onCancel: () -> Unit,
    startScanning: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var codesJson by rememberSaveable { mutableStateOf(AlarmJson.encodeQrString(initial)) }
    var pendingJson by rememberSaveable { mutableStateOf("") }
    var replaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var scanningExisting by rememberSaveable { mutableStateOf(startScanning && initial.size < Alarm.MAX_QR_CODES) }
    var replaceScannedId by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var saveTarget by remember { mutableStateOf<RegisteredQr?>(null) }

    val codes = AlarmJson.decodeQrString(codesJson)
    val pending = AlarmJson.decodeQrString(pendingJson).firstOrNull()
    val camera = rememberCameraPermissionState()
    BackHandler(onBack = onCancel)

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val target = saveTarget ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        QrCodes.render(target.payload, PRINT_SIZE_PX).compress(Bitmap.CompressFormat.PNG, 100, out)
                    } ?: false
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) "${target.label} saved" else "Could not save image", Toast.LENGTH_SHORT).show()
        }
    }
    fun save(code: RegisteredQr) {
        saveTarget = code
        saveLauncher.launch("wakeforce-${code.label.lowercase().replace(' ', '-')}.png")
    }
    fun print(code: RegisteredQr) {
        PrintHelper(context).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }
            .printBitmap("WakeForce ${code.label}", QrCodes.render(code.payload, PRINT_SIZE_PX))
    }
    fun startPending(replacing: RegisteredQr?) {
        val label = replacing?.label ?: "Code ${codes.size + 1}"
        pendingJson = AlarmJson.encodeQrString(listOf(QrRegistry.newPending(codes, label)))
        replaceId = replacing?.id
        scanning = false
        message = null
    }
    fun clearPending() {
        pendingJson = ""
        replaceId = null
        scanning = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QR codes (${codes.size}/${Alarm.MAX_QR_CODES})") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(WfIcons.Back, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Scan any QR code you own (a sticker, a printed code) and put it away from your bed, " +
                    "e.g. in the bathroom. When the alarm rings, scanning it stops the alarm. You can " +
                    "register up to 3 codes; then every one must be scanned.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (scanningExisting) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Point the camera at your QR code", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        if (camera.granted) {
                            QrScannerView(
                                onCode = { scanned ->
                                    val replacing = replaceScannedId
                                    val result = if (replacing != null) QrRegistry.replaceScanned(codes, replacing, scanned)
                                    else QrRegistry.registerScanned(codes, scanned, "Code ${codes.size + 1}")
                                    when (val r = result) {
                                        is QrRegistry.RegisterResult.Registered -> {
                                            codesJson = AlarmJson.encodeQrString(r.codes)
                                            message = if (replacing != null) "Code replaced" else "Code ${codes.size + 1} registered"
                                            scanningExisting = false
                                            replaceScannedId = null
                                        }
                                        is QrRegistry.RegisterResult.Duplicate ->
                                            message = "Already registered as ${r.existingLabel}. Scan a different code."
                                        QrRegistry.RegisterResult.Invalid ->
                                            message = "That QR code is empty or too long to use."
                                        QrRegistry.RegisterResult.Full -> message = "This alarm already has 3 codes."
                                        QrRegistry.RegisterResult.Mismatch -> Unit
                                    }
                                },
                                onError = { message = it; scanningExisting = false },
                                modifier = Modifier.size(260.dp).clip(RoundedCornerShape(16.dp)),
                            )
                        } else {
                            CameraPermissionPrompt(camera.denied, camera.request) {
                                SystemSettings.open(context, SystemSettings.appDetails(context))
                            }
                        }
                        TextButton(onClick = { scanningExisting = false; replaceScannedId = null }) { Text("Cancel") }
                    }
                }
            }

            codes.forEachIndexed { index, code ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        QrThumb(code.payload, 72)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(code.label, style = MaterialTheme.typography.titleMedium)
                            Text("Registered · ${index + 1} of ${codes.size}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    FlowRow(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { renameId = code.id }) { Text("Rename") }
                        TextButton(onClick = {
                            // Swapped in place only after a valid scan; Cancel keeps the old code.
                            clearPending()
                            replaceScannedId = code.id
                            scanningExisting = true
                            message = "Scan the replacement for ${code.label}"
                        }, enabled = pending == null) { Text("Replace") }
                        if (QrCodes.isWakeForceCode(code.payload)) {
                            // Only app-generated codes need saving/printing; physical ones already exist.
                            TextButton(onClick = { save(code) }) { Text("Save image") }
                            TextButton(onClick = { print(code) }) { Text("Print") }
                        }
                        TextButton(onClick = { codesJson = AlarmJson.encodeQrString(QrRegistry.remove(codes, code.id)) }) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (pending != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (replaceId != null) "Replacement for ${pending.label}" else "New: ${pending.label}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text("1. Save or print it.  2. Scan the copy to register it.", textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        if (!scanning) QrThumb(pending.payload, 220)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { save(pending) }) { Text("Save image") }
                            FilledTonalButton(onClick = { print(pending) }) { Text("Print") }
                        }
                        if (scanning) {
                            if (camera.granted) {
                                QrScannerView(
                                    onCode = { scanned ->
                                        when (val r = QrRegistry.confirm(codes, pending, scanned, replaceId)) {
                                            is QrRegistry.RegisterResult.Registered -> {
                                                codesJson = AlarmJson.encodeQrString(r.codes)
                                                message = "${pending.label} registered"
                                                clearPending()
                                            }
                                            QrRegistry.RegisterResult.Mismatch ->
                                                message = "That's a different code. Scan the copy of the code shown above."
                                            is QrRegistry.RegisterResult.Duplicate ->
                                                message = "Already registered as ${r.existingLabel}. Each code must be different."
                                            QrRegistry.RegisterResult.Full -> message = "This alarm already has 3 codes."
                                            QrRegistry.RegisterResult.Invalid ->
                                                message = "That QR code is empty or too long to use."
                                        }
                                    },
                                    onError = { message = it; scanning = false },
                                    modifier = Modifier.size(260.dp).clip(RoundedCornerShape(16.dp)),
                                )
                            } else {
                                CameraPermissionPrompt(camera.denied, camera.request) {
                                    SystemSettings.open(context, SystemSettings.appDetails(context))
                                }
                            }
                        }
                        Button(onClick = { scanning = !scanning; message = null }) {
                            Text(if (scanning) "Stop scanning" else "Scan to register")
                        }
                        TextButton(onClick = ::clearPending) { Text("Cancel") }
                    }
                }
            } else if (codes.size < Alarm.MAX_QR_CODES && !scanningExisting) {
                Button(onClick = { scanningExisting = true; message = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan an existing QR code${if (codes.size >= Alarm.MIN_QR_CODES) " (optional)" else ""}")
                }
                OutlinedButton(onClick = { startPending(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("No QR code? Generate one to print")
                }
            }

            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center) }

            val valid = QrRegistry.isValidForAlarm(codes)
            Button(
                onClick = { onDone(codes) },
                enabled = valid && pending == null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Use ${if (codes.size == 1) "this code" else "these codes"} (${codes.size}/${Alarm.MAX_QR_CODES})") }
            if (!valid) {
                Text("Register at least one QR code.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    renameId?.let { id ->
        var text by remember(id) { mutableStateOf(codes.firstOrNull { it.id == id }?.label.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text("Rename code") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it.take(QrRegistry.MAX_LABEL_LENGTH) },
                    singleLine = true, label = { Text("Name, e.g. Bathroom") })
            },
            confirmButton = {
                TextButton(onClick = {
                    codesJson = AlarmJson.encodeQrString(QrRegistry.rename(codes, id, text))
                    renameId = null
                }, enabled = text.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun QrThumb(payload: String, sizeDp: Int) {
    // A long physical-code payload may not fit a high-error-correction QR; show a placeholder then.
    val bitmap = remember(payload) { runCatching { QrCodes.render(payload, 384) }.getOrNull() }
    if (bitmap == null) {
        Box(Modifier.size(sizeDp.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center) { Text("QR") }
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR code",
        modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape(8.dp)).background(Color.White),
    )
}

@Composable
fun CameraPermissionPrompt(denied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Camera access is needed to scan the codes. It is used only while scanning.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRequest) { Text("Allow camera") }
            if (denied) TextButton(onClick = onOpenSettings) { Text("Open app settings") }
        }
    }
}

private const val PRINT_SIZE_PX = 1024
