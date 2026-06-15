package app.xodos2.ui.drawer.pages

import android.content.SharedPreferences
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xodos2.TerminalSessionIds
import app.xodos2.shell.ShellFonts
import app.xodos2.ui.dialog.MOUSE_MODE_TABLET
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.drawer.menu.DrawerMenu
import app.xodos2.ui.drawer.menu.DrawerMenuActions
import app.xodos2.ui.drawer.menu.DrawerMenuLabels
import app.xodos2.ui.drawer.menu.DrawerMenuOptions
import app.xodos2.ui.drawer.menu.DrawerScriptEditor
import app.xodos2.ui.prefs.AppPrefs
import app.xodos2.ui.runtime.TerminalSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import app.xodos2.ui.runtime.NativeInstallCoordinator

// ----------------------------------------------------------------
// Helper: build the correct installation script for any distro
// ----------------------------------------------------------------
private fun buildDesktopInstallScript(distro: String, envName: String): String {
    val cleanDistro = distro.lowercase().trim()
    
    // 1. Resolve base graphical dependencies securely
    val (managerCmd, baseDeps) = when {
        cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") || cleanDistro.contains("kali") || cleanDistro.contains("trisquel") -> {
            Pair(
                "apt update && apt install -y",
                "mesa-utils xwayland libvulkan-dev mesa-vulkan-drivers libgl1-mesa-dri libglx-mesa0 libegl-mesa0 vulkan-tools dbus-x11"
            )
        }
        cleanDistro.contains("arch") || cleanDistro.contains("manjaro") || cleanDistro.contains("artix") -> {
            Pair(
                "pacman -Syu --noconfirm --needed",
                "mesa-utils xorg-xwayland vulkan-devel mesa vulkan-tools dbus"
            )
        }
        cleanDistro.contains("fedora") || cleanDistro.contains("almalinux") || cleanDistro.contains("rocky") -> {
            Pair(
                "dnf clean all && dnf install -y",
                "mesa-utils xorg-x11-server-Xwayland vulkan-loader-devel mesa-dri-drivers vulkan-tools dbus-x11"
            )
        }
        cleanDistro.contains("alpine") -> {
            Pair(
                "apk update && apk add",
                "mesa-utils xwayland vulkan-loader mesa-dri-gallium vulkan-tools dbus"
            )
        }
        cleanDistro.contains("void") -> {
            Pair(
                "xbps-install -Su && xbps-install -y",
                "mesa-utils xwayland vulkan-loader mesa-dri vulkan-tools dbus"
            )
        }
        cleanDistro.contains("opensuse") -> {
            Pair(
                "zypper refresh && zypper install -y",
                "mesa-utils xorg-x11-server-Xwayland vulkan-devel mesa-dri-drivers vulkan-tools dbus-1-x11"
            )
        }
        else -> { // Fallback safely to Debian-style names
            Pair(
                "apt update && apt install -y",
                "mesa-utils xwayland libvulkan-dev mesa-vulkan-drivers libgl1-mesa-dri libglx-mesa0 libegl-mesa0 vulkan-tools dbus-x11"
            )
        }
    }

    // 2. Resolve Audio Subsystem to prevent PipeWire vs PulseAudio conflicts
    val isModernDE = envName == "GNOME" || envName == "KDE Plasma"
    val audioDeps = if (isModernDE) {
        when {
            cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") || cleanDistro.contains("kali") -> 
                "pipewire pipewire-pulse wireplumber pavucontrol"
            cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> 
                "pipewire-pulse wireplumber pavucontrol"
            cleanDistro.contains("fedora") || cleanDistro.contains("almalinux") -> 
                "pipewire-pulseaudio wireplumber pavucontrol"
            cleanDistro.contains("alpine") -> 
                "pipewire pipewire-pulse wireplumber pavucontrol"
            cleanDistro.contains("void") -> 
                "pipewire wireplumber pavucontrol"
            cleanDistro.contains("opensuse") -> 
                "pipewire-pulseaudio wireplumber pavucontrol"
            else -> "pipewire pipewire-pulse pavucontrol"
        }
    } else {
        "pulseaudio pavucontrol"
    }

    // 3. Resolve Desktop Environment specific package strings safely per distro
    val desktopPackages = when (envName) {
        "XFCE Desktop" -> when {
            cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "xfce4 xfce4-goodies"
            cleanDistro.contains("alpine") -> "xfce4 xfce4-terminal"
            else -> "xfce4 xfce4-goodies"
        }
        "LXQt Desktop" -> when {
            cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "lxqt lxqt-themes featherpad"
            cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") -> "lxqt openbox"
            else -> "lxqt"
        }
        "KDE Plasma" -> when {
            cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "plasma-desktop kde-applications"
            cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") -> "kde-plasma-desktop"
            cleanDistro.contains("fedora") -> "@kde-desktop-environment"
            else -> "plasma-desktop"
        }
        "GNOME" -> when {
            cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "gnome gnome-tweaks"
            cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") -> "gnome-core"
            cleanDistro.contains("fedora") -> "@gnome-desktop"
            else -> "gnome"
        }
        "MATE" -> when {
            cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "mate mate-extra"
            else -> "mate-desktop-environment"
        }
        "Cinnamon" -> when {
            cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "cinnamon nemo"
            else -> "cinnamon-desktop-environment"
        }
        else -> ""
    }

    return "$managerCmd $desktopPackages $baseDeps $audioDeps\n" +
           "export PULSE_SERVER=127.0.0.1\n" +
           "echo 'Installation completed!'\n"
}

// ----------------------------------------------------------------
// ArchDrawerPage composable
// ----------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArchDrawerPage(
    archX11ScriptEditorOpen: Boolean,
    onArchX11ScriptEditorOpenChange: (Boolean) -> Unit,
    onEnterArchX11Desktop: () -> Unit,
    prefs: SharedPreferences,
    drawerState: DrawerState,
    scope: CoroutineScope,
    terminalFontKey: String,
    terminalSessionState: TerminalSessionController.State,
    launcherDefault: String,
    desktopVulkanMode: String,
    desktopOpenGLMode: String,
    mouseMode: Int,
    resolutionPercent: Int,
    scalePercent: Int,
    waylandScriptEditorOpen: Boolean,
    onWaylandScriptEditorOpenChange: (Boolean) -> Unit,
    onEnterWaylandDesktop: () -> Unit,
    onEnterTerminal: () -> Unit,
    onLauncherDefaultSelect: (String) -> Unit,
    onDesktopVulkanSelect: (String) -> Unit,
    onDesktopOpenGLSelect: (String) -> Unit,
    onTerminalFontSelectLabel: (String) -> Unit,
    onTerminalSessionStateChange: (TerminalSessionController.State) -> Unit,
    onMouseModeSelectLabel: (String) -> Unit,
    onResolutionPercentSelectLabel: (String) -> Unit,
    onScalePercentSelectLabel: (String) -> Unit,
    vulkanOptions: List<String>,
    openGLOptions: List<String>,
    hasArchRootfs: Boolean = true,
    onContainerManagerClick: () -> Unit,
    onRequestKeyboard: () -> Unit = {},
    onExecuteCommand: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val containerDisplayName = NativeInstallCoordinator.getContainerDisplayName(context, 1)

    // Current container distro type
    val distroId = NativeInstallCoordinator.getContainerDistro(context, 1) ?: "linux"

    // ===================== Commands state =====================
    var showCommandsDialog by remember { mutableStateOf(false) }
    var showAddEditDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editText by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf<Int?>(null) }
    val savedCommands = remember { mutableStateListOf<String>() }

    // ===================== DE Script Editor State =====================
    var editingDeName by remember { mutableStateOf<String?>(null) }
    var deScriptText by remember { mutableStateOf("") }

    LaunchedEffect(showCommandsDialog) {
        if (showCommandsDialog) {
            savedCommands.clear()
            savedCommands.addAll(AppPrefs.loadCommands(prefs))
        }
    }

    fun persistCommands() {
        AppPrefs.saveCommands(prefs, savedCommands.toList())
    }

    // ===================== Desktop environment list =====================
    val desktopEnvNames = remember {
        listOf("XFCE Desktop", "LXQt Desktop", "KDE Plasma", "GNOME", "MATE", "Cinnamon")
    }

    // ===================== Existing UI =====================
    if (!hasArchRootfs) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Linux distro not installed.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Container Manager",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch { drawerState.close() }
                        onContainerManagerClick()
                    }
                    .padding(vertical = 12.dp)
            )
        }
        return
    }

    val terminalFontLabel = remember(terminalFontKey) {
        ShellFonts.options.find { it.id == terminalFontKey }?.label
            ?: ShellFonts.options.firstOrNull()?.label
            ?: terminalFontKey
    }
    val terminalSessionLabel = remember(terminalSessionState.activeSessionId) {
        TerminalSessionIds.sessionPickerLine(terminalSessionState.activeSessionId, context)
    }
    val mouseModeLabel = remember(mouseMode) {
        if (mouseMode == MOUSE_MODE_TABLET) "Tablet" else "Touchpad"
    }
    val resolutionLabel = remember(resolutionPercent) { "${resolutionPercent.coerceIn(10, 100)}%" }
    val scaleLabel = remember(scalePercent) { "${scalePercent.coerceIn(100, 1000)}%" }
    val launcherMenuLabel = remember(launcherDefault) { AppPrefs.launcherPrefToMenuLabel(launcherDefault) }

    DrawerMenu(
        title = containerDisplayName,
        labels = DrawerMenuLabels(
            launcherDefaultLabel = launcherMenuLabel,
            desktopVulkanLabel = desktopVulkanMode,
            desktopOpenGLLabel = desktopOpenGLMode,
            terminalFontLabel = terminalFontLabel,
            terminalSessionLabel = terminalSessionLabel,
            mouseModeLabel = mouseModeLabel,
            resolutionPercentLabel = resolutionLabel,
            scalePercentLabel = scaleLabel,
        ),
        options = DrawerMenuOptions(
            launcherDefaultOptions = listOf(
                AppPrefs.LAUNCHER_MENU_WAYLAND,
                AppPrefs.LAUNCHER_MENU_TERMINAL,
                AppPrefs.LAUNCHER_MENU_X11,
            ),
            desktopVulkanOptions = vulkanOptions,
            desktopOpenGLOptions = openGLOptions,
            terminalFontOptions = ShellFonts.options.map { it.label },
            terminalSessionOptions = buildList {
                add(TerminalSessionIds.sessionPickerLine(TerminalSessionIds.FIRST_TERMINAL, context))
                terminalSessionState.sessionIds
                    .sorted()
                    .filter { it != TerminalSessionIds.FIRST_TERMINAL }
                    .forEach { add(TerminalSessionIds.sessionPickerLine(it, context)) }
                add("New session")
                add("Close current session")
            },
            mouseModeOptions = listOf("Touchpad", "Tablet"),
            resolutionPercentOptions = (10..100 step 10).map { "${it}%" },
            scalePercentOptions = (100..1000 step 100).map { "${it}%" },
        ),
        actions = DrawerMenuActions(
            onDesktopClick = {
                scope.launch { drawerState.close() }
                onEnterWaylandDesktop()
            },
            onDesktopLongPress = {
                onWaylandScriptEditorOpenChange(true)
            },
            onDebianDesktopClick = {
                scope.launch { drawerState.close() }
                onEnterArchX11Desktop()
            },
            onDebianDesktopLongPress = {
                onArchX11ScriptEditorOpenChange(true)
            },
            onTerminalClick = {
                scope.launch { drawerState.close() }
                onTerminalSessionStateChange(terminalSessionState.copy(activeSessionId = TerminalSessionIds.ARCH_TERMINAL))
                onEnterTerminal()
            },
            onViewClick = { scope.launch { drawerState.close() } },
            onAppearanceClick = { scope.launch { drawerState.close() } },
            onSessionClick = { scope.launch { drawerState.close() } },
            onKeyboardClick = {
                onRequestKeyboard()
                scope.launch { drawerState.close() }
            },
            onLauncherDefaultSelect = onLauncherDefaultSelect,
            onDesktopVulkanSelect = onDesktopVulkanSelect,
            onDesktopOpenGLSelect = onDesktopOpenGLSelect,
            onTerminalFontSelect = onTerminalFontSelectLabel,
            onTerminalSessionSelect = { label ->
                val next = when (label) {
                    "New session" ->
                        TerminalSessionController.addNewInteractiveSession(terminalSessionState, TerminalSessionIds.NS_ARCH)
                    "Close current session" ->
                        TerminalSessionController.closeCurrentSession(terminalSessionState)
                    else ->
                        TerminalSessionController.selectFromPickerLine(terminalSessionState, label)
                }
                onTerminalSessionStateChange(next)
            },
            onMouseModeSelect = onMouseModeSelectLabel,
            onResolutionPercentSelect = onResolutionPercentSelectLabel,
            onScalePercentSelect = onScalePercentSelectLabel,
            onCloseDrawerRequest = { scope.launch { drawerState.close() } },
        ),
        showDebianDesktop = true,
        extraContent = {
            ArchExtraContent(
                prefs = prefs,
                waylandScriptEditorOpen = waylandScriptEditorOpen,
                onWaylandScriptEditorOpenChange = onWaylandScriptEditorOpenChange,
                hasArchRootfs = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            DrawerExpandableSection(title = "X11 Scripts", defaultExpanded = false) {
                if (archX11ScriptEditorOpen) {
                    DrawerScriptEditor(
                        title = "X11 startup script",
                        initialText = AppPrefs.readArchX11DesktopStartupScript(prefs),
                        onSave = {
                            AppPrefs.writeArchX11DesktopStartupScript(prefs, it)
                            onArchX11ScriptEditorOpenChange(false)
                        },
                    )
                } else {
                    Text(
                        text = "Edit Arch X11 startup script",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onArchX11ScriptEditorOpenChange(true) }
                            .padding(vertical = 12.dp, horizontal = 12.dp)
                    )
                }
            }

            // ===================== Install Desktop Section with Script Modification Features =====================
            DrawerExpandableSection(title = "Install Desktop", defaultExpanded = false) {
                desktopEnvNames.forEach { name ->
                    val prefKey = "custom_install_script_${distroId}_${name.replace(" ", "_")}"
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    scope.launch { drawerState.close() }
                                    // Load custom script from preference XML, fallback to hardcoded default template
                                    val script = prefs.getString(prefKey, null) ?: buildDesktopInstallScript(distroId, name)
                                    onExecuteCommand(script)
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp)
                        )
                        IconButton(
                            onClick = {
                                editingDeName = name
                                deScriptText = prefs.getString(prefKey, null) ?: buildDesktopInstallScript(distroId, name)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Startup Script Package Setup",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Commands",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCommandsDialog = true }
                    .padding(vertical = 12.dp, horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Container Manager",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch { drawerState.close() }
                        onContainerManagerClick()
                    }
                    .padding(vertical = 12.dp, horizontal = 12.dp)
            )
        }
    )

    // ===================== Desktop Environment Script Editor Dialog =====================
    if (editingDeName != null) {
        val targetDe = editingDeName!!
        val prefKey = "custom_install_script_${distroId}_${targetDe.replace(" ", "_")}"

        AlertDialog(
            onDismissRequest = { editingDeName = null },
            title = { Text("Edit Setup Script: $targetDe") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = deScriptText,
                        onValueChange = { deScriptText = it },
                        label = { Text("Installation Sequence (.sh)") },
                        singleLine = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 320.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        prefs.edit().putString(prefKey, deScriptText.trim()).apply()
                        editingDeName = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            // Reset back to dynamic default package composition layout rules
                            deScriptText = buildDesktopInstallScript(distroId, targetDe)
                        }
                    ) {
                        Text("Reset", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { editingDeName = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    // ===================== Commands dialogs =====================
    if (showCommandsDialog) {
        AlertDialog(
            onDismissRequest = { showCommandsDialog = false },
            title = { Text("Saved Commands") },
            text = {
                Column {
                    TextButton(onClick = {
                        editingIndex = null
                        editText = ""
                        showAddEditDialog = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add command")
                    }
                    Spacer(Modifier.height(8.dp))

                    if (savedCommands.isEmpty()) {
                        Text(
                            "No saved commands.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn {
                            items(savedCommands.size) { index ->
                                val cmd = savedCommands[index]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                onExecuteCommand(cmd)
                                                showCommandsDialog = false
                                            },
                                            onLongClick = {
                                                showDeleteConfirm = index
                                            }
                                        )
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        cmd,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    IconButton(onClick = {
                                        editingIndex = index
                                        editText = cmd
                                        showAddEditDialog = true
                                    }) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Edit",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCommandsDialog = false }) { Text("Close") }
            }
        )
    }

    if (showAddEditDialog) {
        AlertDialog(
            onDismissRequest = { showAddEditDialog = false },
            title = { Text(if (editingIndex == null) "Add command" else "Edit command") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    label = { Text("Shell command") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val cmd = editText.trim()
                    if (cmd.isNotEmpty()) {
                        if (editingIndex != null) {
                            savedCommands[editingIndex!!] = cmd
                        } else {
                            savedCommands.add(cmd)
                        }
                        persistCommands()
                    }
                    showAddEditDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddEditDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteConfirm != null) {
        val idx = showDeleteConfirm!!
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete command?") },
            text = { Text("Remove \"${savedCommands[idx]}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    savedCommands.removeAt(idx)
                    persistCommands()
                    showDeleteConfirm = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ArchExtraContent(
    prefs: SharedPreferences,
    waylandScriptEditorOpen: Boolean,
    onWaylandScriptEditorOpenChange: (Boolean) -> Unit,
    hasArchRootfs: Boolean
) {
    DrawerExpandableSection(title = "Scripts", defaultExpanded = false) {
        if (waylandScriptEditorOpen) {
            DrawerScriptEditor(
                title = "Wayland desktop startup script",
                initialText = prefs.getString("desktop_startup_script", "") ?: "",
                onSave = {
                    prefs.edit().putString("desktop_startup_script", it).apply()
                    onWaylandScriptEditorOpenChange(false)
                },
            )
        } else {
            Text(
                text = "Edit Wayland startup script",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onWaylandScriptEditorOpenChange(true) }
                    .padding(vertical = 12.dp, horizontal = 12.dp),
            )
        }
    }
}
