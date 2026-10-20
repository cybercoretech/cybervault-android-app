//! uniffi bridge for the CyberVault Android app.
//!
//! The vault format and crypto come straight from the `cybervault` crate and
//! the generators from `keysmith`, so a vault written on the phone opens in
//! the desktop TUI and vice versa. Kotlin never sees a key, only the master
//! password it hands over and the secrets it asks for.

use cybervault::vault::{self, Entry, VaultData};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};
use zeroize::Zeroizing;

uniffi::setup_scaffolding!();

/// Shortest master password the app accepts for a new vault or a change.
/// The desktop CLI has no minimum; the phone is easier to shoulder-surf and
/// lose, so it asks for a little more.
pub const MIN_MASTER_PASSWORD_LEN: u32 = 10;

const MAX_LABEL_BYTES: usize = 128;
const MAX_SECRET_BYTES: usize = 16 * 1024;
const MAX_NOTE_BYTES: usize = 4 * 1024;

#[derive(Debug, uniffi::Error)]
pub enum VaultError {
    /// Decryption failed: wrong password, or the file was altered.
    WrongPassword,
    /// Not a CyberVault file (bad magic bytes or truncated).
    NotAVault {
        detail: String,
    },
    AlreadyExists,
    Missing,
    Locked,
    DuplicateLabel {
        label: String,
    },
    UnknownLabel {
        label: String,
    },
    Invalid {
        detail: String,
    },
    Io {
        detail: String,
    },
}

impl std::fmt::Display for VaultError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::WrongPassword => write!(f, "wrong password, or the vault file was altered"),
            Self::NotAVault { detail } => write!(f, "not a CyberVault file: {detail}"),
            Self::AlreadyExists => write!(f, "a vault already exists here"),
            Self::Missing => write!(f, "no vault has been created yet"),
            Self::Locked => write!(f, "the vault is locked"),
            Self::DuplicateLabel { label } => {
                write!(f, "an entry named \"{label}\" already exists")
            }
            Self::UnknownLabel { label } => write!(f, "no entry named \"{label}\""),
            Self::Invalid { detail } => write!(f, "{detail}"),
            Self::Io { detail } => write!(f, "storage error: {detail}"),
        }
    }
}

impl std::error::Error for VaultError {}

/// `vault::load` reports everything as strings; sort them into the cases the
/// UI treats differently.
fn classify_load_error(message: String) -> VaultError {
    if message.contains("wrong password") {
        VaultError::WrongPassword
    } else if message.contains("bad magic") || message.contains("too short") {
        VaultError::NotAVault { detail: message }
    } else {
        VaultError::Io { detail: message }
    }
}

/// One row of the entry list. Never carries the secret.
#[derive(Debug, Clone, uniffi::Record)]
pub struct EntryInfo {
    pub label: String,
    pub created: String,
    pub note: Option<String>,
}

struct Unlocked {
    password: Zeroizing<String>,
    data: VaultData,
}

/// A vault file plus, while unlocked, its decrypted contents in memory.
/// Every change is written back immediately, so nothing is lost if Android
/// kills the process.
#[derive(uniffi::Object)]
pub struct Vault {
    path: PathBuf,
    state: Mutex<Option<Unlocked>>,
}

fn check_master_password(password: &str) -> Result<(), VaultError> {
    if (password.chars().count() as u32) < MIN_MASTER_PASSWORD_LEN {
        return Err(VaultError::Invalid {
            detail: format!(
                "the master password needs at least {MIN_MASTER_PASSWORD_LEN} characters"
            ),
        });
    }
    Ok(())
}

fn check_entry(label: &str, secret: &str, note: Option<&str>) -> Result<(), VaultError> {
    let invalid = |detail: &str| {
        Err(VaultError::Invalid {
            detail: detail.to_string(),
        })
    };
    if label.trim().is_empty() {
        return invalid("the label can't be empty");
    }
    if label.len() > MAX_LABEL_BYTES {
        return invalid("the label is too long (128 bytes max)");
    }
    if label.chars().any(char::is_control) {
        return invalid("the label can't contain control characters");
    }
    if secret.is_empty() {
        return invalid("the secret can't be empty");
    }
    if secret.len() > MAX_SECRET_BYTES {
        return invalid("the secret is too long (16 KiB max)");
    }
    if note.is_some_and(|n| n.len() > MAX_NOTE_BYTES) {
        return invalid("the note is too long (4 KiB max)");
    }
    Ok(())
}

/// Save through a sibling temp file and rename it into place, so a crash or
/// a full disk mid-write leaves the previous vault intact instead of a
/// truncated one.
fn save_atomically(path: &Path, password: &str, data: &VaultData) -> Result<(), VaultError> {
    let tmp = path.with_extension("cvlt.tmp");
    vault::save(&tmp, password, data).map_err(|detail| VaultError::Io { detail })?;
    std::fs::rename(&tmp, path).map_err(|e| VaultError::Io {
        detail: e.to_string(),
    })
}

impl Vault {
    fn state(&self) -> MutexGuard<'_, Option<Unlocked>> {
        // A panic while holding the lock can't leave the data half-written
        // to disk (saves are atomic), so recover rather than poison the app.
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn mutate(
        &self,
        change: impl FnOnce(&mut VaultData) -> Result<(), VaultError>,
    ) -> Result<(), VaultError> {
        let mut guard = self.state();
        let unlocked = guard.as_mut().ok_or(VaultError::Locked)?;
        // Apply to a copy first: if the save fails, memory still matches disk.
        let mut next = VaultData {
            entries: unlocked.data.entries.clone(),
        };
        change(&mut next)?;
        save_atomically(&self.path, &unlocked.password, &next)?;
        unlocked.data = next;
        Ok(())
    }
}

#[uniffi::export]
impl Vault {
    #[uniffi::constructor]
    pub fn new(path: String) -> Arc<Self> {
        Arc::new(Self {
            path: PathBuf::from(path),
            state: Mutex::new(None),
        })
    }

    pub fn exists(&self) -> bool {
        self.path.is_file()
    }

    pub fn is_unlocked(&self) -> bool {
        self.state().is_some()
    }

    /// Create a new, empty vault and leave it unlocked.
    pub fn create(&self, password: String) -> Result<(), VaultError> {
        let password = Zeroizing::new(password);
        if self.exists() {
            return Err(VaultError::AlreadyExists);
        }
        check_master_password(&password)?;
        let data = VaultData::default();
        save_atomically(&self.path, &password, &data)?;
        *self.state() = Some(Unlocked { password, data });
        Ok(())
    }

    pub fn unlock(&self, password: String) -> Result<(), VaultError> {
        let password = Zeroizing::new(password);
        if !self.exists() {
            return Err(VaultError::Missing);
        }
        let data = vault::load(&self.path, &password).map_err(classify_load_error)?;
        *self.state() = Some(Unlocked { password, data });
        Ok(())
    }

    /// Drop the decrypted contents and the password. `Entry` and
    /// `Zeroizing` wipe their memory on drop.
    pub fn lock(&self) {
        *self.state() = None;
    }

    pub fn entries(&self) -> Result<Vec<EntryInfo>, VaultError> {
        let guard = self.state();
        let unlocked = guard.as_ref().ok_or(VaultError::Locked)?;
        Ok(unlocked
            .data
            .entries
            .iter()
            .map(|(label, entry)| EntryInfo {
                label: label.clone(),
                created: entry.created.clone(),
                note: entry.note.clone(),
            })
            .collect())
    }

    pub fn reveal(&self, label: String) -> Result<String, VaultError> {
        let guard = self.state();
        let unlocked = guard.as_ref().ok_or(VaultError::Locked)?;
        unlocked
            .data
            .entries
            .get(&label)
            .map(|entry| entry.secret.clone())
            .ok_or(VaultError::UnknownLabel { label })
    }

    /// Add a new entry. Refuses to replace an existing label, like the
    /// desktop bridge does.
    pub fn add(
        &self,
        label: String,
        secret: String,
        note: Option<String>,
        created: String,
    ) -> Result<(), VaultError> {
        let secret = Zeroizing::new(secret);
        check_entry(&label, &secret, note.as_deref())?;
        self.mutate(|data| {
            if data.entries.contains_key(&label) {
                return Err(VaultError::DuplicateLabel {
                    label: label.clone(),
                });
            }
            data.entries.insert(
                label.clone(),
                Entry {
                    secret: secret.to_string(),
                    created,
                    note: note.filter(|n| !n.trim().is_empty()),
                },
            );
            Ok(())
        })
    }

    /// Replace an existing entry's secret and note, keeping its creation date.
    pub fn update(
        &self,
        label: String,
        secret: String,
        note: Option<String>,
    ) -> Result<(), VaultError> {
        let secret = Zeroizing::new(secret);
        check_entry(&label, &secret, note.as_deref())?;
        self.mutate(|data| {
            let entry = data
                .entries
                .get_mut(&label)
                .ok_or_else(|| VaultError::UnknownLabel {
                    label: label.clone(),
                })?;
            entry.secret = secret.to_string();
            entry.note = note.filter(|n| !n.trim().is_empty());
            Ok(())
        })
    }

    pub fn remove(&self, label: String) -> Result<(), VaultError> {
        self.mutate(|data| {
            data.entries
                .remove(&label)
                .map(|_| ())
                .ok_or_else(|| VaultError::UnknownLabel {
                    label: label.clone(),
                })
        })
    }

    /// Re-encrypt under a new master password. The current one must be
    /// re-entered even though the vault is unlocked.
    pub fn change_password(&self, current: String, new: String) -> Result<(), VaultError> {
        let current = Zeroizing::new(current);
        let new = Zeroizing::new(new);
        check_master_password(&new)?;
        let mut guard = self.state();
        let unlocked = guard.as_mut().ok_or(VaultError::Locked)?;
        if *unlocked.password != *current {
            return Err(VaultError::WrongPassword);
        }
        save_atomically(&self.path, &new, &unlocked.data)?;
        unlocked.password = new;
        Ok(())
    }

    /// Replace this vault's file with `source` (an imported copy) after
    /// proving `password` opens it. The vault is left locked.
    pub fn replace_with(&self, source: String, password: String) -> Result<u32, VaultError> {
        let password = Zeroizing::new(password);
        let count = inspect_vault_file(source.clone(), password.to_string())?;
        let tmp = self.path.with_extension("cvlt.tmp");
        std::fs::copy(&source, &tmp).map_err(|e| VaultError::Io {
            detail: e.to_string(),
        })?;
        std::fs::rename(&tmp, &self.path).map_err(|e| VaultError::Io {
            detail: e.to_string(),
        })?;
        self.lock();
        Ok(count)
    }
}

/// Open `path` read-only with `password` and report how many entries it
/// holds, without keeping anything. Used to vet a file before importing it.
#[uniffi::export]
pub fn inspect_vault_file(path: String, password: String) -> Result<u32, VaultError> {
    let password = Zeroizing::new(password);
    let data = vault::load(Path::new(&path), &password).map_err(classify_load_error)?;
    Ok(data.entries.len() as u32)
}

#[uniffi::export]
pub fn min_master_password_len() -> u32 {
    MIN_MASTER_PASSWORD_LEN
}

/// Keysmith random password. `length` 8–128.
#[uniffi::export]
pub fn generate_password(
    length: u32,
    lower: bool,
    upper: bool,
    digits: bool,
    symbols: bool,
    exclude_ambiguous: bool,
) -> Result<String, VaultError> {
    if !(8..=128).contains(&length) {
        return Err(VaultError::Invalid {
            detail: "length must be between 8 and 128".into(),
        });
    }
    let charset = keysmith::password::Charset {
        lower,
        upper,
        digits,
        symbols,
        exclude_ambiguous,
    };
    keysmith::password::generate(length as usize, &charset).ok_or(VaultError::Invalid {
        detail: "pick at least one character set".into(),
    })
}

#[uniffi::export]
pub fn password_entropy_bits(
    length: u32,
    lower: bool,
    upper: bool,
    digits: bool,
    symbols: bool,
    exclude_ambiguous: bool,
) -> f64 {
    let charset = keysmith::password::Charset {
        lower,
        upper,
        digits,
        symbols,
        exclude_ambiguous,
    };
    keysmith::password::entropy_bits(length as usize, &charset)
}

/// Keysmith `passphrase/v1`: 3–12 EFF Large words.
#[uniffi::export]
pub fn generate_passphrase(
    words: u32,
    separator: String,
    capitalize: bool,
) -> Result<String, VaultError> {
    keysmith::generate_passphrase(&keysmith::PassphraseOptions {
        words: words as usize,
        separator,
        capitalize,
    })
    .map_err(|detail| VaultError::Invalid {
        detail: detail.to_string(),
    })
}

#[uniffi::export]
pub fn passphrase_entropy_bits(words: u32) -> f64 {
    keysmith::passphrase::entropy_bits(words as usize, keysmith::wordlist::words().len())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fresh() -> (tempfile::TempDir, Arc<Vault>) {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("vault").join("vault.cvlt");
        (dir, Vault::new(path.to_string_lossy().into_owned()))
    }

    const PW: &str = "correct horse battery";

    #[test]
    fn create_add_lock_unlock_round_trip() {
        let (_dir, v) = fresh();
        v.create(PW.into()).unwrap();
        v.add(
            "github".into(),
            "hunter2-hunter2".into(),
            Some("work".into()),
            "2026-10-20".into(),
        )
        .unwrap();
        v.lock();
        assert!(matches!(v.entries(), Err(VaultError::Locked)));
        v.unlock(PW.into()).unwrap();
        assert_eq!(v.reveal("github".into()).unwrap(), "hunter2-hunter2");
        let list = v.entries().unwrap();
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].note.as_deref(), Some("work"));
    }

    #[test]
    fn file_is_readable_by_the_desktop_vault_code() {
        let (_dir, v) = fresh();
        v.create(PW.into()).unwrap();
        v.add("a".into(), "s3cret!".into(), None, "2026-10-20".into())
            .unwrap();
        let data = vault::load(&v.path, PW).unwrap();
        assert_eq!(data.entries["a"].secret, "s3cret!");
    }

    #[test]
    fn wrong_password_is_reported_as_such() {
        let (_dir, v) = fresh();
        v.create(PW.into()).unwrap();
        v.lock();
        assert!(matches!(
            v.unlock("nope nope nope".into()),
            Err(VaultError::WrongPassword)
        ));
    }

    #[test]
    fn refuses_short_master_password_duplicates_and_recreate() {
        let (_dir, v) = fresh();
        assert!(matches!(
            v.create("short".into()),
            Err(VaultError::Invalid { .. })
        ));
        v.create(PW.into()).unwrap();
        assert!(matches!(
            v.create(PW.into()),
            Err(VaultError::AlreadyExists)
        ));
        v.add("x".into(), "1".into(), None, "d".into()).unwrap();
        assert!(matches!(
            v.add("x".into(), "2".into(), None, "d".into()),
            Err(VaultError::DuplicateLabel { .. })
        ));
        assert_eq!(v.reveal("x".into()).unwrap(), "1");
    }

    #[test]
    fn update_remove_and_change_password() {
        let (_dir, v) = fresh();
        v.create(PW.into()).unwrap();
        v.add("x".into(), "1".into(), None, "2026-01-01".into())
            .unwrap();
        v.update("x".into(), "2".into(), Some(" ".into())).unwrap();
        assert_eq!(v.reveal("x".into()).unwrap(), "2");
        assert_eq!(v.entries().unwrap()[0].created, "2026-01-01");
        assert_eq!(v.entries().unwrap()[0].note, None);
        assert!(matches!(
            v.change_password("wrong-current-pw".into(), "another long password".into()),
            Err(VaultError::WrongPassword)
        ));
        v.change_password(PW.into(), "another long password".into())
            .unwrap();
        v.remove("x".into()).unwrap();
        v.lock();
        assert!(matches!(
            v.unlock(PW.into()),
            Err(VaultError::WrongPassword)
        ));
        v.unlock("another long password".into()).unwrap();
        assert!(v.entries().unwrap().is_empty());
    }

    #[test]
    fn rejects_bad_entries() {
        let (_dir, v) = fresh();
        v.create(PW.into()).unwrap();
        for (label, secret) in [("", "s"), ("ok", ""), ("bad\nlabel", "s")] {
            assert!(matches!(
                v.add(label.into(), secret.into(), None, "d".into()),
                Err(VaultError::Invalid { .. })
            ));
        }
        let long_label = "l".repeat(MAX_LABEL_BYTES + 1);
        assert!(v.add(long_label, "s".into(), None, "d".into()).is_err());
    }

    #[test]
    fn import_vets_the_file_and_replaces_atomically() {
        let (_dir, v) = fresh();
        v.create(PW.into()).unwrap();
        let (_dir2, other) = fresh();
        other.create("the other password".into()).unwrap();
        other
            .add("one".into(), "1".into(), None, "d".into())
            .unwrap();
        other
            .add("two".into(), "2".into(), None, "d".into())
            .unwrap();
        let src = other.path.to_string_lossy().into_owned();
        assert!(matches!(
            v.replace_with(src.clone(), PW.into()),
            Err(VaultError::WrongPassword)
        ));
        assert!(
            v.is_unlocked(),
            "a failed import leaves the current vault alone"
        );
        assert_eq!(v.replace_with(src, "the other password".into()).unwrap(), 2);
        assert!(!v.is_unlocked());
        v.unlock("the other password".into()).unwrap();
        assert_eq!(v.entries().unwrap().len(), 2);
    }

    #[test]
    fn non_vault_files_are_identified() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("junk");
        std::fs::write(&path, b"definitely not a vault, just some bytes here").unwrap();
        assert!(matches!(
            inspect_vault_file(path.to_string_lossy().into_owned(), PW.into()),
            Err(VaultError::NotAVault { .. })
        ));
    }

    #[test]
    fn generators_follow_keysmith_rules() {
        let pw = generate_password(24, true, true, true, false, true).unwrap();
        assert_eq!(pw.chars().count(), 24);
        assert!(pw.chars().all(|c| c.is_ascii_alphanumeric()));
        assert!(generate_password(4, true, true, true, true, false).is_err());
        assert!(generate_password(16, false, false, false, false, false).is_err());
        let phrase = generate_passphrase(6, "-".into(), false).unwrap();
        assert_eq!(phrase.split('-').count(), 6);
        assert!(generate_passphrase(2, "-".into(), false).is_err());
        assert!(passphrase_entropy_bits(6) > 77.0);
    }
}
