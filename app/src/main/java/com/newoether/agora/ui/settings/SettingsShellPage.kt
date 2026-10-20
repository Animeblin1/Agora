package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.viewmodel.ChatViewModel

/** A fresh route owns an immutable opening snapshot, independently of persisted list emissions. */
internal class ShellDeviceEditorRoute(val initial: ShellDeviceConfig, val isNew: Boolean)

@Composable
fun SettingsShellPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val shellEnabled by viewModel.settings.shellEnabled.collectAsState()
    val shellConfirmEnabled by viewModel.settings.shellConfirmEnabled.collectAsState()
    val shellDevices by viewModel.settings.shellDevices.collectAsState()
    val sandboxEnabled by viewModel.settings.sandboxEnabled.collectAsState()
    val sandboxSharedStorageEnabled by viewModel.settings.sandboxSharedStorageEnabled.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    var editorRoute by remember { mutableStateOf<ShellDeviceEditorRoute?>(null) }
    var showSandboxMgmt by remember { mutableStateOf(false) }
    var sandboxEntryCount by remember { mutableIntStateOf(0) }
    val pageActive = LocalSettingsScrollRetentionActive.current
    SideEffect {
        if (!pageActive) editorRoute = null
    }
    BackHandler(enabled = pageActive && showSandboxMgmt) { showSandboxMgmt = false }

    GuardedAnimatedContent(targetState = showSandboxMgmt, forward = showSandboxMgmt) { isMgmt ->
        if (isMgmt && viewModel.sandboxManager != null) {
            SettingsSecondaryPane {
                key(sandboxEntryCount) {
                    SettingsSandboxPage(
                        sandboxManager = viewModel.sandboxManager!!,
                        onBack = { showSandboxMgmt = false },
                        showDocFab = showDocFab,
                        sharedStorageEnabled = sandboxSharedStorageEnabled,
                        onSharedStorageEnabledChange = viewModel.settings::setSandboxSharedStorageEnabled,
                    )
                }
            }
        } else {
            GuardedAnimatedContent(
                targetState = editorRoute,
                forward = editorRoute != null,
                contentKey = { it?.initial?.id ?: "devices" },
            ) { route ->
                if (route != null) {
                    SettingsSecondaryPane {
                        SettingsShellDeviceEditor(
                            initial = route.initial,
                            isNew = route.isNew,
                            viewModel = viewModel,
                            existingDevices = shellDevices,
                            showDocFab = showDocFab,
                            isCurrent = { pageActive && !showSandboxMgmt && editorRoute === route },
                            onBack = { if (editorRoute === route) editorRoute = null },
                        )
                    }
                } else {
                    CollapsingSettingsScaffold(
                        title = stringResource(R.string.shell_title),
                        onBack = onBack,
                    ) {
                        SettingsGroupColumn {
                            SettingsGroup(
                                title = stringResource(R.string.shell_title),
                                items = buildList {
                                    add {
                                        SettingsItem(
                                            headlineContent = { Text(stringResource(R.string.shell_enable)) },
                                            supportingContent = { Text(stringResource(R.string.shell_enable_desc)) },
                                            leadingContent = {
                                                Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                                            },
                                            trailingContent = {
                                                Switch(shellEnabled, onCheckedChange = viewModel.settings::setShellEnabled)
                                            },
                                            modifier = Modifier.clickable { viewModel.settings.setShellEnabled(!shellEnabled) },
                                        )
                                    }
                                    if (shellEnabled) {
                                        add {
                                            SettingsItem(
                                                headlineContent = { Text(stringResource(R.string.shell_confirm_setting)) },
                                                supportingContent = { Text(stringResource(R.string.shell_confirm_setting_desc)) },
                                                leadingContent = {
                                                    Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary)
                                                },
                                                trailingContent = {
                                                    Switch(shellConfirmEnabled, onCheckedChange = { viewModel.shellConfirmation.setEnabled(it) })
                                                },
                                                modifier = Modifier.clickable {
                                                    viewModel.shellConfirmation.setEnabled(!shellConfirmEnabled)
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                            if (shellEnabled) {
                                if (viewModel.isSandboxFlavor) {
                                    SandboxSection(viewModel, sandboxEnabled) {
                                        sandboxEntryCount++
                                        showSandboxMgmt = true
                                    }
                                } else {
                                    SandboxNotSupportedSection()
                                }
                                SettingsGroup(
                                    title = stringResource(R.string.shell_devices),
                                    items = buildList {
                                        if (shellDevices.isEmpty()) {
                                            add {
                                                SettingsItem(
                                                    headlineContent = {
                                                        Text(stringResource(R.string.shell_no_devices), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    },
                                                    leadingContent = {
                                                        Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    },
                                                    modifier = Modifier.heightIn(min = 64.dp),
                                                )
                                            }
                                        }
                                        shellDevices.forEach { device ->
                                            add {
                                                ShellDeviceRow(device) {
                                                    editorRoute = ShellDeviceEditorRoute(device, isNew = false)
                                                }
                                            }
                                        }
                                        add {
                                            SettingsAddItem(
                                                label = stringResource(R.string.shell_add_device),
                                                onClick = {
                                                    editorRoute = ShellDeviceEditorRoute(
                                                        ShellDeviceConfig(name = ""), isNew = true,
                                                    )
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShellDeviceRow(device: ShellDeviceConfig, onEdit: () -> Unit) {
    val type = if (device.type == "ssh") "SSH" else "Conch"
    val connection = if (device.type == "ssh") {
        if (device.sshHost.isBlank()) "" else "${device.sshUser}@${device.sshHost}:${device.sshPort}"
    } else device.serverUrl
    SettingsItem(
        headlineContent = { Text(device.name.ifBlank { stringResource(R.string.search_untitled) }) },
        supportingContent = {
            Text(listOf(type, connection).filter { it.isNotBlank() }.joinToString(" · "))
            if (device.description.isNotBlank()) Text(device.description)
        },
        leadingContent = { Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, stringResource(R.string.edit), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        modifier = Modifier.clickable(onClick = onEdit),
    )
}

@Composable
private fun SandboxSection(viewModel: ChatViewModel, sandboxEnabled: Boolean, onManage: () -> Unit) {
    SettingsGroup(title = stringResource(R.string.sandbox_title), items = buildList {
        add {
            SettingsItem(
                headlineContent = { Text(stringResource(R.string.sandbox_enable)) },
                supportingContent = { Text(stringResource(R.string.sandbox_enable_desc)) },
                leadingContent = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = {
                    Switch(checked = sandboxEnabled, onCheckedChange = viewModel.settings::setSandboxEnabled)
                },
                modifier = Modifier.clickable { viewModel.settings.setSandboxEnabled(!sandboxEnabled) },
            )
        }
        if (sandboxEnabled) {
            add {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.sandbox_manage)) },
                    supportingContent = { Text(stringResource(R.string.sandbox_manage_desc)) },
                    leadingContent = { Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { onManage() },
                )
            }
        }
    })
}

@Composable
private fun SandboxNotSupportedSection() {
    SettingsGroup(title = stringResource(R.string.sandbox_title), items = listOf {
        SettingsItem(
            headlineContent = { Text(stringResource(R.string.sandbox_not_supported)) },
            supportingContent = { Text(stringResource(R.string.sandbox_not_supported_desc)) },
            leadingContent = {
                Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
        )
    })
}
