# Test plan

How Bump Beeper is tested: what runs where, who owns it, and what a human checks on a phone before a release.
Owned by **qa** (`contracts/OWNERSHIP.yml`). Each layer's owner writes and fixes its tests; qa writes the cross-cutting
app tests and, as the gate on every PR, checks that the new behaviour was actually exercised.

## The pyramid

Fast and many at the bottom, slow and few at the top. A bug is caught at the lowest layer that can see it.

| Layer | Where | Runs with | Owner | When |
|---|---|---|---|---|
| Simulated-drive scenarios (engine, driving monitor) | `core/src/test/.../Scenarios.kt`, `BumpEngineTest.kt` | `./gradlew :core:test` | engine | every PR touching `core/`, `app/`, `tools/replay/`, `.github/` |
| qa's own core tests | `core/src/test/**/qa/**` | same | qa | same |
| Replay of anonymized real drives (accuracy metrics) | `tools/replay/`, `testdata/` | `./gradlew :tools:replay:test` | validation | same as above; metrics table in the PR when `core/` changes |
| App JVM + Robolectric tests | `app/src/test/kotlin/app/bumpbeeper/` | `./gradlew testDebugUnitTest` (job `app-build`) | qa (+ android-platform / android-ui for their own) | every PR touching `app/`, `core/`, gradle files or `.github/` |
| Emulator (instrumented) tests | `app/src/androidTest/` | `connectedDebugAndroidTest` on an emulator | qa | nightly once devops adds the emulator job (not on every PR: too slow on free runners) |
| Backend (Supabase) pgTAP | `supabase/tests/*.sql` | `supabase test db` (job `backend`) | backend | every PR touching `supabase/` |
| Dashboard end-to-end | `dashboard/` | Playwright | dashboard | every PR touching `dashboard/` once it exists |
| Manual phone checklist | below | a real phone | owner + qa | before every release tag |

`ci-gate` is the single required check: it fails if any needed job failed. The `app-build` job writes
"app unit tests: N run, … failed" into the run summary; a PR that adds app tests must show N going up.

## App unit tests (JVM / Robolectric)

Rules: no network, no real clocks in assertions, no dependence on UI text (strings move to `strings.xml`).
Robolectric tests use `@RunWith(RobolectricTestRunner::class)` and `@Config(sdk = [34])`.
Plain file-format code (e.g. `TraceWriter`) runs as plain JUnit with a `TemporaryFolder`.

| Test | Covers |
|---|---|
| `BumpDbTest` | fresh install = schema v4; upgrade from a hand-built v1 database keeps bumps, events and trips and adds the new columns with neutral defaults; old trips stay unscored; CSV import merge rules (19 m vs 21 m, heading 44° vs 46°, wrap past north), bad-row counting, old export format with BOM/CRLF, export → import round trip |
| `UpdateCheckTest` | numeric version compare (1.10.0 > 1.9.0), `v` prefix, equal versions, missing parts, pre-releases never offered, garbage versions, dev/local builds detected and never checking (no network, no check time stored) |
| `LabelsTest` | label kinds match the trace/replay contract; unknown kinds and labels while not recording are refused without counting; a new trip clears the counters |
| `CrashLogTest` | crash list is newest first and ignores other files; a crash goes through the real handler, is written with version and stack trace, is passed on to Android's handler, and only the newest 10 files are kept |
| `TraceWriterTest` | `#` metadata lines before the unchanged header; 18 columns in every row; gyro columns empty when NaN; GPS, label and battery rows; labels flushed to disk at once; commas/newlines in notes sanitized; US digits on an Arabic phone; `TracingStore` starts copying events when a recording appears mid-trip |
| `SharingKmlTest` | KML parses as XML in the KML namespace; one pin per spot with the right style (muted > harsh > pothole > bump > unsure) and name; coordinates in lon,lat order with US digits; empty map is valid |

### Not yet testable on the JVM (tracked in #33)

- `UpdateCheck` 24-h back-off and the "count a failed check" rule: needs an injectable clock and HTTP call.
- Label counting (undo never below 0, count unchanged when the write fails) lives in `BumpService.postLabel` and needs the
  running service; to be covered by an emulator test, or on the JVM once the counting is a pure function.
- Prefs listener reacting only to engine keys and `label_mode`: needs the running service.

## Emulator tests (planned, `app/src/androidTest/`)

- Start → drive a mock-location route → Stop: a trip row appears with distance and score.
- Label mode switched on mid-trip: a recording file is opened, labels are written and counted, undo never goes below 0.
- Import a shared bump file through `ACTION_VIEW`: confirm dialog, merge counts in the toast.
- Upgrade: install the previous release APK, record a bump, install the new APK over it, bump still there.

## What runs when

- **Every PR:** only the jobs whose files changed (see `.github/workflows/ci.yml`), then `ci-gate`.
- **Every push to `main`:** all jobs.
- **Nightly (once devops adds it):** emulator tests and the full replay set.
- **Before a release tag:** everything above green on `main`, plus the manual checklist below.

## Manual phone checklist (before every release)

Use a real phone with the **previous release** installed and a few bumps learned. Tick each line in the release PR.

1. **Install over the previous version keeps the bumps.** Note the bump count on the Map page, install the new signed
   APK over it (no uninstall), open the app: same count, same trips, settings unchanged.
2. **Debug build installs side by side.** The PR test APK (`app.bumpbeeper.dev`) installs next to the real app and
   shows an empty map; the real app's data is untouched.
3. **Recording.** Start, drive over a known bump: it beeps before the bump; Stop: the trip appears with a score.
4. **Label mode.** Turn label mode on in Settings *while recording*: the label buttons appear, each tap raises the
   count, undo lowers it but never below 0; after Stop the recording file contains `event=label` rows.
5. **Update banner.** With an older version installed and a newer release on GitHub, the banner offers the update
   (once per day at most; "check now" forces it). A `-dev` build never shows it. A pre-release tag is never offered.
6. **Sharing.** Share the bump file (CSV) and import it on a second phone: "Added N new · M already on your map".
   Share the KML and open it in Google Earth: coloured pins in the right places.
7. **Crash log.** Settings shows saved crash reports (if any); they stay on the phone.
8. **Backup exclusions** (Android 11 and Android 12+ if possible):
   ```
   adb shell bmgr enable true
   adb shell bmgr backupnow app.bumpbeeper
   adb shell bmgr list transports
   ```
   The backup succeeds; restoring it (`adb shell bmgr restore <token> app.bumpbeeper`, or uninstall + reinstall with
   backup on) brings back the bumps and settings but **not** `files/crash/` or the `traces/` recordings.
9. **Arabic.** Switch the phone to Arabic: screens readable, exported CSV/KML still use 0-9 digits.
