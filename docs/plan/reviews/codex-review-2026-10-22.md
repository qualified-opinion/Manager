# Codex review of main e1f18bd (22 Oct 2026)

**Planner preface (Kiln, 22 Oct 2026).** This is a read-only Codex check of `main` at `e1f18bd`, focused on what merged after the Claude review of 18 Oct (`e0cfb42`): #34, #21 and #35 to #38. Codex changed no files and did not run Gradle; CI on `e1f18bd` is green. The text below is the engine output unchanged, except that file links were rewritten to repo-relative paths.

Planner decisions on this review:
- F-03 is closed as a doc note. Jan confirmed on 22 Oct 2026 that no August 2026 test build was ever installed on his phone, so no v1 vault exists to migrate. Batches 8 and 9 below are cut.
- F-13 and F-18 stay deferred (see the ROADMAP cut list). Batches 10 and 11 below are cut.
- K-3 is downgraded to P3. The repo has a `KEY_ALIAS` secret set, so the manual signed build works today; only the docs and the workflow disagree.
- K-1 and K-2 are accepted as P2. K-2 is newly reachable through #36.
- The wordlist ships as a plain text asset, checked by a count and checksum test, and counts as data outside the 450-line target.
- The optional `.aab` (N-9) stays out unless Jan decides to publish on Google Play.

---

Reviewed `e1f18bd`, including `git log -p e0cfb42..HEAD`. **No files changed; Gradle and tests were not run.** Verdicts below are from source inspection, not CI or device verification.

**1. Merged fixes**

N-1…N-6 mapping follows the items in your prompt; their original review text is not committed.

| Item / PR | Verdict | Evidence and limitations |
|---|---|---|
| **N-1 / #34** — offline check, test reliability | **Correct implementation.** Script now rejects missing APKs, failed `aapt`, unreadable output and network permissions. Tests receive 2 GiB; backup document IO is injectable. No production regression identified. | [check-apk-offline.sh:4](../../../.github/scripts/check-apk-offline.sh#L4), lines 13–24; [app/build.gradle.kts:59](../../../app/build.gradle.kts#L59); [BackupPickerFlowTest.kt:87](../../../app/src/test/java/com/janreins/vaultlock/ui/BackupPickerFlowTest.kt#L87). This improves determinism; repository IO still uses real threads, explicitly acknowledged at test lines 92–96. |
| **N-2 / #21** — manual signed artifact | **Works with all four secrets configured.** Restricted to manual runs on `main`, waits for tests, verifies signing/offline permissions, uploads APK/checksum for seven days. **New configuration regression: K-3 below.** | [android-ci.yml:74](../../../.github/workflows/android-ci.yml#L74), lines 109–135. Certificate logging identifies the signer; it does not compare against an expected production fingerprint. |
| **N-3 / #35** — “Immediately” idle lock | **Fixed**, subject to existing N-8. Zero now maps to 30 seconds in both timer and foreground checks. Background stops still lock immediately. | [VaultViewModel.kt:188](../../../app/src/main/java/com/janreins/vaultlock/ui/VaultViewModel.kt#L188), lines 203–206, 243–255; [SettingsScreen.kt:252](../../../app/src/main/java/com/janreins/vaultlock/ui/screens/SettingsScreen.kt#L252). Five-second polling means approximately **30–35 seconds** under normal scheduling. No separate regression identified. |
| **N-4 / #36** — clipboard survives Back | **Fixes premature clearing.** `onCleared()` removes the Handler callback while preserving the alarm. **Expiry remains unreliable; K-1/K-2 below.** | [VaultViewModel.kt:766](../../../app/src/main/java/com/janreins/vaultlock/ui/VaultViewModel.kt#L766); regression coverage at [VaultClipboardTest.kt:177](../../../app/src/test/java/com/janreins/vaultlock/ui/VaultClipboardTest.kt#L177). |
| **N-5 / #37** — IME learning | **Correct for cooperative keyboards.** Adds `IME_FLAG_NO_PERSONALIZED_LEARNING` after the field populates its options, preserving existing flags. Username, URL and notes are wrapped. | [NoPersonalizedLearning.kt:14](../../../app/src/main/java/com/janreins/vaultlock/ui/NoPersonalizedLearning.kt#L14), lines 19–25; [AddEditEntryScreen.kt:253](../../../app/src/main/java/com/janreins/vaultlock/ui/screens/AddEditEntryScreen.kt#L253), lines 382 and 405. This requests IME behavior; it cannot enforce keyboard compliance. No implementation regression identified. |
| **N-6 / #38** — finished lockout after reboot | **Fixed once expiry has been observed.** Removes timing keys while retaining the failure count. **Not a complete fix for every elapsed lockout:** kill the process before expiry, wait, then reboot without reopening; the full delay is reapplied. This limitation is documented. | [SecurityPreferences.kt:286](../../../app/src/main/java/com/janreins/vaultlock/data/SecurityPreferences.kt#L286), lines 300–315. [UnlockThrottlingTest.kt:107](../../../app/src/test/java/com/janreins/vaultlock/UnlockThrottlingTest.kt#L107) explicitly reads expiry before simulating reboot. No new security regression identified. |

**2. Remaining reported items**

| Item | Status at HEAD |
|---|---|
| **N-7: rotation resets `updatedAt`** | **Still real.** `reEncryptAll()` invokes `toEntity()`, which assigns the current time: [VaultRepository.kt:106](../../../app/src/main/java/com/janreins/vaultlock/data/VaultRepository.kt#L106), [line 299](../../../app/src/main/java/com/janreins/vaultlock/data/VaultRepository.kt#L299). |
| **N-8: wall-clock idle timer** | **Still real.** Activity timestamps use `System.currentTimeMillis()`; both idle checks subtract wall time. Moving time backward can extend the unlocked interval: [SessionManager.kt:36](../../../app/src/main/java/com/janreins/vaultlock/crypto/SessionManager.kt#L36), line 47; [VaultViewModel.kt:194](../../../app/src/main/java/com/janreins/vaultlock/ui/VaultViewModel.kt#L194), line 253. |
| **N-9: no `.aab`** | **Still real as a distribution gap.** CI builds `assembleDebug`/`assembleRelease`; release jobs package only APKs/checksums: [android-ci.yml:51](../../../.github/workflows/android-ci.yml#L51), lines 119–133 and 180–193. This does not mean AGP cannot build a bundle. |
| **N-10: clipboard README** | **Still inaccurate.** [README.md:27](../../../README.md#L27) promises clearing on vault lock, but background lock deliberately preserves the clip ([VaultViewModel.kt:243](../../../app/src/main/java/com/janreins/vaultlock/ui/VaultViewModel.kt#L243)). Its reliable-after-process-death wording also conflicts with K-1. Sensitive flags request masking; they do not guarantee every keyboard hides content. |
| **N-10: stale review** | **Still historical, not current status.** [REVIEW.md:1](../../../REVIEW.md#L1) describes October 9 and an earlier baseline; lines 22–26 describe validation of that change. Preserve it as history and add a current status/index. |
| **N-10: Node 20 actions** | **Still real maintenance debt.** Old action majors remain at [android-ci.yml:24](../../../.github/workflows/android-ci.yml#L24), lines 27, 37 and 60, repeated in release jobs. Their upstream manifests declare Node 20: [checkout](https://raw.githubusercontent.com/actions/checkout/v4/action.yml), [setup-java](https://raw.githubusercontent.com/actions/setup-java/v4/action.yml), [setup-android](https://raw.githubusercontent.com/android-actions/setup-android/v3/action.yml), [upload-artifact](https://raw.githubusercontent.com/actions/upload-artifact/v4/action.yml). GitHub’s [migration notice](https://github.blog/changelog/2025-09-19-deprecation-of-node-20-on-github-actions-runners/) calls for Node 24 actions; declarations alone do not establish a current CI failure. |

**F-ID correction:** [REVIEW-2026-10.md:62](../../../REVIEW-2026-10.md#L62) defines **F-19 as sampling bias**, and **F-20 as strength labeling**. Missing passphrase mode is separate.

- **F-19 remains:** forced characters, combined-pool filling and shuffling remain at [PasswordGenerator.kt:62](../../../app/src/main/java/com/janreins/vaultlock/generator/PasswordGenerator.kt#L62).
- **F-20 remains:** the heuristic only detects sequences matching the entire input, then rewards length/classes. `Qwertyuiop123456!` still reaches “Very strong.” Generated values use that same heuristic: [PasswordGenerator.kt:115](../../../app/src/main/java/com/janreins/vaultlock/generator/PasswordGenerator.kt#L115), lines 123–146; [PasswordGeneratorScreen.kt:126](../../../app/src/main/java/com/janreins/vaultlock/ui/screens/PasswordGeneratorScreen.kt#L126).
- **No passphrase mode:** options contain character switches and pronounceable syllables only: [PasswordGenerator.kt:5](../../../app/src/main/java/com/janreins/vaultlock/generator/PasswordGenerator.kt#L5), lines 78–101.
- **F-03 remains:** plaintext legacy titles are copied into `encrypted_title`: [VaultDatabase.kt:64](../../../app/src/main/java/com/janreins/vaultlock/data/VaultDatabase.kt#L64).
- **F-13 remains:** empty strings bypass authentication; field encryption supplies no AAD: [CryptoManager.kt:77](../../../app/src/main/java/com/janreins/vaultlock/crypto/CryptoManager.kt#L77), lines 84–86 and 97–98.
- **F-18 remains:** save checks decryptability, not whether the loaded row changed: [VaultRepository.kt:56](../../../app/src/main/java/com/janreins/vaultlock/data/VaultRepository.kt#L56).

**3. New findings and test gaps**

- **K-1 — P2: delayed alarm can permanently abandon clipboard cleanup.** The alarm is inexact ([ClipboardWipe.kt:35](../../../app/src/main/java/com/janreins/vaultlock/ui/ClipboardWipe.kt#L35)). When ownership is hidden, delivery more than 60 seconds after its deadline skips clearing, with no retry or foreground cleanup ([lines 65–69](../../../app/src/main/java/com/janreins/vaultlock/ui/ClipboardWipe.kt#L65)). Android permits substantially later delivery, including [up to one hour on Android 12+ absent battery restrictions](https://developer.android.com/develop/background-work/services/alarms#deliver-after-time). After process death—or #36 removing the Handler—the app may never clear that secret. This predates the range but limits #36’s guarantee.

- **K-2 — P2: a fresh ViewModel can cancel another instance’s outstanding wipe.** Reproduce: copy → Back clears ViewModel → reopen/unlock/manual-lock before expiry. Ownership fields start null ([VaultViewModel.kt:114](../../../app/src/main/java/com/janreins/vaultlock/ui/VaultViewModel.kt#L114)); `clearOwnedClipboard()` unconditionally cancels the shared alarm before checking ownership ([line 780](../../../app/src/main/java/com/janreins/vaultlock/ui/VaultViewModel.kt#L780)), then clears nothing because `ownedClipboard` is null (line 793). The previous Handler is already gone. Cleanup exceptions also lose the alarm and ownership state. Newly exposed by #36’s normal Back path.

- **K-3 — P2: manual signed build rejects the documented default alias.** #21 requires nonempty `KEY_ALIAS` ([android-ci.yml:109](../../../.github/workflows/android-ci.yml#L109)), while [README.md:81](../../../README.md#L81) and [app/build.gradle.kts:31](../../../app/build.gradle.kts#L31) make it optional, defaulting to `upload`. A correctly configured default-alias keystore therefore fails only on the manual path.

Material coverage gaps:

- Clipboard tests manually invoke the receiver with readable clipboard state; no delayed/hidden-ownership, fresh-ViewModel cancellation, or real Back/process-death acceptance test: [VaultClipboardTest.kt:136](../../../app/src/test/java/com/janreins/vaultlock/ui/VaultClipboardTest.kt#L136), line 177.
- Idle tests manipulate timestamps through reflection; no clock-jump coverage: [LockLifecycleTest.kt:115](../../../app/src/test/java/com/janreins/vaultlock/ui/LockLifecycleTest.kt#L115).
- Migration coverage actually asserts plaintext in `encrypted_title`, without a successful repository decrypt/export: [VaultDatabaseMigrationTest.kt:83](../../../app/src/test/java/com/janreins/vaultlock/data/VaultDatabaseMigrationTest.kt#L83).
- Rotation tests check restored secrets but omit timestamp preservation: [MasterPasswordChangeTest.kt:122](../../../app/src/test/java/com/janreins/vaultlock/MasterPasswordChangeTest.kt#L122).
- Add offline-check failure fixtures and default-alias workflow validation. Existing IME tests verify options, not real keyboard compliance.

**4. Offline passphrase implementation**

Bundle a fixed **EFF 7,776-word list** in `app/src/main/assets/wordlists/eff_large_wordlist.txt`, with source URL, checksum and attribution/license notice. Estimate **60–65 KiB** stripped text, approximately **25–35 KiB compressed**. EFF documents this list size and approximately **12.9 bits per independently sampled word**; six words give about 77.5 bits. [EFF wordlist description](https://www.eff.org/deeplinks/2016/07/new-wordlists-random-passphrases).

Use `SecureRandom.nextInt(wordCount)`, sampling with replacement. Fixed separators/capitalization add no entropy. Load locally once; no network dependency or permission.

Expected changes:

| Files | Estimated changed lines |
|---|---:|
| New `PassphraseGenerator.kt`, wordlist loader, generator options/strength model | 150–220 |
| `PasswordGeneratorScreen.kt`, generator state/functions in `VaultViewModel.kt` | 200–280 |
| Generator/resource tests, UI integration tests | 200–280 |
| README and attribution | 20–40 |
| **Total excluding wordlist data** | **570–820** |

Plain text adds **7,776 data lines**. To keep every batch below approximately 450 textual lines, bundle a reproducible `.txt.gz` asset instead, with decompressed checksum/count/uniqueness tests and documented provenance. Count the binary asset separately and disclose its contents.

**5. Ranked batches**

Estimates include tests and documentation; none require network functionality in the app.

| Rank | Batch | Changed-line target |
|---|---|---:|
| 1 | CI/release maintenance: K-3, Node action upgrades, offline-check fixtures, N-10 docs, optional signed AAB artifact/checks | 250–400 |
| 2 | Clipboard K-1/K-2: preserve outstanding cleanup, foreground ownership recovery, delayed/denied-access tests; accurate guarantees | 250–400 |
| 3 | F-19/F-20: uniform random-password sampling, generated-strength model, conservative pronounceable labeling, embedded-sequence tests | 250–400 |
| 4 | Bundled wordlist, attribution, passphrase engine/resource tests | 250–350 + binary asset |
| 5 | Passphrase UI/state and editor integration; reuse clipboard path | 300–430 |
| 6 | **Protected lock code:** N-8 monotonic idle timing, injected-clock tests; document/test N-6’s unobserved-expiry policy | 180–300 |
| 7 | **Protected repository:** N-7 timestamp preservation and F-18 snapshot conflict detection | 250–400 |
| 8–9 | **Protected DB/recovery:** F-03 migration groundwork, then authenticated unlock-time repair/integration tests; each independently safe | 300–450 each |
| 10–11 | **Protected crypto/format:** F-13 versioned authenticated field envelope, then compatible migration/writer integration and tamper tests | 300–450 each |

For F-03, account for already-upgraded v2/v3 installations as well as v1 upgrades. Arbitrary decryption failure must never become permission to reinterpret ciphertext as plaintext.