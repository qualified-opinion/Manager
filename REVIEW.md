Historical note from 9 Oct 2026; see [docs/plan/ROADMAP.md](docs/plan/ROADMAP.md) for current status.
# VaultLock review and repair status — 2026-10-09

Starting point: `fb8514ecb40db0488e63d1c72e9509b79a78b2d0`.
This change addresses portable recovery, asynchronous session safety and release identity.

## Changes

- New password-protected v2 backup envelope: authenticated version/KDF/salt/IV header,
  bounded inputs, destination-key re-encryption, strict all-or-nothing payload validation,
  preserved timestamps and optional exact-content deduplication.
- Explicit v1 legacy import; old files still require their original key. No silent fallback.
- Session generations prevent password/biometric authentication and key rotation from
  reopening a session after a lock. Security operations share a process-wide authentication gate.
- A shared repository mutation gate spans row rotation, recovery-journal staging and credential
  promotion. Queued mutations reject a changed session; save is single-flight in the ViewModel.
- Legacy preference migration verifies a synchronous destination commit before clearing source.
- Debug application ID is separate. Tagged releases require a stable production signing key;
  CI compiles/lints both variants and verifies both manifests remain offline.

## Validation

The starting commit's GitHub Actions run passed tests, lint and debug compilation. For this
change, local Gradle execution was blocked before compilation by network access to
`services.gradle.org`. The PR's GitHub Actions checks are authoritative for compilation,
unit tests and lint. New regressions cover backup portability, tamper/wrong-password/version/
size/KDF rejection, atomic import, legacy import, duplicate handling and stale authentication.

## Remaining device acceptance checks

- Test Android 26/29/33/36, actual biometric hardware and biometric enrollment invalidation.
- Exercise process death during rotation/import, background locking, picker cancellation,
  unavailable/failing document providers and clipboard clearing on real Android versions.
- Configure signing secrets using the correct existing production key and verify an in-place
  update; signing credentials are not created or replaced by this PR.
- Verify fresh-install recovery using synthetic credentials and a separately stored backup password.

No Internet permission, network clients, cloud sync or telemetry were added. This is a focused
repair, not an independent security certification. Legacy exports and historical debug signatures
cannot be retroactively repaired.
