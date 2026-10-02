# Bump Beeper backend (Supabase Free)

The shared bump map and the fleet pilot. Everything here runs on the Supabase **Free** plan (500 MB database):
raw observations are merged into spots and deleted after 30 days, so the database stays small.

```
migrations/20261005000001_core.sql   tables, RLS policies, RPCs, aggregation, pg_cron job
seed.sql                             sample fleets/spots for local runs and CI only (desert coordinates)
tests/*.sql                          pgTAP tests, run by CI (`supabase test db`)
config.toml                          local `supabase start` settings (anonymous sign-ins on)
```

## Tables

| Table | What it holds | Who can read / write (RLS) |
|---|---|---|
| `devices` | one row per installed app (id = anonymous auth user): version, consent, `share_enabled`, `trust` | own row only; may update version/consent/sharing, never `trust`; created by `register_device` |
| `observations` | raw hazard points sent by phones (`jolt`, `known_hit`, `pass_clear`), time rounded to the hour | nobody; only `submit_observations` and the aggregation touch it |
| `spots` | merged bumps/potholes with counters and `status` (candidate → confirmed → stale / retired) | signed-in users read **confirmed** spots only; nobody writes |
| `spot_contributors` | per spot and device: hits, clears (used for counting distinct devices) | nobody |
| `fleets`, `memberships`, `vehicles`, `drivers` | fleet pilot: who manages which fleet, its vehicles and drivers | members read their fleet; owner/admin write; drivers are linked to devices only via a future join RPC |
| `invites` | join codes for drivers | owner/admin of that fleet only (viewers can't see codes) |
| `trips`, `trip_events` | driving scores and harsh events | the device that drove, or members of the trip's fleet |
| `crash_reports` | crash stacks | a device can insert its own; nobody reads through the API |

`anon` (not signed in) has no table privileges and cannot call any RPC. The app always signs in anonymously first.

## RPCs (all `SECURITY DEFINER`, empty `search_path`, executable by `authenticated` only)

- `register_device(app_version, os_api, consent_version, share_enabled) → uuid`: create/refresh the caller's device row.
- `submit_observations(batch jsonb) → uuid[]`: up to 500 elements
  `{"client_obs_id", "kind", "lat", "lon", "heading", "speed_kmh", "peak", "kind_score", "side_score", "observed_at"}`.
  Rejects the whole batch (`22023`) if any element has an unknown kind, is outside lat 12..38 / lon 24..60, or is older
  than 30 days. Rejects (`42501`) unregistered devices and devices with sharing off, and (`54000`) above 2000 rows per
  device per day. Duplicates (`device_id, client_obs_id`) are ignored. Returns the ids the server holds, so the phone
  can clear its outbox.
- `spots_near(lat, lon, radius_m default 3000)`: confirmed spots as `id, latitude, longitude, heading, kind, side,
  severity, n_devices, last_hit`; radius capped at 10 km.
- `forget_me()`: deletes the caller's device and, by cascade, its observations, contributions, trips and crash reports.
  Merged spots stay because they no longer refer to anyone.

`aggregate_observations()` is server-only (not executable by clients). It matches each new observation to a spot within
15 m and 45° of heading (or opens a candidate), updates contributors and counters, confirms a spot when
`n_devices >= 3` (2 if one of them is an active fleet driver's phone) and `hits / (hits + clears) >= 0.5`, marks a
confirmed spot `stale` when that ratio falls, and deletes processed observations older than 30 days.

## Privacy

- Only hazard **points** are uploaded, never tracks or routes; observation time is rounded down to the hour.
- Devices are anonymous auth users: no name, email or phone number.
- Uploading needs `share_enabled = true` (the user's opt-in in the app).
- Raw observations are deleted 30 days after they are merged; `forget_me()` deletes a device's data at once.
- Fleet trip data is visible only to that fleet's members; one fleet can never see another.

## Tests

CI (`backend` job) runs `supabase start`, `supabase db lint --level error` and `supabase test db`. The tests impersonate
roles with `set local role authenticated` + `request.jwt.claims` inside a rolled-back transaction:
`01_rls.sql` (role matrix), `02_submit_observations.sql`, `03_aggregation.sql`, `04_forget_me.sql`.

## Applying to the hosted project (owner / lead only, after approval)

Agents never link or push to the hosted project. When the owner approves:

1. In the Supabase dashboard: **Authentication → Sign In / Providers → Allow anonymous sign-ins** on; email
   confirmations as wished (the app does not use email).
2. **Database → Extensions**: enable `pg_cron` (and check `postgis` is available). Enable it *before* step 3 so the
   migration schedules the job; if it was enabled later, run once in the SQL editor:
   `select cron.schedule('aggregate-observations', '*/10 * * * *', 'select public.aggregate_observations()');`
3. Apply `migrations/20261005000001_core.sql` (via the Supabase connector's apply-migration, or `supabase db push` from
   a linked machine). Do **not** run `seed.sql` on the hosted project.
4. Check: `select jobname, schedule from cron.job;` shows `aggregate-observations`, and the Security Advisor shows no
   "RLS disabled" errors.

Rollback (nothing depends on it yet): `drop schema public cascade` is too broad; instead drop the tables listed above,
the six functions, and `select cron.unschedule('aggregate-observations');`.
