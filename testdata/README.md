# Test data

Recorded drives used by `tools/replay`. **Only anonymized recordings go here** (`replay anonymize`, see
`docs/validation/protocol.md`): GPS moved to a fake origin at 0.5, 0.5 with distances and bearings kept, the clock
started at 0, and only `app_version`, `android`, `placement` and `gyro` metadata kept. No dates, places, driver
initials or phone models in file names or notes.

Research recordings (rr2) go through the same command, all segments of a trip at once
(`anonymize --in rr_…_000.csv.gz --in rr_…_001.csv.gz --out driveNN_pocket.csv.gz`). They are also trimmed exactly
like the research upload (the first and last 300 m driven and 300 m around where the trip started and ended), become
one file with the clock at 0, a fake wall clock and altitude relative to the first kept fix, and keep only the app
version, OS level, placement, how the trip started and each sensor's rates and ranges (no research id, trip id,
device or sensor chip names). The command refuses a trip if anything original is left.

`testdata/real/*.csv.gz` (or `.csv`) are replayed by `RealDriveTest` on every CI run of `:tools:replay:test`. The test
fails if a file still looks raw: a position in the `lat`/`lon` columns of any row (GPS, event or label) away from the
fake origin, or metadata other than the four above. It also fails if the clock does not start at 0. That check
catches an untrimmed clock (rows cut from a file after it was anonymized, without restarting the clock), not a raw file:
a raw recording's clock can start at 0 too. Labels, when a drive has them, are the passenger's `label` event rows
inside the recording; with labels the accuracy table (`docs/validation/metrics.md`) is printed too. A `<name>.expected.properties` next to a drive turns its counts into a
regression guard (ranges are explained in that file); a `placement=` line there (mounted, cupholder, pocket) sets the
phone placement the drive is replayed with, unknown without it. An rr2 file in `real/` is checked the same way: positions
near the fake origin, the fake clocks, no identifying header keys, the clock starting at 0.

## Drives

| File | Placement | Android | App | Length | Labels |
|---|---|---|---|---|---|
| `real/drive01_pocket.csv.gz` | pocket | 15 | 1.4.0 (play) | recorded 50 min, 19.6 km; kept 22 min, 10.9 km | none |

### drive01_pocket
- **Trimmed and rotated** (owner-approved exception, `docs/validation/protocol.md`): every row from the first and
  last stretch driven is dropped, at least 1.5 km at each end (distance from GPS speed × time, as core
  `TripPrivacy.driven`) and more at the start, until the first and last kept fix are over 1 km in a straight line
  from where the drive started and ended (every kept fix ends up over 800 m from them, and neither cut falls
  at a stop); the clock restarts at 0 on the kept part. After the anonymizer's shift,
  positions are rotated about the fake origin by a secret angle and the `bearing` column turned by the same angle.
  Not mirrored, so left/right, swerves and the phone-frame accelerometer and gyroscope are unchanged.
- One continuous city drive, phone in a pocket, gyroscope present, about 88 Hz accelerometer and 1 Hz GPS.
- On the phone (its own map, already holding spots from earlier drives) the whole (untrimmed) drive logged **48 learned spots and
  30 warnings**, plus 29 hits, 4 misses and 51 rejected jolts (28 of them `phone_moving`: a phone in a pocket moves
  a lot). The replay starts from an empty map with the current engine, and sees only the kept part, so its counts differ; CI prints them.
- **Not labelled**: there is no ground truth, so precision, recall and false warnings cannot be measured. CI records
  learned spots, warnings, hits, misses, rejects by reason and harsh events instead. The owner may label spots later;
  labels would be added as `label` event rows and the expected counts re-pinned.
