# Architecture

## Modules
```
core/        Pure Kotlin (JVM). No Android. Engine + driving monitor + simulator + scenarios.
  BumpEngine      sensors → jolts → bump/pothole (kind, side, harshness) → learned spots → warnings, passes, misses
  DrivingMonitor  sensors → speeding, harsh brake/accel, cornering, swerves, bumps taken fast, phone use → score 0–100
  Model, Geo, Phrases
app/         Android app (only platform APIs + :core)
  BumpService     foreground location service; feeds 50 Hz accel/gyro + 1 Hz GPS into engine + monitor on a HandlerThread
  BumpDb          SQLite: bumps, events, trips (v4) — next: outbox, remote_spots, sync_state (v5)
  UI              MainActivity (4 tabs) + DrivePage / MapPage / TripsPage / SettingsPage, Ui.kt helpers
  Sharing         CSV / KML share, import from other apps
  TraceWriter     debug recordings (raw sensors + GPS + events) — the input for replay tests
tools/replay  (P1)  trace + labels → engine → metrics.json; anonymizer
supabase/     (P2)  migrations, RLS, aggregation, pgTAP tests
dashboard/    (P4)  static fleet web page on GitHub Pages (MapLibre + Supabase JS)
contracts/          ownership map + sync formats shared by app, backend and dashboard
```

## Data flow (target)
```
phone sensors ─► BumpEngine ─► local spots (SQLite) ─► warnings (beep / voice)
                     │                ▲
                     ▼                │ remote_spots (pulled by tile at trip start)
              ObservationSink ─► outbox ─► Supabase submit_observations ─► aggregation (pg_cron) ─► spots
DrivingMonitor ─► trips (local) ─► (fleet drivers on shift only) submit_trip ─► fleet web page
```

## Contracts (change only via a lead PR)
- `BumpStore` (exists): where the engine keeps spots and events.
- `SpotSource` (P2): `spotsNear(lat, lon, radiusM)` — merges local learned spots with online confirmed ones.
- `ObservationSink` (P2): the engine records hazard observations for upload.
- `SensorFeed` (P1): lets tests replay a recorded trace into the service.
- `contracts/sync/*.schema.json` (P2): JSON formats for observations, spot tiles, trips, fleet join.

## Decisions
- Zero cost; free stack only (see `ROADMAP.md`).
- No runtime libraries in the app (smaller, fewer surprises, F-Droid friendly), except MapLibre Native (BSD-2) for the street map since v1.8, with free OpenFreeMap tiles. Two editions: `foss` (none) and `play` (+ Google activity recognition only); both detect driving with built-in sensors, `play` adds Google's IN_VEHICLE detection.
- Map merging in SQL (`pg_cron`) so it is testable with pgTAP and runs next to the data.
- Repo is public under GPL-3.0; real recordings are anonymized before they enter `testdata/`.
- Releases are tags `vX.Y.Z` → signed APK on GitHub Releases; `versionCode = X*10000 + Y*100 + Z`.
