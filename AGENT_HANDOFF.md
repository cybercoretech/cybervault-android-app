# 🔐 CyberVault for Android: agent handoff

The state of the project for the next agent (or person) picking it up. Keep it current: update it in the same PR as any phase or significant change.

## 📍 Repository state (2026-10-20)

- **Repo:** `cybercoretech/cybervault-android-app`, local checkout `~/Devspace/Android/CyberVault` on Blackbox.
- **v0.1 (first PR):** a working app, tests, CI, release workflow, Pages site, `install.sh` and F-Droid metadata. **Not tagged or released yet.**
- **Depends on unmerged PRs:** `rust/Cargo.toml` pins `cybervault` and `keysmith` to **exact commits** on their PR branches (cybervault#1 `feat/holodrive-bridge`, keysmith#1 `feat/passphrase-api`). Those PRs add the `lib` targets and the `cli` feature this app needs. Once they're merged and tagged `v0.2.0`, switch both deps to `tag = "v0.2.0"`, run `cargo update -p cybervault -p keysmith`, rebuild and retest.
- **Release key:** `~/Vaults/android/cybervault-release.jks` + `cybervault-release.env` (0600). Certificate SHA-256 `9dfa3699b793ad16af999b2fbe99b4b13ee26f8014705cc661521ee2b15e52cb`, pinned in `docs/install.sh`, the README, SECURITY.md and the site. **Never regenerate it:** installed apps can only update with this key. GitHub secrets: `CYBERVAULT_RELEASE_KEYSTORE_BASE64` (base64 of the .jks), `CYBERVAULT_RELEASE_STORE_PASSWORD`, `CYBERVAULT_RELEASE_KEY_ALIAS`, `CYBERVAULT_RELEASE_KEY_PASSWORD`.

## ✅ Strict resume checklist

1. Read this file, then `README.md` and `docs/THREAT_MODEL.md`.
2. `git status`, `git log --oneline -5`, open PRs (`gh pr list -R cybercoretech/cybervault-android-app`).
3. Build and test before changing anything:
   ```sh
   (cd rust && cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test)
   ./gradlew assembleDebug testDebugUnitTest lint assembleRelease
   ```
4. **Emulator only, pinned:** use the AVD `CyberVault_Test_API_36` (`ANDROID_AVD_HOME=~/.config/.android/avd`, `-port 5554`), and pass `-s emulator-5554` to **every** adb call. The user's real phone is often plugged in too. Don't use `Blackbox_API_36`; it has an unknown lock-screen PIN. Run `python3 tools/emulator_smoke.py`, and `CV_RELEASE=1 …` for the signed, minified build.
5. Branch from `main`, one PR per milestone, **never stacked**. The user merges.
6. Before committing, `git config user.email` must be `19244308+darkstardevx@users.noreply.github.com` (cybercoretech org repos use the darkstardevx identity since the cybercore-tech account suspension).
7. **Docs in the same PR:** README (emoji headers), this file, `docs/THREAT_MODEL.md` if the security model changed, the Wiki page `~/Wiki/src/crypto/cybervault-android.md`, and the vault note `~/Vaults/blackbox-notes/Projects/CyberVault.md`.
8. **CI:** in the cybercoretech org, push and PR events sometimes don't trigger Actions. If no run shows up, use `gh workflow run Android -R cybercoretech/cybervault-android-app --ref <branch>`.

## 🧭 Decisions already made

- **One app, named CyberVault, with Keysmith inside.** No separate Keysmith app; the generator logic stays in the `keysmith` crate. The names Cipherkeep and Hexvault were rejected (taken).
- **CyberAuth stays separate** (2FA/TOTP), so second factors don't live in the password vault.
- **No crypto in Kotlin.** Everything goes through the uniffi bridge over the desktop crates. The vault format stays `CVL1`.
- **No INTERNET permission, ever**, without the user's explicit decision. CI enforces it.
- **v0.1 scope:** a local vault with export/import. **0.2:** Autofill + biometric quick-unlock (which wraps the master password in Keystore, never replaces it). **Later:** sync via Holodrive; F-Droid (the own repo `cybercoretech/fdroid` first, then the main catalog with reproducible builds).
- The minimum master password is 10 characters (phone only; the desktop CLI has none).

## 🏗️ Architecture

```
app/ (Kotlin, Compose M3)
  MainActivity   FLAG_SECURE (release), ProcessLifecycle → auto-lock
  VaultModel     state flow; all Rust calls on Dispatchers.Default; clipboard; export/import (SAF)
  VaultApp       Setup / Unlock / Vault · Keysmith · Settings tabs, edit dialog, generator
  VaultTheme     fixed CYBERGRID palette + JetBrains Mono; Strength bands
rust/ (cybervault-mobile, uniffi 0.32.2)
  Vault object   create/unlock/lock/entries/reveal/add/update/remove/change_password/replace_with
  functions      inspect_vault_file, generate_password/passphrase, entropy helpers
  examples/inspect.rs   desktop-code reader used by the smoke test
docs/            Pages site (shared kit styles.css/app.js, contact block), install.sh, screenshots, threat model
fastlane/        F-Droid/store metadata (changelog file name = versionCode)
```

## ⚠️ Invariants and gotchas

- **Bindings from a host build:** the shipped `.so` files are stripped (`strip = true`), which removes uniffi metadata, so `uniffiBindings` builds the host library to generate the Kotlin.
- **`android = true` and `android_cleaner = true` in `rust/uniffi.toml`:** without them lint fails on `java.lang.ref.Cleaner` (API 33; minSdk is 30).
- **Don't add `panic = "abort"`** to the release profile: uniffi turns panics into Kotlin exceptions; abort would crash the app.
- **Proguard keeps** JNA and `dev.cybercore.cybervault.core.**`. The `CV_RELEASE=1` smoke run is what proves R8 didn't break the bridge.
- **Mutations save through a copy** (`Vault::mutate`), so memory never runs ahead of disk when a save fails.
- **Clipboard clearing** only wipes a clip flagged sensitive, never something the user copied afterwards.
- **`versionCode` = X·10⁶ + Y·10³ + Z** from the tag; the fastlane changelog file uses the same number.

## 🖥️ Machine context

- SDK `~/android` (`local.properties` is gitignored), NDK `27.2.12479018`, build-tools 36.0.0, `cargo-ndk` 4.1.2, Rust targets aarch64/x86_64-linux-android.
- `CARGO_TARGET_DIR` for the bridge is `rust/target` (set by Gradle), separate from the global `~/.cargo-target`.

## 📜 PR history

- *(pending)* #1: v0.1, the first app.
