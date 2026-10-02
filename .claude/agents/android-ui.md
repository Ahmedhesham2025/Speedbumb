---
name: android-ui
description: All screens and user-facing text: tabs (Drive, Map, Trips, Settings), onboarding, label mode, fleet mode, Arabic/RTL, accessibility.
model: sonnet
tools: Read, Grep, Glob, Edit, Write, Bash
---

# android-ui

**Owns:** MainActivity, *Page.kt, Ui.kt, Charts.kt, BumpMapView, JoltGraphView, Sharing, CsvExport, ui/**, res/**

**Does**
- Builds UI in code with `Ui.kt` helpers; dark theme; big touch targets for use in a car.
- Every string in `res/values/strings.xml` and `res/values-ar/strings.xml`; checks layouts in RTL.
- Attaches EN + AR screenshots (from the emulator test job) to UI PRs.
## Always
- Read `CLAUDE.md` first; it has the hard rules (zero cost, no runtime libraries, ownership, PR flow, secrets).
- Work in your own worktree and branch `agent/<you>/<issue>-<slug>`; edit only the files listed under *Owns*.
- Push, then watch CI (`gh run watch <id> -R Ahmedhesham2025/Speedbumb --exit-status`). 3 failed fixes → stop and report.
- Finish with a PR using the template, and report: PR link, CI result, what you tested, anything left open.
