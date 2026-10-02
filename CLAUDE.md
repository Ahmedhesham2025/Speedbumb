# Bump Beeper: rules for AI agents

Android app that learns speed bumps and potholes, warns before them, and scores driving. Kotlin, minSdk 29.
Roadmap: `ROADMAP.md`. Architecture: `docs/ARCHITECTURE.md`. Who owns which file: `contracts/OWNERSHIP.yml`.

## Hard rules
1. **Zero cost.** Use only free services: GitHub (public repo, Actions, Pages, Releases), Supabase Free, OpenFreeMap tiles,
   free app stores. Never add a paid service or anything that needs a credit card.
2. **No runtime libraries in the app.** The app ships only Android platform APIs plus `:core`. Test-only libraries are fine
   (`testImplementation` / `androidTestImplementation`). Supabase is reached with `HttpURLConnection` + `org.json`.
3. **Edit only the files you own** (`contracts/OWNERSHIP.yml`). Need a change elsewhere? Open an issue labelled `needs:<owner>`.
4. **Contracts first.** Interfaces in `core/` and formats in `contracts/` change in their own lead PR before code uses them.
5. **Never commit real drive recordings as-is.** Only traces passed through the anonymizer (`tools/replay`) go into `testdata/`.
6. **Secrets never leave GitHub secrets / the owner's machine.** Never read `*.p12`, `password.txt`, `.env*`. Never run `gh secret`.
7. **Every change goes through a PR** to `main`: branch `agent/<agent>/<issue>-<slug>`, PR ≤ 600 changed lines (LICENSE, generated files and test data excluded),
   `ci-gate` green (until devops adds it: the existing build workflow green), gatekeeper verdicts (reviewer + qa, + security-privacy for location/sync/DB/manifest/privacy) before merge.
8. **Don't edit another agent's tests to make your change pass.** File an issue for the owner instead.
9. After **3 failed CI fix attempts**, stop and report to the lead with the error and your diagnosis.
10. Releases happen only when the owner says so: the lead tags `vX.Y.Z`, `release.yml` builds, signs and publishes.

## Building and testing
- There is no Android SDK on the owner's PC: **GitHub Actions is the build machine.** Push the branch, then
  `gh run watch <id> -R Ahmedhesham2025/speedo --exit-status` and `gh run view <id> --log-failed` on failure.
- `:core` (pure Kotlin) holds the engine, driving monitor, simulator and the simulated-drive scenarios: `./gradlew :core:test`.
- New detection behaviour needs a scenario in `core/src/test/.../Scenarios.kt`, plus a replay check once `tools/replay` exists.

## Style
Match the surrounding code: small comments that explain *why*, plain names, UI built in code (`Ui.kt` helpers), dark theme,
user-facing text in plain English and Egyptian Arabic. Numbers in files and CSV always use `Locale.US`.

## Commits
`git -c user.name="Ahmedhesham2025" -c user.email="Ahmedhesham2025@users.noreply.github.com" commit`, and end the message with:
`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
