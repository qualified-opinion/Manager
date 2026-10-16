# VaultLock

VaultLock is a privacy-first, fully offline personal password manager for Android. Built with Modern Android Development (MAD) practices using Kotlin, Jetpack Compose, Material 3, and Room.

---

## 🔒 Security Model

VaultLock uses local encryption to protect credentials stored on-device:

- **Key Derivation (PBKDF2):** Master Key derived using `PBKDF2WithHmacSHA256` with **600,000 iterations** and a 256-bit key length. The count is stored with each vault's credentials; vaults set up by earlier builds keep 150,000 until their next master password change, which upgrades them to 600,000.
- **AES-256-GCM Encryption:** Sensitive fields (titles, usernames, passwords, notes, TOTP secrets) are individually encrypted with `AES/GCM/NoPadding` using a cryptographically secure random 12-byte IV for every write operation.
- **Biometric Unlock:** Android `BiometricPrompt` with Hardware KeyStore wrapping to securely preserve and retrieve the master session key without compromising security. Biometric re-enrollment is required when device biometric settings change or keystore credentials are invalidated.
- **Unlock Throttling & Rate Limiting:** Consecutive failed master-password attempts trigger exponential backoff delays (1s, 2s, 4s, 8s, 16s, 32s, capped at 60s) persisted in `EncryptedSharedPreferences` to mitigate brute-force attacks.
- **Memory Hardening & Session Wiping:** Session lock clears active key references, zeroizes temporary key byte arrays, and invokes `Destroyable.destroy()` where supported. Note that due to JVM/Android garbage collection and immutable `SecretKeySpec` internal fields, full key zeroization in managed memory is best-effort and immutable copies may persist until collected by GC.
- **100% Offline Architecture:** VaultLock does not declare `android.permission.INTERNET`. Zero network dependencies, zero cloud sync, and zero external telemetry.

---

## ✨ Features (v1.1)

- **2FA Authenticator (TOTP):** Raw Base32 secrets and `otpauth://totp` URIs (RFC 6238), with SHA1/SHA256/SHA512, 6–8 digits, custom periods (15–120 seconds), and live countdowns.
- **Duplicate Password Warnings:** In-app visual warnings flagging entries reusing passwords across different services.
- **Encrypted Exports:** Save an encrypted file directly through Android's document picker. Imports are limited to 16 MiB and add entries to the current vault. A 2FA secret the app can't read is moved into that entry's notes so the entry can still be edited.
- **Biometric Authentication:** Hardware-backed fingerprint / face unlock.
- **Auto-Lock Timer:** Automatic locking on inactivity or app backgrounding.
- **Clipboard Masking:** Copies are flagged as sensitive on every Android version, so keyboards and clipboard previews hide them, with clipboard clearing after 10 seconds (backed by a system alarm, so it still happens if Android closes the app; it may run a little late while the phone is idle) and on vault lock. A clip copied later by another app is kept whenever Android lets VaultLock see it; on Android 10 and newer, VaultLock can't see the clipboard from the background, so something copied in another app within those 10 seconds may also be cleared.

---

## 🛠️ Building & releases

### Prerequisites
- JDK 17 or higher
- Android SDK 36 (Build Tools 36.0.0)

### Run Unit Tests
```bash
./gradlew test
```

### Build and check a debug APK
```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```
Android's default debug keystore is generated automatically; no repository keystore is needed.
The Android CI workflow runs unit tests and debug/release lint, builds both APK variants,
checks both packaged manifests for network permissions, and uploads the debug APK and reports.
Debug builds use `com.janreins.vaultlock.debug` and can coexist with production. Their generated
signing key is not stable across CI runners; use synthetic data in debug builds.

### Portable backups and recovery
New exports use a separate backup password (minimum 12 characters). The v2 binary envelope
contains a random 32-byte salt, PBKDF2-HMAC-SHA256 work factor (600,000 iterations), random
12-byte IV, and AES-256-GCM ciphertext/tag. The complete header is authenticated. Imports
reject unsupported versions, excessive work factors, files over 16 MiB, and malformed entries
before the single batch insert. Restore encrypts entries with the destination vault's current key.

To recover after reinstalling or on another device: set up a destination vault with any master
password, open Settings → Restore, select the export, and enter its **backup password**. Keep
that password separately; there is no reset or recovery service. Exact duplicate contents are
skipped by default; existing entries are never replaced. Creation/update dates are preserved.
Choose a local document provider if you do not want a provider to sync the file to the cloud.

Old v1 exports still require the original installation key. Select the explicit **Legacy file**
option only for those files. A master-password change cannot make old exports portable:
create a new v2 export while you can still unlock the original vault.

Before any signing/app-ID transition, install an update signed by the **existing** key that can
export v2, then verify recovery in a separate installation. Never uninstall a legacy vault just
to resolve an APK signature mismatch. If its original signing key is unavailable, preserve that
installation and recover credentials while it is accessible; this release cannot bypass Android
signature checks or reconstruct missing legacy backup salts.

### GitHub production releases
A `v*` tag publishes a signed **release** APK plus its SHA-256 checksum only after CI succeeds.
Configure repository Actions secrets using your existing production keystore:

- `RELEASE_KEYSTORE_BASE64`: Base64-encoded keystore file.
- `STORE_PASSWORD` and `KEY_PASSWORD`: keystore/key passwords.
- `KEY_ALIAS`: signing alias (defaults to `upload`).

Missing credentials fail the release job; it never substitutes a debug signature. Keep an offline
copy of the production signing key and increment `versionCode` for every production update.
Do not generate a replacement key for an existing production installation.

### Build Release APK
```bash
./gradlew assembleRelease
```
Release signing is optional: set `KEYSTORE_PATH` to an existing keystore file and provide non-blank
`STORE_PASSWORD` and `KEY_PASSWORD`. `KEY_ALIAS` is optional and defaults to `upload`.
Without these, the release APK is unsigned. Keep the same production signing key for updates; never commit it.

---

## 📄 License
This project is released under the MIT License.
