package app.xodos2.ui.drawer.pages

import android.content.SharedPreferences
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xodos2.ui.prefs.AppPrefs
import org.json.JSONObject

//private data class SavedCommand(val title: String, val command: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommandsSection(
    prefs: SharedPreferences,
    onExecuteCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCommandsDialog by remember { mutableStateOf(false) }
    var showAddEditDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editText by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf<Int?>(null) }
    val savedCommands = remember { mutableStateListOf<SavedCommand>() }

    LaunchedEffect(showCommandsDialog) {
        if (showCommandsDialog) {
            savedCommands.clear()
            AppPrefs.loadCommands(prefs).forEach { json ->
                try {
                    val obj = JSONObject(json)
                    savedCommands.add(
                        SavedCommand(
                            title = obj.optString("title", ""),
                            command = obj.optString("command", "")
                        )
                    )
                } catch (_: Exception) {
                    savedCommands.add(SavedCommand(title = "", command = json))
                }
            }
        }
    }

    fun persistCommands() {
        val jsonList = savedCommands.map {
            JSONObject().apply {
                put("title", it.title)
                put("command", it.command)
            }.toString()
        }
        AppPrefs.saveCommands(prefs, jsonList)
    }

    Text(
        text = "Commands",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .clickable { showCommandsDialog = true }
            .padding(vertical = 12.dp, horizontal = 12.dp)
    )

    if (showCommandsDialog) {
        AlertDialog(
            onDismissRequest = { showCommandsDialog = false },
            title = { Text("Saved Commands") },
            text = {
                Column {
                    TextButton(onClick = {
                        editingIndex = null
                        editTitle = ""
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
                            items(items = savedCommands, key = { it.command }) { cmd ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                onExecuteCommand(cmd.command)
                                                showCommandsDialog = false
                                            },
                                            onLongClick = {
                                                showDeleteConfirm = savedCommands.indexOf(cmd)
                                            }
                                        )
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = cmd.title.ifBlank { cmd.command },
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    IconButton(onClick = {
                                        editingIndex = savedCommands.indexOf(cmd)
                                        editTitle = cmd.title
                                        editText = cmd.command
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
                Column {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        label = { Text("Shell command") },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = editTitle.trim()
                    val command = editText.trim()
                    if (command.isNotEmpty()) {
                        if (editingIndex != null) {
                            savedCommands[editingIndex!!] = SavedCommand(title, command)
                        } else {
                            savedCommands.add(SavedCommand(title, command))
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
            text = { Text("Remove \"${savedCommands[idx].title.ifBlank { savedCommands[idx].command }}\"?") },
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