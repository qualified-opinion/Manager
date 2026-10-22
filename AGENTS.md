# AGENTS.md: VaultLock developer and agent guidelines

**Start here:** read [docs/plan/ROADMAP.md](docs/plan/ROADMAP.md). It says what is done, what is next and how work is run. Each batch has a handoff pack in `docs/plan/handoffs/`.

## Overview
VaultLock (`com.janreins.vaultlock`, repo `qualified-opinion/Manager`) is a privacy-first, 100% offline Android password manager and TOTP authenticator built with Kotlin, Jetpack Compose, Material 3 and Room. It is Jan's personal app, sideloaded from signed GitHub release APKs.

## Strict offline integrity policy
- VaultLock is designed as a zero-trust, 100% offline application.
- **NEVER** add `android.permission.INTERNET` or any network dependencies or libraries.
- No network communication, cloud sync or external telemetry is permitted.
- Bundled data (for example a passphrase wordlist) ships inside the APK; it is never downloaded.

## Build and test commands (what CI runs)
```bash
./gradlew testDebugUnitTest
./gradlew lintDebug lintRelease
./gradlew assembleDebug assembleRelease
.github/scripts/check-apk-offline.sh app/build/outputs/apk/debug/*.apk app/build/outputs/apk/release/*.apk
```

## Protected components (do not modify unless explicitly requested)
Unless the batch's handoff pack names them and Jan has given the go for that batch, do **NOT** modify:
- **Cryptography and security:** `CryptoManager`, PBKDF2/AES-GCM encryption routines, `TotpHelper`, `BiometricHelper`, `SessionManager`, `SecurityPreferences`.
- **Database and persistence:** `VaultRepository`, `VaultDatabase` or Room schema migrations. Any migration needs a migration test and a committed schema.
- **Authentication and unlock UI:** master password unlock screens, biometric prompts, or ViewModel unlock flows.
- **Permissions:** no network or unnecessary system permissions.

## Process
- Work on a feature branch and open one PR per batch. Never push to `main`, force-push, tag or create GitHub Releases; Jan publishes releases himself.
- Every batch PR updates the status table in `docs/plan/ROADMAP.md` (status and PR number; the merge sha is filled in after merge).
- A PR is reviewed with a different engine than the one that wrote it.
- Merge only after review approval, green CI and Jan's OK, as a squash pinned to the approved head.
