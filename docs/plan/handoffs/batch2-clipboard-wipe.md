# Batch 2: clipboard wipe reliability

- **Repo:** `qualified-opinion/Manager`
- **Branch:** `fix/clipboard-wipe-reliability`, from `main` after B1 merges
- **Engine:** Codex CLI builds; Claude Code reviews.
- **Source items:** K-1, K-2 from [the Codex review](../reviews/codex-review-2026-10-22.md), plus the clipboard test gaps listed there.
- **Size target:** about 250 to 400 changed lines, including tests.
- **Protected components:** none. The clipboard code in `VaultViewModel` is not an unlock flow. Don't touch unlock, lock-session, crypto or DB code.

## Goal
A copied secret always gets cleared once it is ours to clear, even if the alarm arrives late or the ViewModel was recreated, without ever clearing something the user copied from another app.

## Problems
- **K-1:** `ClipboardWipe.onAlarm` skips the wipe for good when Android hides the clipboard and the alarm is more than 60 s late (`BLIND_CLEAR_LIMIT_MS`). Android may deliver inexact alarms much later. Nothing retries.
- **K-2:** `VaultViewModel.clearOwnedClipboard` cancels the shared alarm before checking ownership. A new ViewModel (after Back, per #36) has null ownership fields, so on lock or unlock it cancels the old wipe and clears nothing.

## Scope
1. Keep a small pending-wipe record (marker label and deadline only, never the secret) in plain app `SharedPreferences`, written when a copy schedules a wipe and removed when a wipe clears, finds a different clip, or is replaced by a newer copy. Put it in `ClipboardWipe.kt` so the receiver and the ViewModel share it.
2. K-2: `clearOwnedClipboard` cancels the alarm only when this instance's marker matches the pending record, or after it has actually cleared. A fresh ViewModel with no ownership must leave the alarm and record alone.
3. K-1: foreground recovery. When the app comes to the foreground (the existing `onAppForegrounded` path) or a `VaultViewModel` is created, if a pending record exists and its deadline has passed, read the clipboard (readable in the foreground) and clear it only if the label matches the marker; then drop the record. Keep the 60 s blind limit for the background alarm path.
4. Tests in `app/src/test/java/com/janreins/vaultlock/ui/VaultClipboardTest.kt` (or a new test file next to it), each failing on current `main`:
   - Late alarm with hidden ownership skips, then the next foreground clears our clip.
   - Late alarm, then the user copied something else: the foreground does not clear it.
   - Copy, ViewModel cleared, new ViewModel locks: the pending alarm and record survive and the wipe still happens.
   - A newer copy replaces the record.
5. `README.md`: one sentence saying a late background wipe is finished the next time you open the app.
6. `docs/plan/ROADMAP.md`: set B2 to "In review" with the PR number.

## Out of scope
- Exact alarms or the `SCHEDULE_EXACT_ALARM` permission. No new permissions at all.
- Changing the 10 s delay or the "keep the copy when leaving the app" behaviour.
- Lock, session, unlock or crypto code.

## Acceptance criteria
- CI green with the `AGENTS.md` commands, including the offline check.
- The new tests fail on `main` `e1f18bd` and pass on the branch (say how you showed this).
- No secret value is ever written to `SharedPreferences` or logs (the reviewer greps for it).
- `AndroidManifest.xml` unchanged.
- **Jan's phone check (after merge):** on Android 10 or newer, copy a password, press Back, wait about a minute with the screen off, open VaultLock: the clipboard is empty. Then copy something in another app and repeat: that clip stays.

## Risks
- Clearing a clip that isn't ours. The label match is the guard; never clear blind in the foreground.
- Robolectric clipboard behaviour differs from devices. That's why the phone check is listed.

## Process
Open one PR. Update the ROADMAP status table. Don't merge; wait for Kiln's review and Jan's OK.
