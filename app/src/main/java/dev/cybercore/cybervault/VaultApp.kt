package dev.cybercore.cybervault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cybercore.cybervault.core.EntryInfo
import dev.cybercore.cybervault.core.VaultException
import dev.cybercore.cybervault.core.generatePassphrase
import dev.cybercore.cybervault.core.generatePassword
import dev.cybercore.cybervault.core.minMasterPasswordLen
import dev.cybercore.cybervault.core.passphraseEntropyBits
import dev.cybercore.cybervault.core.passwordEntropyBits

@Composable
fun VaultApp(model: VaultModel, state: VaultState) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error, state.notice) {
        val message = state.error ?: state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        model.dismissMessages()
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when (state.phase) {
                Phase.Setup -> SetupScreen(model, state)
                Phase.Locked -> UnlockScreen(model, state)
                Phase.Unlocked -> UnlockedScreen(model, state)
            }
            if (state.pendingImport) ImportPasswordDialog(model, state)
        }
    }
}

// ---- Shared bits ---------------------------------------------------------

@Composable
private fun Brand(subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Shield, null, tint = Neon.Cyan, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(12.dp))
        Text("CYBERVAULT", style = MaterialTheme.typography.headlineMedium, color = Neon.Cyan)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Neon.Muted)
    }
}

@Composable
private fun SecretField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (shown) "Hide" else "Show")
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun StrengthBadge(bits: Double, prefix: String = "") {
    val strength = Strength.of(bits)
    Text(
        "$prefix${strength.label} · ${bits.toInt()} bits",
        color = strength.color,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
    )
}

/** Rough entropy for a typed master password: character classes times length. */
internal fun roughEntropy(password: String): Double {
    if (password.isEmpty()) return 0.0
    var pool = 0
    if (password.any { it.isLowerCase() }) pool += 26
    if (password.any { it.isUpperCase() }) pool += 26
    if (password.any { it.isDigit() }) pool += 10
    if (password.any { !it.isLetterOrDigit() }) pool += 33
    return password.length * (Math.log(pool.coerceAtLeast(2).toDouble()) / Math.log(2.0))
}

// ---- Setup / unlock ------------------------------------------------------

@Composable
private fun SetupScreen(model: VaultModel, state: VaultState) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val min = remember { minMasterPasswordLen().toInt() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::stageImport)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Brand("Create your vault")
        Text(
            "Your master password encrypts everything on this phone. It is never stored or sent anywhere. " +
                "If you forget it, the vault cannot be recovered.",
            style = MaterialTheme.typography.bodySmall,
            color = Neon.Muted,
        )
        SecretField(password, { password = it }, "Master password", enabled = !state.busy)
        if (password.isNotEmpty()) StrengthBadge(roughEntropy(password), "ESTIMATE: ")
        SecretField(confirm, { confirm = it }, "Repeat it", enabled = !state.busy)
        Button(
            onClick = { model.create(password, confirm) },
            enabled = !state.busy && password.length >= min && confirm.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.busy) "ENCRYPTING…" else "CREATE VAULT") }
        if (password.isNotEmpty() && password.length < min) {
            Text("At least $min characters.", color = Neon.Amber, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !state.busy) {
            Icon(Icons.Filled.FileUpload, null)
            Spacer(Modifier.width(8.dp))
            Text("Import an existing vault file")
        }
        if (state.busy) CircularProgressIndicator(color = Neon.Cyan)
    }
}

@Composable
private fun UnlockScreen(model: VaultModel, state: VaultState) {
    var password by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Brand("Locked")
        SecretField(password, { password = it }, "Master password", enabled = !state.busy)
        Button(
            onClick = { model.unlock(password); password = "" },
            enabled = !state.busy && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.busy) "DECRYPTING…" else "UNLOCK") }
        if (state.busy) CircularProgressIndicator(color = Neon.Cyan)
    }
}

@Composable
private fun ImportPasswordDialog(model: VaultModel, state: VaultState) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!state.busy) model.cancelImport() },
        title = { Text("Import vault") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (state.phase == Phase.Setup) {
                        "Enter the master password of the vault you picked."
                    } else {
                        "Enter the imported vault's master password. It REPLACES the vault on this phone; export first if you need a copy."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                SecretField(password, { password = it }, "Its master password", enabled = !state.busy)
            }
        },
        confirmButton = {
            TextButton(onClick = { model.confirmImport(password) }, enabled = !state.busy && password.isNotEmpty()) {
                Text(if (state.busy) "CHECKING…" else "IMPORT")
            }
        },
        dismissButton = { TextButton(onClick = model::cancelImport, enabled = !state.busy) { Text("CANCEL") } },
    )
}

// ---- Unlocked ------------------------------------------------------------

private enum class Tab(val title: String) { Vault("VAULT"), Keysmith("KEYSMITH"), Settings("SETTINGS") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnlockedScreen(model: VaultModel, state: VaultState) {
    var tab by rememberSaveable { mutableStateOf(Tab.Vault) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(tab.title, color = Neon.Cyan) },
                actions = {
                    IconButton(onClick = model::lock) { Icon(Icons.Filled.Lock, "Lock now", tint = Neon.Pink) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Neon.Panel) {
                NavigationBarItem(tab == Tab.Vault, { tab = Tab.Vault }, { Icon(Icons.Filled.Shield, null) }, label = { Text("Vault") })
                NavigationBarItem(tab == Tab.Keysmith, { tab = Tab.Keysmith }, { Icon(Icons.Filled.Key, null) }, label = { Text("Keysmith") })
                NavigationBarItem(tab == Tab.Settings, { tab = Tab.Settings }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        },
        floatingActionButton = {
            if (tab == Tab.Vault) {
                FloatingActionButton(onClick = { editing = Editing(null, "", "", "") }, containerColor = Neon.Cyan) {
                    Icon(Icons.Filled.Add, "Add entry", tint = Neon.Bg)
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.Vault -> EntryList(model, state) { entry, secret ->
                    editing = Editing(entry.label, entry.label, secret, entry.note.orEmpty())
                }
                Tab.Keysmith -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Generator(onUse = null, onCopy = { model.copySecret("Keysmith", it) })
                }
                Tab.Settings -> SettingsScreen(model, state)
            }
        }
    }
    editing?.let { current ->
        EditDialog(model, state, current, onDismiss = { editing = null })
    }
}

private data class Editing(val original: String?, val label: String, val secret: String, val note: String)

@Composable
private fun EntryList(model: VaultModel, state: VaultState, onEdit: (EntryInfo, String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val shown = remember(state.entries, query) { filterEntries(state.entries, query) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(if (state.entries.size == 1) "Search 1 entry" else "Search ${state.entries.size} entries") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (state.entries.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.Key, null, tint = Neon.Purple, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("Empty vault", style = MaterialTheme.typography.titleMedium)
                Text("Tap + to add a login. Keysmith can generate the password.", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.label }) { entry ->
                EntryCard(
                    entry = entry,
                    expanded = open == entry.label,
                    onToggle = { open = if (open == entry.label) null else entry.label },
                    reveal = { model.reveal(entry.label) },
                    onCopy = { secret -> model.copySecret(entry.label, secret) },
                    onEdit = { secret -> onEdit(entry, secret) },
                    onDelete = { confirmDelete = entry.label },
                )
            }
        }
    }
    confirmDelete?.let { label ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"$label\"?") },
            text = { Text("This removes the entry from the vault. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = { model.remove(label); confirmDelete = null; open = null }) {
                    Text("DELETE", color = Neon.Red)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("KEEP") } },
        )
    }
}

internal fun filterEntries(entries: List<EntryInfo>, query: String): List<EntryInfo> {
    val q = query.trim()
    if (q.isEmpty()) return entries
    return entries.filter { it.label.contains(q, ignoreCase = true) || it.note?.contains(q, ignoreCase = true) == true }
}

@Composable
private fun EntryCard(
    entry: EntryInfo,
    expanded: Boolean,
    onToggle: () -> Unit,
    reveal: () -> String?,
    onCopy: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var visible by remember(expanded) { mutableStateOf<String?>(null) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Neon.Panel),
        border = BorderStroke(1.dp, if (expanded) Neon.Cyan else Neon.Line),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(entry.created, color = Neon.Muted, style = MaterialTheme.typography.labelSmall)
            }
            entry.note?.let { Text(it, color = Neon.Muted, style = MaterialTheme.typography.bodySmall, maxLines = if (expanded) 8 else 1, overflow = TextOverflow.Ellipsis) }
            if (expanded) {
                Text(
                    visible ?: "••••••••••••",
                    color = if (visible != null) Neon.Lime else Neon.Muted,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { visible = if (visible == null) reveal() else null }) {
                        Icon(if (visible == null) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, if (visible == null) "Reveal" else "Hide", tint = Neon.Cyan)
                    }
                    IconButton(onClick = { reveal()?.let(onCopy) }) { Icon(Icons.Filled.ContentCopy, "Copy secret", tint = Neon.Cyan) }
                    IconButton(onClick = { reveal()?.let(onEdit) }) { Icon(Icons.Filled.Edit, "Edit", tint = Neon.Purple) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete", tint = Neon.Red) }
                }
            }
        }
    }
}

@Composable
private fun EditDialog(model: VaultModel, state: VaultState, start: Editing, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf(start.label) }
    var secret by remember { mutableStateOf(start.secret) }
    var note by remember { mutableStateOf(start.note) }
    var showGenerator by remember { mutableStateOf(start.original == null) }
    Dialog(onDismissRequest = { if (!state.busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Neon.Bg),
            border = BorderStroke(1.dp, Neon.Cyan),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (start.original == null) "NEW ENTRY" else "EDIT ENTRY", style = MaterialTheme.typography.titleLarge, color = Neon.Cyan)
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label, e.g. github") },
                    singleLine = true,
                    enabled = start.original == null && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecretField(secret, { secret = it }, "Password / secret", enabled = !state.busy)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (username, URL…)") },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { showGenerator = !showGenerator }) {
                    Icon(Icons.Filled.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (showGenerator) "HIDE KEYSMITH" else "GENERATE WITH KEYSMITH")
                }
                if (showGenerator) Generator(onUse = { secret = it }, onCopy = null)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !state.busy) { Text("CANCEL") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { model.save(start.original, label, secret, note, onDismiss) },
                        enabled = !state.busy && label.isNotBlank() && secret.isNotEmpty(),
                    ) { Text(if (state.busy) "SAVING…" else "SAVE") }
                }
            }
        }
    }
}

// ---- Keysmith ------------------------------------------------------------

private enum class GenMode { Password, Passphrase }

@Composable
private fun Generator(onUse: ((String) -> Unit)?, onCopy: ((String) -> Unit)?) {
    var mode by rememberSaveable { mutableStateOf(GenMode.Passphrase) }
    var length by rememberSaveable { mutableFloatStateOf(24f) }
    var words by rememberSaveable { mutableFloatStateOf(6f) }
    var lower by rememberSaveable { mutableStateOf(true) }
    var upper by rememberSaveable { mutableStateOf(true) }
    var digits by rememberSaveable { mutableStateOf(true) }
    var symbols by rememberSaveable { mutableStateOf(true) }
    var noAmbiguous by rememberSaveable { mutableStateOf(false) }
    var capitalize by rememberSaveable { mutableStateOf(false) }
    var separator by rememberSaveable { mutableStateOf("-") }
    var nonce by remember { mutableStateOf(0) }

    val result = remember(mode, length.toInt(), words.toInt(), lower, upper, digits, symbols, noAmbiguous, capitalize, separator, nonce) {
        try {
            if (mode == GenMode.Password) {
                generatePassword(length.toInt().toUInt(), lower, upper, digits, symbols, noAmbiguous)
            } else {
                generatePassphrase(words.toInt().toUInt(), separator, capitalize)
            }
        } catch (e: VaultException) {
            null
        }
    }
    val bits = if (mode == GenMode.Password) {
        passwordEntropyBits(length.toInt().toUInt(), lower, upper, digits, symbols, noAmbiguous)
    } else {
        passphraseEntropyBits(words.toInt().toUInt())
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            GenMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { mode = m },
                    shape = SegmentedButtonDefaults.itemShape(i, GenMode.entries.size),
                ) { Text(m.name.uppercase()) }
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = Neon.Panel),
            border = BorderStroke(1.dp, Neon.Purple),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(result ?: "Pick at least one character set", color = if (result != null) Neon.Lime else Neon.Amber,
                    style = MaterialTheme.typography.titleMedium)
                StrengthBadge(bits)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nonce++ }) { Icon(Icons.Filled.Refresh, "Generate another", tint = Neon.Cyan) }
                    if (onCopy != null && result != null) {
                        IconButton(onClick = { onCopy(result) }) { Icon(Icons.Filled.ContentCopy, "Copy", tint = Neon.Cyan) }
                    }
                    Spacer(Modifier.weight(1f))
                    if (onUse != null && result != null) Button(onClick = { onUse(result) }) { Text("USE THIS") }
                }
            }
        }
        if (mode == GenMode.Password) {
            Text("Length: ${length.toInt()}", style = MaterialTheme.typography.labelLarge)
            Slider(value = length, onValueChange = { length = it }, valueRange = 8f..64f, steps = 55)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(lower, { lower = !lower }, { Text("a-z") })
                FilterChip(upper, { upper = !upper }, { Text("A-Z") })
                FilterChip(digits, { digits = !digits }, { Text("0-9") })
                FilterChip(symbols, { symbols = !symbols }, { Text("!@#") })
            }
            FilterChip(noAmbiguous, { noAmbiguous = !noAmbiguous }, { Text("No look-alikes (0 O 1 l I |)") })
        } else {
            Text("Words: ${words.toInt()}  ·  EFF Large wordlist", style = MaterialTheme.typography.labelLarge)
            Slider(value = words, onValueChange = { words = it }, valueRange = 3f..12f, steps = 8)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("-", ".", "_", " ").forEach { sep ->
                    FilterChip(separator == sep, { separator = sep }, { Text(if (sep == " ") "space" else sep) })
                }
                FilterChip(capitalize, { capitalize = !capitalize }, { Text("Caps") })
            }
        }
        Text("Generated on this phone by Keysmith. Nothing is sent anywhere.", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

// ---- Settings ------------------------------------------------------------

@Composable
private fun SettingsScreen(model: VaultModel, state: VaultState) {
    var changing by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(model::export)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::stageImport)
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingRow(Icons.Filled.Key, "Change master password", "Re-encrypts the vault under a new password.") { changing = true }
        SettingRow(Icons.Filled.FileDownload, "Export vault file", "Saves the encrypted file, e.g. to move it to your desktop CyberVault.") {
            exportLauncher.launch("cybervault.cvlt")
        }
        SettingRow(Icons.Filled.FileUpload, "Import vault file", "Replaces this vault with one from another device.") {
            importLauncher.launch(arrayOf("*/*"))
        }
        SettingRow(Icons.Filled.Lock, "Lock now", "Also locks itself after ${VaultModel.AUTO_LOCK_MS / 1000} s in the background.") { model.lock() }
        Spacer(Modifier.height(8.dp))
        Text(
            "CyberVault ${BuildConfig.VERSION_NAME} · same vault format as the desktop app (CVL1: Argon2id + ChaCha20-Poly1305). " +
                "No internet permission: your secrets never leave this phone unless you export them.",
            color = Neon.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (changing) ChangePasswordDialog(model, state) { changing = false }
}

@Composable
private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Neon.Panel),
        border = BorderStroke(1.dp, Neon.Line),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Neon.Cyan)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(detail, color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ChangePasswordDialog(model: VaultModel, state: VaultState, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val min = remember { minMasterPasswordLen().toInt() }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("Change master password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SecretField(current, { current = it }, "Current password", enabled = !state.busy)
                SecretField(new, { new = it }, "New password", enabled = !state.busy)
                if (new.isNotEmpty()) StrengthBadge(roughEntropy(new), "ESTIMATE: ")
                SecretField(confirm, { confirm = it }, "Repeat new password", enabled = !state.busy)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { model.changePassword(current, new, confirm, onDismiss) },
                enabled = !state.busy && current.isNotEmpty() && new.length >= min,
            ) { Text(if (state.busy) "RE-ENCRYPTING…" else "CHANGE") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("CANCEL") } },
    )
}

