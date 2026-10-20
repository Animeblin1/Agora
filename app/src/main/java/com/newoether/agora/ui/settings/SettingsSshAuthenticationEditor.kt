package com.newoether.agora.ui.settings

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.SSH_AUTH_PASSWORD
import com.newoether.agora.data.SSH_AUTH_PRIVATE_KEY
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.AgoraExposedDropdownMenu
import com.newoether.agora.ui.components.SecretVisibilityToggle
import com.newoether.agora.ui.components.rememberSecretVisible
import com.newoether.agora.ui.components.secretVisualTransformation
import com.newoether.agora.util.SshPrivateKeyException
import com.newoether.agora.util.readSshPrivateKey
import com.newoether.agora.util.validateSshPrivateKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns the SAF import and authentication fields of the existing explicit-save device draft. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSshAuthenticationEditor(
    device: ShellDeviceConfig,
    onChange: (ShellDeviceConfig) -> Unit,
    onImportingChange: (Boolean) -> Unit,
    @StringRes saveError: Int?,
) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val latestDevice by rememberUpdatedState(device)
    val latestOnChange by rememberUpdatedState(onChange)
    var menuExpanded by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<Int?>(null) }
    var encrypted by remember(device.sshPrivateKey) { mutableStateOf(false) }
    var requestSequence by remember { mutableIntStateOf(0) }
    var pickerSequence by remember { mutableIntStateOf(-1) }
    var passwordVisible by rememberSecretVisible()
    var passphraseVisible by rememberSecretVisible()
    LaunchedEffect(device.sshPrivateKey) {
        encrypted = if (device.sshPrivateKey.isBlank()) false else withContext(Dispatchers.IO) {
            runCatching { validateSshPrivateKey(device.sshPrivateKey) }.getOrDefault(false)
        }
    }
    DisposableEffect(Unit) {
        onDispose { onImportingChange(false) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pickerSequence == requestSequence && latestDevice.sshAuthMode == SSH_AUTH_PRIVATE_KEY) {
            val request = pickerSequence
            scope.launch {
                importing = true
                onImportingChange(true)
                try {
                    val (name, imported) = withContext(Dispatchers.IO) {
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor ->
                                if (cursor.moveToFirst()) cursor.getString(0)?.take(160) else null
                            }.orEmpty()
                        val key = resolver.openInputStream(uri)?.use(::readSshPrivateKey)
                            ?: error("Private key source is unavailable")
                        name to key
                    }
                    if (request == requestSequence && latestDevice.sshAuthMode == SSH_AUTH_PRIVATE_KEY) {
                        latestOnChange(latestDevice.copy(
                            sshPassword = "",
                            sshPrivateKey = imported.first,
                            sshPrivateKeyName = name,
                            sshKeyPassphrase = "",
                        ))
                        encrypted = imported.second
                        importError = null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (request == requestSequence) importError = sshPrivateKeyErrorResource(error)
                } finally {
                    importing = false
                    onImportingChange(false)
                }
            }
        }
    }
    ExposedDropdownMenuBox(expanded = menuExpanded, onExpandedChange = { menuExpanded = it }) {
        OutlinedTextField(
            value = stringResource(if (device.sshAuthMode == SSH_AUTH_PRIVATE_KEY) {
                R.string.shell_ssh_auth_private_key
            } else R.string.shell_device_password),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.shell_ssh_auth_method)) },
            leadingIcon = { Icon(Icons.Default.Key, null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuExpanded) },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        AgoraExposedDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            listOf(SSH_AUTH_PASSWORD, SSH_AUTH_PRIVATE_KEY).forEach { mode ->
                AgoraDropdownMenuItem(
                    text = { Text(stringResource(if (mode == SSH_AUTH_PRIVATE_KEY) {
                        R.string.shell_ssh_auth_private_key
                    } else R.string.shell_device_password)) },
                    onClick = {
                        menuExpanded = false
                        if (mode != device.sshAuthMode) {
                            requestSequence++
                            importError = null
                            onChange(device.copy(
                                sshAuthMode = mode,
                                sshPassword = "",
                                sshPrivateKey = "",
                                sshPrivateKeyName = "",
                                sshKeyPassphrase = "",
                            ))
                        }
                    },
                    leadingIcon = { Icon(if (mode == SSH_AUTH_PRIVATE_KEY) Icons.Default.Key else Icons.Default.Password, null) },
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    if (device.sshAuthMode == SSH_AUTH_PRIVATE_KEY) {
        ModelImportItem(
            title = device.sshPrivateKeyName.ifBlank { stringResource(R.string.shell_ssh_import_private_key) },
            description = stringResource(R.string.shell_ssh_private_key_desc),
            icon = Icons.Default.UploadFile,
            importing = importing,
            onClick = {
                requestSequence++
                pickerSequence = requestSequence
                picker.launch(arrayOf("*/*"))
            },
        )
        if (encrypted) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = device.sshKeyPassphrase,
                onValueChange = { onChange(device.copy(sshKeyPassphrase = it)) },
                label = { Text(stringResource(R.string.shell_ssh_private_key_passphrase)) },
                leadingIcon = { Icon(Icons.Default.Password, null) },
                visualTransformation = secretVisualTransformation(passphraseVisible),
                trailingIcon = { SecretVisibilityToggle(passphraseVisible) { passphraseVisible = !passphraseVisible } },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        OutlinedTextField(
            value = device.sshPassword,
            onValueChange = { onChange(device.copy(sshPassword = it)) },
            label = { Text(stringResource(R.string.shell_device_password)) },
            leadingIcon = { Icon(Icons.Default.Password, null) },
            visualTransformation = secretVisualTransformation(passwordVisible),
            trailingIcon = { SecretVisibilityToggle(passwordVisible) { passwordVisible = !passwordVisible } },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    (importError ?: saveError)?.let {
        Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@StringRes
internal fun sshPrivateKeyErrorResource(error: Exception): Int = when ((error as? SshPrivateKeyException)?.reason) {
    SshPrivateKeyException.Reason.INVALID -> R.string.shell_ssh_private_key_invalid
    SshPrivateKeyException.Reason.TOO_LARGE -> R.string.shell_ssh_private_key_too_large
    SshPrivateKeyException.Reason.PASSPHRASE -> R.string.shell_ssh_private_key_passphrase_invalid
    null -> R.string.shell_ssh_private_key_read_failed
}
