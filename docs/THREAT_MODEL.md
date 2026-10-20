# CyberVault for Android: threat model

## Assets

The secrets in the vault and the master password.

## Protects against

- **A lost or stolen locked phone, or a copied vault file.** The file is ChaCha20-Poly1305 ciphertext under a key derived with Argon2id (the `cybervault` crate's parameters). Without the master password, an attacker has to brute-force Argon2id. Tampering is detected by the Poly1305 tag and reported as "wrong password, or the vault file was altered".
- **Network exfiltration.** The app has no INTERNET permission, and CI checks the release APK for it.
- **Casual exposure.** Release builds set `FLAG_SECURE` (no screenshots, recordings or recents thumbnail). Copied secrets are marked sensitive and cleared after 30 s. The vault locks after 60 s in the background.
- **Cloud backup leaks.** `allowBackup=false` and data-extraction rules exclude everything from cloud backup and device transfer.
- **Corruption on crash.** Saves go through a temp file and an atomic rename. An import is only applied after its master password checks out.

## Does not protect against

- **A compromised, unlocked phone:** root, malware with accessibility or overlay access, or a malicious keyboard reading what you type. Use a trusted keyboard; the password fields turn off autocorrect and suggestions.
- **A weak master password.** Argon2id slows guessing but can't save `password123`. The app requires 10+ characters and shows a rough strength estimate.
- **Forgetting the master password.** There is no recovery and no escrow, by design.
- **Secrets held in JVM memory.** Revealed or copied secrets pass through Kotlin `String`s, which can't be reliably zeroized. The Rust side zeroizes the password, the decrypted entries and the derived key; the app keeps Kotlin copies short-lived and drops them on lock.
- **Other apps reading the clipboard during its 30 s window.** Android 10+ limits background clipboard access, but a foreground app or an IME can still read it.
- **A malicious release.** Install only APKs whose signing certificate is `9dfa3699…b15e52cb`. Android refuses updates signed with another key.

## Out of scope for 0.1

- **Autofill and biometric unlock** come in 0.2. Biometrics will only *wrap* the master password in Android Keystore; they won't replace it.
- **Sync.** Vaults move only through explicit export and import.
- **2FA seeds.** Keep TOTP secrets in a separate authenticator such as CyberAuth, so the second factor stays independent of the vault.
