# VaultLock: review and fix plan (18 Oct 2026)

**Status 20 Oct 2026:** roadmap PRs 1–3 are done (#23 and #24 via PR #34, and PR #21 merged). The rest is paused until Jan says to resume.

Checked against `main` at `e0cfb42` (PR #20 merged). It replaces the earlier to-do list, `vaultlock-todo-2026-10-15.md`. This was a static review: I read the code and the CI logs but couldn't build here. Three of the new findings I re-checked by hand: auto-lock "Immediately", the restart lockout and the password-change dates. The rest come from one careful read-through.

## In plain terms

The app is in good shape. All 23 issues from the October review are fixed or deliberately parked, and nothing found today can lose your vault or crash the main screens.

The most urgent problem is in the automated checks, not the app:

- The check that is meant to prove the app never asks for internet access has silently done nothing on every run. It calls a tool the GitHub machine doesn't have, and the step still shows as passed.
- A test keeps failing at random, so PRs go red for no real reason. The only open PR, #21, is red for exactly that reason.

After those come a few small real bugs, mostly about auto-lock and the clipboard. Then come the passphrase generator and some clean-up.

## Findings

| ID | Priority | What goes wrong for you | Where |
| --- | --- | --- | --- |
| N-1 | P0 | The "app never uses the internet" check in CI never runs. The log says `rg: command not found` and the step still passes. That includes the build behind the v1.1.0 release. | `.github/workflows/android-ci.yml`: "Check APK stays offline" and the release job (PR #21 copies it) |
| N-2 | P1 | One backup test fails at random. It went past a 60 s time limit on #20 and on #21. Likely cause, not yet proven: the test machine runs low on memory, because no memory size is set for the tests. The logs show only a one-line error, so it can't be proven from here. | `BackupPickerFlowTest`; `app/build.gradle.kts` (no test settings) |
| N-3 | P1 | Auto-lock set to "Immediately when app is backgrounded" never locks when you're idle. Left on screen, the vault stays open longer than with the 2-minute default. | `VaultViewModel.startInactivityTimer` / `onAppForegrounded` (`timeoutMillis > 0`); `SettingsScreen.AutoLockPickerDialog` |
| N-4 | P2 | On Android 8–11, copying a password and pressing Back to reach the browser can empty the clipboard before you paste. | `VaultViewModel.onCleared` → `clearOwnedClipboard` |
| N-5 | P2 | The phone keyboard can learn what you type in Notes, Username and URL, and may suggest it in other apps. Notes may hold security answers. | `AddEditEntryScreen` notes, username and URL fields |
| N-6 | P3 | After a wrong-password lockout has already ended, every phone restart makes you wait the full delay again (up to 60 s) until you next unlock. This was introduced by #20. | `SecurityPreferences.getLockoutRemainingMillis` (protected) |
| N-7 | P3 | Changing the master password resets every entry's "last updated" date, so the list order shuffles and later exports carry the wrong dates. | `VaultRepository.reEncryptAll` → `toEntity` sets `updatedAt = now` (protected) |
| N-8 | P3 | The idle timer still uses the phone's clock, which F-11 fixed only for the unlock lockout. Setting the clock back while the vault is open delays auto-lock. Leaving the app still locks it. | `SessionManager` (`System.currentTimeMillis`) (protected) |
| N-9 | P2 | Only needed for Google Play: there is no `.aab` bundle, and Play requires one. The GitHub APK is fine for sideloading. | CI and README release steps |
| N-10 | P3 | The README says the clipboard is cleared "on vault lock"; it isn't when the lock comes from leaving the app (by design). REVIEW.md is a stale 9 Oct note. CI shows deprecation warnings for its Node 20 actions. | `README.md`, `REVIEW.md`, workflow action versions |
| F-19, F-20 | P2 | Generator strength labels are optimistic (`Qwertyuiop123456!` = "Very strong"), and there's no passphrase mode. | `PasswordGenerator.kt`, `PasswordGeneratorScreen.kt` |
| F-03 | P3 | Only matters for a phone that ran an Aug 2026 test build. You confirmed none did. | `VaultDatabase.kt` migration (protected) |
| F-13, F-18 | not planned | Format change with no current risk (F-13); theoretical with one window (F-18). | (see REVIEW-2026-10.md) |

Already fine, and checked again: no internet permission or network libraries in the code, Android backups off, screenshots and the recents thumbnail blocked, nothing secret written to logs, #18–#20 fixes correct, and backup export/import safe across locking. targetSdk 36 meets Google Play's current rule.

## PR roadmap

On GitHub: tracking issue [#22](https://github.com/qualified-opinion/Manager/issues/22); roadmap PRs 1, 2, 4–12 are issues #23–#33 in order (PR 3 is the existing PR #21; PR 13 has no issue).

| # | Title | Fixes | Size | Risk | Protected? | Order |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Make the offline check actually run | N-1 | S | Low | No | First |
| 2 | Make the CI tests reliable | N-2 | M | Low (tests/build only) | No | After 1 |
| 3 | Finish PR #21 (signed test build button) | N-1 in #21, red CI | S | Low | No | After 1 and 2 |
| 4 | "Immediately" auto-lock also locks when idle | N-3 | S | Low | Lock flow | Any time |
| 5 | Keep a copied password when pressing Back | N-4 | S | Low | No | Any time |
| 6 | Stop the keyboard learning notes, usernames and URLs | N-5 | M | Low | No | Any time |
| 7 | Don't repeat a finished lockout after a restart | N-6 | S | Low | Yes | Any time |
| 8 | Keep entry dates when changing the master password | N-7 | S | Low | Yes | Any time |
| 9 | Auto-lock idle timer on the uptime clock | N-8 | S | Low | Yes | After 4 (same code) |
| 10 | Passphrase generator and honest strength labels | F-19, F-20 | L | Medium | No | After 1–2 |
| 11 | Docs and CI housekeeping | N-10 | S | Low | No | Last |
| 12 | Google Play bundle (optional) | N-9 | S | Low | No | Only if publishing on Play |
| 13 | Encrypt legacy plaintext titles (optional) | F-03 | M | Medium | Yes | Only if ever needed |

PRs 7, 8 and 9 change protected components, so each needs your go-ahead, the same as before.

## For you (Jan)

- On a phone: set auto-lock to 30 s and type a long note (F-10), copy a password, switch apps, paste, and check it's gone after about 10 s.
- After PR 1 merges, re-run the checks on main once. That proves the current app truly has no internet permission.
