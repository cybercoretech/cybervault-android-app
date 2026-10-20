package dev.cybercore.cybervault

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cybercore.cybervault.core.EntryInfo
import dev.cybercore.cybervault.core.Vault
import dev.cybercore.cybervault.core.VaultException
import dev.cybercore.cybervault.core.inspectVaultFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

enum class Phase { Setup, Locked, Unlocked }

data class VaultState(
    val phase: Phase = Phase.Locked,
    val entries: List<EntryInfo> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** An imported file waiting for its password before it replaces the vault. */
    val pendingImport: Boolean = false,
)

/**
 * Owns the vault. Every call into Rust runs off the main thread: unlocking
 * and saving run Argon2id, which takes about a second on a phone.
 */
class VaultModel(app: Application) : AndroidViewModel(app) {
    private val vaultFile = File(app.filesDir, "vault/vault.cvlt")
    private val importFile = File(app.cacheDir, "import.cvlt")
    private val vault = Vault(vaultFile.absolutePath)
    private val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var clearClipJob: Job? = null
    private var backgroundedAt: Long? = null

    private val _state = MutableStateFlow(VaultState(phase = if (vault.exists()) Phase.Locked else Phase.Setup))
    val state: StateFlow<VaultState> = _state.asStateFlow()

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                block()
            } catch (e: VaultException) {
                _state.update { it.copy(error = describe(e)) }
            } catch (e: java.io.IOException) {
                _state.update { it.copy(error = "Storage error: ${e.message}") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun refresh() {
        val entries = withContext(Dispatchers.Default) { vault.entries() }
        _state.update { it.copy(phase = Phase.Unlocked, entries = entries) }
    }

    fun create(password: String, confirm: String) {
        if (password != confirm) {
            _state.update { it.copy(error = "The two passwords don't match.") }
            return
        }
        run {
            withContext(Dispatchers.Default) { vault.create(password) }
            refresh()
        }
    }

    fun unlock(password: String) = run {
        withContext(Dispatchers.Default) { vault.unlock(password) }
        refresh()
    }

    fun lock() {
        vault.lock()
        clearClipboardNow()
        _state.update { VaultState(phase = Phase.Locked) }
    }

    fun reveal(label: String): String? = try {
        vault.reveal(label)
    } catch (e: VaultException) {
        _state.update { it.copy(error = describe(e)) }
        null
    }

    fun save(originalLabel: String?, label: String, secret: String, note: String, onDone: () -> Unit) = run {
        val cleanNote = note.trim().ifEmpty { null }
        withContext(Dispatchers.Default) {
            if (originalLabel == null) {
                vault.add(label.trim(), secret, cleanNote, LocalDate.now().toString())
            } else {
                vault.update(originalLabel, secret, cleanNote)
            }
        }
        refresh()
        _state.update { it.copy(notice = if (originalLabel == null) "Saved \"${label.trim()}\"" else "Updated \"$label\"") }
        onDone()
    }

    fun remove(label: String) = run {
        withContext(Dispatchers.Default) { vault.remove(label) }
        refresh()
        _state.update { it.copy(notice = "Deleted \"$label\"") }
    }

    fun changePassword(current: String, new: String, confirm: String, onDone: () -> Unit) {
        if (new != confirm) {
            _state.update { it.copy(error = "The new passwords don't match.") }
            return
        }
        run {
            withContext(Dispatchers.Default) { vault.changePassword(current, new) }
            _state.update { it.copy(notice = "Master password changed") }
            onDone()
        }
    }

    /** Copy the encrypted vault file as-is. It stays encrypted under the master password. */
    fun export(target: Uri) = run {
        withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            resolver.openOutputStream(target, "wt")?.use { out -> vaultFile.inputStream().use { it.copyTo(out) } }
                ?: throw java.io.IOException("couldn't open the destination")
        }
        _state.update { it.copy(notice = "Exported. The file is still encrypted with your master password.") }
    }

    /** Stage a picked file, then ask for its password before anything is replaced. */
    fun stageImport(source: Uri) = run {
        withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val input = resolver.openInputStream(source) ?: throw java.io.IOException("couldn't open the file")
            input.use { inp ->
                importFile.outputStream().use { out ->
                    // A real vault is small; stop as soon as a file is clearly not one.
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = inp.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_IMPORT_BYTES) {
                            throw java.io.IOException("that file is too large to be a vault")
                        }
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
        _state.update { it.copy(pendingImport = true) }
    }

    fun confirmImport(password: String) = run {
        val count = withContext(Dispatchers.Default) {
            if (vault.exists()) {
                vault.replaceWith(importFile.absolutePath, password)
            } else {
                // First run: vet it, then move it into place.
                val n = inspectVaultFile(importFile.absolutePath, password)
                vaultFile.parentFile?.mkdirs()
                importFile.copyTo(vaultFile, overwrite = true)
                n
            }
        }
        importFile.delete()
        withContext(Dispatchers.Default) { vault.unlock(password) }
        refresh()
        _state.update { it.copy(pendingImport = false, notice = "Imported $count entries") }
    }

    fun cancelImport() {
        importFile.delete()
        _state.update { it.copy(pendingImport = false) }
    }

    fun dismissMessages() = _state.update { it.copy(error = null, notice = null) }

    /** Copy a secret, flagged sensitive so keyboards and previews hide it, and wipe it later. */
    fun copySecret(label: String, secret: String) {
        val clip = ClipData.newPlainText(label, secret)
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            } else {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
        clearClipJob?.cancel()
        clearClipJob = viewModelScope.launch {
            delay(CLIPBOARD_CLEAR_MS)
            clearClipboardNow()
        }
        _state.update { it.copy(notice = "Copied. The clipboard clears in ${CLIPBOARD_CLEAR_MS / 1000} s") }
    }

    private fun clearClipboardNow() {
        clearClipJob?.cancel()
        clearClipJob = null
        val current = clipboard.primaryClipDescription ?: return
        // Only wipe our own clip, never something the user copied since.
        if (current.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true ||
            current.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
        ) {
            clipboard.clearPrimaryClip()
        }
    }

    fun onBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    /** Lock when the app comes back after sitting in the background too long. */
    fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (vault.isUnlocked() && SystemClock.elapsedRealtime() - since >= AUTO_LOCK_MS) lock()
    }

    override fun onCleared() {
        vault.lock()
        vault.destroy()
    }

    companion object {
        const val CLIPBOARD_CLEAR_MS = 30_000L
        const val AUTO_LOCK_MS = 60_000L
        const val MAX_IMPORT_BYTES = 16L * 1024 * 1024

        fun describe(e: VaultException): String = when (e) {
            is VaultException.WrongPassword -> "Wrong password, or the vault file was altered."
            is VaultException.NotAVault -> "That isn't a CyberVault file."
            is VaultException.AlreadyExists -> "A vault already exists."
            is VaultException.Missing -> "No vault yet."
            is VaultException.Locked -> "The vault is locked."
            is VaultException.DuplicateLabel -> "An entry named \"${e.label}\" already exists."
            is VaultException.UnknownLabel -> "No entry named \"${e.label}\"."
            is VaultException.Invalid -> e.detail.replaceFirstChar { it.uppercase() } + "."
            is VaultException.Io -> "Storage error: ${e.detail}"
        }
    }
}
