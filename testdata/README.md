# Test data

Recorded drives used by `tools/replay`. **Only anonymized recordings go here** (`replay anonymize`, see
`docs/validation/protocol.md`): GPS moved to a fake origin at 0.5, 0.5 with distances and bearings kept, the clock
started at 0, and only `app_version`, `android`, `placement` and `gyro` metadata kept. No dates, places, driver
initials or phone models in file names or notes.

`testdata/real/*.csv.gz` (or `.csv`) are replayed by `RealDriveTest` on every CI run of `:tools:replay:test`. The test
fails if a file still looks raw (GPS away from the fake origin, clock not at zero, other metadata). Labels, when a
drive has them, are the passenger's `label` event rows inside the recording; with labels the accuracy table
(`docs/validation/metrics.md`) is printed too. A `<name>.expected.properties` next to a drive turns its counts into a
regression guard (ranges are explained in that file).

## Drives

| File | Placement | Android | App | Length | Labels |
|---|---|---|---|---|---|
| `real/drive01_pocket.csv.gz` | pocket | 15 | 1.4.0 (play) | recorded 50 min, 19.6 km; kept 22 min, 10.9 km | none |

### drive01_pocket
- **Trimmed and rotated** (owner-approved exception, `docs/validation/protocol.md`): every row from the first and
  last stretch driven is dropped, at least 1.5 km at each end (distance from GPS speed × time, as core
  `TripPrivacy.driven`) and more at the start, until the first and last kept fix are over 1 km in a straight line
  from where the drive started and ended; the clock restarts at 0 on the kept part. After the anonymizer's shift,
  positions are rotated about the fake origin by a secret angle and the `bearing` column turned by the same angle.
  Not mirrored, so left/right, swerves and the phone-frame accelerometer and gyroscope are unchanged.
- One continuous city drive, phone in a pocket, gyroscope present, about 88 Hz accelerometer and 1 Hz GPS.
- On the phone (its own map, already holding spots from earlier drives) the whole (untrimmed) drive logged **48 learned spots and
  30 warnings**, plus 29 hits, 4 misses and 51 rejected jolts (28 of them `phone_moving`: a phone in a pocket moves
  a lot). The replay starts from an empty map with the current engine, and sees only the kept part, so its counts differ; CI prints them.
- **Not labelled**: there is no ground truth, so precision, recall and false warnings cannot be measured. CI records
  learned spots, warnings, hits, misses, rejects by reason and harsh events instead. The owner may label spots later;
  labels would be added as `label` event rows and the expected counts re-pinned.
