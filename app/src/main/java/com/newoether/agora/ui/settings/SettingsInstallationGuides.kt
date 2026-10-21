package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.newoether.agora.R

internal const val CONCH_USER_UNIX_INSTALL =
    "curl -fsSL https://raw.githubusercontent.com/newo-ether/conch/main/scripts/install.sh | bash"
internal const val CONCH_USER_WINDOWS_INSTALL =
    "Set-ExecutionPolicy Bypass -Scope Process -Force; irm https://raw.githubusercontent.com/newo-ether/conch/main/scripts/install.ps1 | iex"
internal const val CONCH_SYSTEM_UNIX_INSTALL =
    "curl -fsSL https://raw.githubusercontent.com/newo-ether/conch/main/scripts/install.sh | sudo bash -s -- --mode system"
internal const val CONCH_SYSTEM_WINDOWS_INSTALL =
    "Set-ExecutionPolicy Bypass -Scope Process -Force; & ([scriptblock]::Create((irm https://raw.githubusercontent.com/newo-ether/conch/main/scripts/install.ps1))) -Mode system"
internal const val FILO_WINDOWS_INSTALL =
    "Set-ExecutionPolicy Bypass -Scope Process -Force; irm https://raw.githubusercontent.com/newo-ether/filo/main/install.ps1 | iex"

@Composable
internal fun SettingsConchGuidePage(onBack: () -> Unit, onAddDevice: () -> Unit) {
    var platform by remember { mutableIntStateOf(0) }
    var systemMode by remember { mutableStateOf(false) }
    val pageActive = LocalSettingsScrollRetentionActive.current
    var closed by remember { mutableStateOf(false) }
    SideEffect { if (!pageActive) closed = true }
    val latestPageActive by rememberUpdatedState(pageActive)
    fun ownsGuide() = latestPageActive && !closed
    val active = ownsGuide()
    val back = { if (ownsGuide()) { closed = true; onBack() } }
    BackHandler(enabled = active, onBack = back)
    val command = when {
        platform == 0 && systemMode -> CONCH_SYSTEM_WINDOWS_INSTALL
        platform == 0 -> CONCH_USER_WINDOWS_INSTALL
        platform == 1 && systemMode -> CONCH_SYSTEM_UNIX_INSTALL
        else -> CONCH_USER_UNIX_INSTALL
    }
    CollapsingSettingsScaffold(
        title = stringResource(R.string.conch_guide_title),
        onBack = back,
        modifier = Modifier.testTag("conch-install-guide"),
    ) {
        SettingsGroupColumn {
            SettingsGroup(stringResource(R.string.install_guide_purpose), items = listOf {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.conch_guide_title)) },
                    supportingContent = { Text(stringResource(R.string.conch_guide_description)) },
                    leadingContent = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary) },
                )
            })
            SettingsGroup(
                title = stringResource(R.string.install_guide_platform),
                modifier = Modifier.selectableGroup().testTag("conch-guide-platform"),
                items = listOf(
                    R.string.install_guide_windows,
                    R.string.install_guide_linux,
                    R.string.install_guide_termux,
                ).mapIndexed { index, label ->
                    {
                        SettingsItem(
                            headlineContent = {
                                Text(stringResource(label), modifier = Modifier.fillMaxWidth()
                                    .testTag("conch-guide-platform-label-$index"))
                            },
                            leadingContent = { RadioButton(selected = platform == index, onClick = null, enabled = active) },
                            modifier = Modifier.testTag("conch-guide-platform-$index").selectable(
                                selected = platform == index,
                                enabled = active,
                                role = Role.RadioButton,
                            ) {
                                if (ownsGuide()) {
                                    platform = index
                                    if (index == 2) systemMode = false
                                }
                            },
                        )
                    }
                },
            )
            SettingsGroup(stringResource(R.string.install_guide_install), items = listOf {
                SettingsIconContent(Icons.Default.Computer) {
                    Text(
                        stringResource(when {
                            systemMode -> R.string.conch_guide_system_mode_desc
                            platform == 0 -> R.string.conch_guide_windows_terminal
                            platform == 1 -> R.string.conch_guide_linux_terminal
                            else -> R.string.conch_guide_termux_terminal
                        }),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("conch-guide-terminal-note"),
                    )
                    Text(stringResource(R.string.install_guide_run_on_target), style = MaterialTheme.typography.bodyMedium)
                }
            })
            if (platform != 2) {
                SettingsGroup(stringResource(R.string.advanced_title), items = listOf {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.conch_guide_system_mode)) },
                        supportingContent = { Text(stringResource(R.string.conch_guide_system_mode_desc)) },
                        leadingContent = { Icon(Icons.Default.AdminPanelSettings, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(
                                checked = systemMode,
                                onCheckedChange = { if (ownsGuide()) systemMode = it },
                                enabled = active,
                                modifier = Modifier.testTag("conch-guide-system-mode"),
                            )
                        },
                        modifier = Modifier.clickable(enabled = active) { if (ownsGuide()) systemMode = !systemMode },
                    )
                })
            }
            SettingsGroup(stringResource(R.string.install_guide_copy_command), items = listOf {
                Column(Modifier.padding(16.dp)) {
                    if (platform != 2) {
                        Text(
                            stringResource(if (systemMode) R.string.conch_guide_system_mode else R.string.conch_guide_user_mode),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("conch-guide-mode-note"),
                        )
                    }
                    Text(stringResource(R.string.conch_guide_mode_preserved), style = MaterialTheme.typography.bodyMedium)
                    if (platform == 1 && !systemMode) Text(stringResource(R.string.conch_guide_linger), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    InstallCommand(command, active, "conch-guide-command")
                }
            })
            SettingsGroup(stringResource(R.string.install_guide_connect), items = listOf(
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.shell_device_name)) },
                        supportingContent = { Text(stringResource(R.string.install_guide_name_mapping)) },
                        leadingContent = { Icon(Icons.Default.Label, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.shell_device_url)) },
                        supportingContent = { Text(stringResource(R.string.conch_guide_address_mapping)) },
                        leadingContent = { Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.shell_device_key)) },
                        supportingContent = { Text(stringResource(R.string.conch_guide_key_mapping)) },
                        leadingContent = { Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                },
            ))
            Button(
                onClick = { if (ownsGuide()) { closed = true; onAddDevice() } },
                enabled = active,
                modifier = Modifier.fillMaxWidth().testTag("conch-guide-add-device"),
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.shell_add_device))
            }
            InstallSourceLink("https://github.com/newo-ether/conch", active, "conch-guide-source")
        }
    }
}

@Composable
internal fun SettingsFiloGuidePage(onBack: () -> Unit, onAddDevice: () -> Unit) {
    val pageActive = LocalSettingsScrollRetentionActive.current
    var closed by remember { mutableStateOf(false) }
    SideEffect { if (!pageActive) closed = true }
    val latestPageActive by rememberUpdatedState(pageActive)
    fun ownsGuide() = latestPageActive && !closed
    val active = ownsGuide()
    val back = { if (ownsGuide()) { closed = true; onBack() } }
    BackHandler(enabled = active, onBack = back)
    CollapsingSettingsScaffold(
        title = stringResource(R.string.filo_guide_title),
        onBack = back,
        modifier = Modifier.testTag("filo-install-guide"),
    ) {
        SettingsGroupColumn {
            SettingsGroup(stringResource(R.string.install_guide_purpose), items = listOf {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.filo_guide_title)) },
                    supportingContent = { Text(stringResource(R.string.filo_guide_description)) },
                    leadingContent = { Icon(Icons.Default.Computer, null, tint = MaterialTheme.colorScheme.primary) },
                )
            })
            SettingsGroup(stringResource(R.string.install_guide_install), items = listOf {
                SettingsIconContent(Icons.Default.DesktopWindows) {
                    Text(stringResource(R.string.install_guide_windows), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.filo_guide_windows_terminal), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.filo_guide_selected_user), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.filo_guide_scope), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.install_guide_run_on_target), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    InstallCommand(FILO_WINDOWS_INSTALL, active, "filo-guide-command")
                }
            })
            SettingsGroup(stringResource(R.string.install_guide_connect), items = listOf(
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.shell_device_name)) },
                        supportingContent = { Text(stringResource(R.string.install_guide_name_mapping)) },
                        leadingContent = { Icon(Icons.Default.Label, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.remote_address)) },
                        supportingContent = { Text(stringResource(R.string.filo_guide_address_mapping)) },
                        leadingContent = { Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.remote_token)) },
                        supportingContent = { Text(stringResource(R.string.filo_guide_token_mapping)) },
                        leadingContent = { Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                },
            ))
            Button(
                onClick = { if (ownsGuide()) { closed = true; onAddDevice() } },
                enabled = active,
                modifier = Modifier.fillMaxWidth().testTag("filo-guide-add-device"),
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.remote_add_device))
            }
            InstallSourceLink("https://github.com/newo-ether/filo", active, "filo-guide-source")
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun InstallCommand(command: String, active: Boolean, tag: String) {
    val clipboard = LocalClipboardManager.current
    val latestActive by rememberUpdatedState(active)
    SelectionContainer {
        Text(
            command,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.testTag(tag),
        )
    }
    TextButton(
        onClick = { if (latestActive) clipboard.setText(AnnotatedString(command)) },
        enabled = active,
        modifier = Modifier.testTag("$tag-copy"),
    ) {
        Icon(Icons.Default.ContentCopy, null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.install_guide_copy_command))
    }
}

@Composable
private fun InstallSourceLink(url: String, active: Boolean, tag: String) {
    val uriHandler = LocalUriHandler.current
    val latestActive by rememberUpdatedState(active)
    TextButton(
        onClick = { if (latestActive) runCatching { uriHandler.openUri(url) } },
        enabled = active,
        modifier = Modifier.testTag(tag),
    ) {
        Text(stringResource(R.string.install_guide_source))
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.OpenInNew, null)
    }
}
