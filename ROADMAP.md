# Roadmap: prototype → fleet pilot (zero cost)

Start 5 Oct 2026, about 22 weeks. Full plan and reasoning: the approved project plan (summarised here).

| Phase | Weeks | Goal | Exit criteria |
|---|---|---|---|
| **P0 Foundation** | 1–2 | Agents work safely; every change tested | `:core` module, 17/17 scenarios; branch rules on `main` (PR + green `ci-gate`); debug APK per PR; anonymizer works |
| **P1 Real-road validation** | 2–7 | Measured accuracy on Egyptian roads | ≥ 600 km, 3 drivers, 4 phones, 3 vehicle types. Precision ≥ 0.90; recall ≥ 0.95 (≥ 25 km/h) / ≥ 0.85 overall; false warnings ≤ 1/100 km; bump vs pothole ≥ 85 %; side ≥ 80 %; harsh-brake F1 ≥ 0.85; score ± 7; battery ≤ 4–6 %/h |
| **P2 Shared online map** | 4–10 | New phone warns on its first drive | RLS tested per role; 3 phones confirm → 4th warns (emulator E2E); a week offline loses nothing; ≤ 2 MB/day; fits Supabase Free; daily keep-alive |
| **P3 Public release (free)** | 8–13 | Anyone can install | GitHub Releases + in-app update check; Galaxy Store + AppGallery approved; F-Droid merged; privacy policy + download page on GitHub Pages; AR + EN; crash-free ≥ 99.5 % over 20 beta users / 14 days |
| **P4 Fleet package** | 9–15 | Fleet runs itself | Fleet mode in app (join code/QR, on/off shift); fleet web page on GitHub Pages; fleet A can't see fleet B; off-shift never uploads |
| **P5 Fleet pilot** | 14–22 | First paying customer | ≥ 80 % vehicles active weekly; harsh events −15 %; < 1 false-alarm complaint/driver/week; ≥ 70 % km covered; LOI or contract. Start by 4 Jan 2027 (before Ramadan) |

## Free stack
GitHub (public repo, Actions, Pages, Releases) · Supabase Free (Postgres + PostGIS, auth; daily keep-alive) ·
MapLibre + OpenFreeMap · distribution via GitHub Releases, F-Droid, Samsung Galaxy Store, Huawei AppGallery.
Google Play ($25) is postponed until there is revenue.

## Sprint 1 (5–16 Oct 2026)
- [ ] **owner:** free Supabase project; 3 drivers + 1 labeler; 3 loop routes (~15 known hazards each); free Samsung + Huawei developer accounts
- [ ] **lead:** CLAUDE.md, ARCHITECTURE, LICENSE (GPL-3.0), agent files, OWNERSHIP, PR template, Sprint 1 issues, interface PR
- [ ] **engine:** `:core` module (17/17); `TraceReader`; simulator → trace export
- [ ] **devops:** `ci.yml` with `ci-gate` + debug APK per PR; tag-only signed `release.yml` → GitHub Release; branch rules; GitHub Pages
- [ ] **android-platform:** labels + battery in traces; crash file; "update available" check
- [ ] **android-ui:** label-mode screen; edge-to-edge
- [ ] **validation:** protocol, metrics, anonymizer, replay CLI (self-test on simulator traces)
- [ ] **backend:** first migration + RLS, seed, pgTAP in CI, keep-alive
- [ ] **qa:** test plan; DB + CSV-import tests; first emulator test
- [ ] **chore:** strings → `strings.xml` + Arabic draft

**Demo (day 10):** one agent PR merges through every gate · 20-minute labelled real drive · first real accuracy table.
