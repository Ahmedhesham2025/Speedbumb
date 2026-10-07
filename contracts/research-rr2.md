# Research recording files: format rr2

The single source of truth for research recordings: what the Android app writes
(`app/src/main/java/app/bumpbeeper/research/`), what validation reads (`tools/replay`), and what a future iOS recorder must
write. When code and this file disagree, the code is wrong. Change this file first (lead PR), then the code.

- **Status:** rr2, Sprint 1 (P0-2: PRs #107, #109, #111, #119). `ResearchFormat.kt` holds the same code table and is
  tested against it.
- **rr1** (whole-millisecond times) was never released.
- **Readers** must check `# format=rr2` and refuse other versions.

## 1. Files

**File content**
- **Encoding:** gzip-compressed UTF-8 text with `\n` line ends. Data lines are plain ASCII.
- **Segments:** one file per segment of a trip.
  - A new segment starts at the first 2-second flush after 10 minutes, so a segment covers about 10 minutes.
  - Every segment carries the full header (§3) and can be read on its own.
  - Time runs on across segments.
- **Flushing:** the writer does a gzip sync flush every 2 s.
  - A file cut off by a killed app (no gzip trailer) is readable up to its last flush.
  - Readers stop quietly at a truncated end, and drop a last line that has no `\n`.

**Names**
- **Pattern:** `rr_<research id>_<start UTC>_<segment>.csv.gz`, for example `rr_3f2a9c1e_20261007T201530_000.csv.gz`.
  - **research id:** 8 lowercase hex digits (§6).
  - **start UTC:** the trip's start time, `yyyyMMdd'T'HHmmss` in UTC, the same in every segment of the trip.
  - **segment:** 0, 1, 2, … written with 3 digits. The server accepts 1 to 4.
- **While being written:** the file is named `<name>.part` and gets its final name when it is closed.
  - Only final names are shared or uploaded.
  - The app renames a `.part` left by a killed app once it has been untouched for a minute.
  - Empty files are deleted.

**Upload and storage**
- **Upload (P0-4, `supabase/migrations/20261007000002_research.sql`):**
  - object `<auth uid>/<name>` in the private `research` bucket, `application/gzip`;
  - 1 byte to 10 MB (10,485,760 bytes) per file;
  - the server checks `^[0-9a-f-]{36}/rr_[0-9a-f]{8}_[0-9]{8}T[0-9]{6}_[0-9]{1,4}\.csv\.gz$`.
- **Size:** about 27–32 MB per hour at the rates in §5 (measured: 22.7 MB/h with every stream at 100 Hz). A segment is
  therefore about 4–6 MB, well inside the 10 MB limit.
- **On the phone (Android):** `noBackupFilesDir/research`, which is never in a backup or a phone move.
  - At most 14 days and 2 GB are kept, whichever is smaller; the oldest files go first.
  - The limits are applied before each new file, at app start and after every trip.

## 2. Lines

Every line that is not a comment is: `t_dms,code,value,value,…`

- **`t_dms`:** time since the trip started, in tenths of a millisecond (0.1 ms = 100,000 ns).
  - It is a full integer on every line, never a difference.
  - Clock: the monotonic clock that sensors and GPS share (Android: `elapsedRealtimeNanos`).
    `t_dms = floor((event_ns − start_elapsed_ns) / 100,000)`.
  - **Negative** means measured before the start: the last value an on-change sensor had before the trip.
- **`code`:** what the line holds (§4).
- **Values:** integers, stored = round(physical × scale) (round half up). Physical = stored / scale.
  - An **empty value means "not available"**.
  - A line has as many fields as its code in §4, empty ones included.
  - Text fields (`ac`, `lbl`) are printable ASCII without commas: anything else becomes `_`, at most 64 characters.
- **Order:** lines are in the order the phone delivered them, **not sorted by time**. Sort by `t_dms` per code when order
  matters.
  - A `G` line carries the fix's own time, usually 0.1–1 s older than the lines around it.
  - Batched sensors (§5) arrive in bursts up to 1 s late.
  - The first reading of an on-change sensor can be minutes old.
- **Line length:** a data line is at most 256 bytes.
- **Unknown codes:** readers skip them, and ignore extra fields beyond the table. Codes may be added within rr2 (§8).

## 3. Header and footer

Comment lines start with `#`. The text after `# ` is `key=value`, split at the first `=`.

- **Order:** the writer emits the header keys in the order below. Readers map by key and must not rely on the order.
- **Header lines** are UTF-8 (a sensor name may hold non-ASCII), and line breaks inside a value become spaces.
- **Footer lines** are ASCII and at most 203 bytes.

| key | value |
|---|---|
| `format` | `rr2` |
| `line` | a human description of the line layout |
| `code.<c>` | one per code in §4: what it is, its fields and units (informational; §4 is binding) |
| `app_version` | app versionName, e.g. `1.8.0-beta1` |
| `android_sdk` | Android API level, e.g. `34` (another platform writes its own `os=…`) |
| `device` | `manufacturer/model`, e.g. `samsung/SM-A546E` (owner decision: sensor quirks are per model) |
| `placement` | `mounted`, `cupholder`, `pocket` or `unknown` (the user's setting) |
| `start_source` | who started the trip: `user`, `car` (car Bluetooth), `vehicle` (Google activity recognition), `motion` (built-in detection) |
| `trip_id` | the app's local trip id. P0-4 holds the files of a trip that "Was this a drive?" still asks about, and deletes them on "no" |
| `start_utc_ms` | wall clock at the trip start, ms since 1970-01-01 UTC |
| `start_elapsed_ns` | monotonic clock at the trip start, ns (Android `elapsedRealtimeNanos`) |
| `sensor.<c>` | one per sensor code (§5): `absent`, or the sensor description below |
| `research_id` | the 8-hex research id (§6), the same as in the file name |
| `segment` | `0`, `1`, `2`, … the same as in the file name |

**Sensor description:** `<name>;<vendor>;v<version>;res=<resolution>;max=<maxRange>;min_delay_us=<µs>;max_delay_us=<µs>;power_ma=<mA>;fifo=<events>;wakeup=<true|false>;batch_us=<µs>;asked_us=<µs>`.
- `;registered=no` is appended when Android refused the sensor.
- `res` and `max` are in the platform's own units (Android: m/s², rad/s, µT, hPa, lux, cm).
- Android may deliver faster or slower than `asked_us`. The real rate comes from the `t_dms` spacing.

**Time anchors**
- A line happened at monotonic time `start_elapsed_ns + t_dms × 100,000` ns.
- Its wall-clock time is about `start_utc_ms + t_dms / 10` ms. The wall clock can be changed by the user; the monotonic one
  can't.

**Footer:** the trip's last segment, and only that one, ends with two comment lines.
- `end_lines=<n>`: data lines of the whole trip, all segments, comments not counted.
- `end_dropped=<n>`: lines lost because storage was too slow (see `drop`) or failed.

A trip whose last segment has no footer was cut short: the app was killed, or storage failed.

## 4. Code table

Stored integer = round(physical × scale). Units are SI as listed. Phone axes follow Android's sensor coordinate system:
x to the right, y up the screen, z out of the screen.

| code | what | fields (in order) | stored unit | scale |
|---|---|---|---|---|
| `a` | accelerometer (gravity included) | x, y, z | mm/s² | 1000 (from m/s²) |
| `au` | accelerometer, uncalibrated | x, y, z, bias_x, bias_y, bias_z | mm/s² | 1000 |
| `g` | gyroscope | x, y, z | mrad/s | 1000 (from rad/s) |
| `gu` | gyroscope, uncalibrated | x, y, z, drift_x, drift_y, drift_z | mrad/s | 1000 |
| `gr` | gravity | x, y, z | mm/s² | 1000 |
| `la` | linear acceleration (gravity removed) | x, y, z | mm/s² | 1000 |
| `rv` | rotation vector (unit quaternion) | x, y, z, w; heading_accuracy | 1/10000; mrad (negative = unknown) | 10000; 1000 (from rad) |
| `gv` | game rotation vector (no magnetometer) | x, y, z, w | 1/10000 | 10000 |
| `m` | magnetic field | x, y, z | 0.01 µT | 100 (from µT) |
| `px` | proximity | distance | cm | 1 (many phones only say near = 0 or far = max) |
| `lx` | ambient light | illuminance | lux | 1 |
| `pa` | barometric pressure | pressure | 0.1 Pa | 1000 (from hPa) |
| `sd` | step detected | (none) | | |
| `ac` | a sensor's accuracy changed | sensor code (text); accuracy | `SENSOR_STATUS_*`: −1 no contact, 0 unreliable, 1 low, 2 medium, 3 high | text; 1 |
| `G` | GPS fix (`t_dms` = the fix's own time) | lat, lon; alt; speed; bearing; accuracy; speed_accuracy; bearing_accuracy; vertical_accuracy; lag | 1e-7°; cm; cm/s; 0.01°; cm; cm/s; 0.01°; cm; ms | 1e7; 100 (m); 100 (m/s); 100 (°); 100 (m, horizontal, 68 %); 100; 100; 100; 1 |
| `S` | GNSS status | used_in_fix, visible, mean_cn0_of_used | count, count, 0.1 dB-Hz | 1, 1, 10 |
| `scr` | screen | on | 1 on, 0 off | 1 |
| `unl` | unlocked (the user got past the lock screen) | (none) | | |
| `lk` | lock screen showing | locked | 1 locked, 0 not | 1 |
| `aud` | audio state | mode, outputs, call_device | see below | 1, 1, 1 |
| `bat` | battery | percent, plugged, status, temperature | %, see below, see below, 0.1 °C | 1, 1, 1, 10 (from °C) |
| `bt` | the car's Bluetooth | connected | 1 connected, 0 disconnected | 1 |
| `act` | activity transition (play edition) | kind | 1 in vehicle, 2 on foot (walking or running) | 1 |
| `lbl` | label tapped by the driver (label mode) | kind (text) | e.g. `bump`, `rough`, `undo` | text |
| `drop` | lines dropped so far | total | count (running total for the trip) | 1 |

`G` field details:
- `alt` is the WGS84 ellipsoid height.
- `lag` is how long after the fix's own time it reached the app.
- An empty field means the fix had no value (for example no altitude).

`aud`:
- **mode:** Android `AudioManager.MODE_*`: 0 normal, 1 ringtone, 2 in call, 3 in communication (VoIP), 4 call screening,
  5 call redirect, 6 communication redirect.
- **outputs:** a bitmask of output devices connected now (not necessarily playing):
  - 1 = Bluetooth (A2DP, SCO, BLE headset or speaker, hearing aid);
  - 2 = wired (headset, headphones, USB headset, line, aux).
- **call_device:** the device a call would use now, as `AudioDeviceInfo.TYPE_*`: 1 earpiece, 2 speaker, 3 wired headset,
  7 Bluetooth SCO, 26 BLE headset, …; empty = unknown.
- Nothing is read from the microphone.

`bat`:
- **plugged:** `BATTERY_PLUGGED_*` bits, 0 on battery: 1 AC, 2 USB, 4 wireless, 8 dock.
- **status:** `BATTERY_STATUS_*`: 1 unknown, 2 charging, 3 discharging, 4 not charging, 5 full.

## 5. When lines are written (Android, rr2)

Sensors are registered only while a trip records, with research recording switched on. Only the sensors the phone has
are used (missing ones: `sensor.<c>=absent`).

| code | asked rate | batched (max report latency) |
|---|---|---|
| `a`, `g` | 5,000 µs (200 Hz; the Android 12+ maximum without extra permission) | no (the engine reads them too) |
| `au`, `gu` | 5,000 µs | up to 1 s |
| `gr`, `la`, `rv`, `gv` | 10,000 µs (100 Hz) | up to 1 s |
| `m`, `pa` | 200,000 µs (5 Hz, "normal") | up to 1 s |
| `lx` | on change | up to 1 s |
| `px`, `sd` | on change (`sd` needs the play edition's "physical activity" permission) | no |

The other codes are written as follows.

**At the trip start**
- `scr`: always.
- `bt`: `1` when the car's Bluetooth started the trip.
- `act`: `1` when Google's in-vehicle detection started it.

**When something changes**
- `scr`: on each change of the screen.
- `unl`: on each unlock.
- `bt`: on each change. Only the car chosen for auto start counts.
- `act`: on each in-vehicle or on-foot transition.
- `bat`: when percent, plug, status or whole °C change.
- `ac`: when a sensor's accuracy changes.
- `lbl`: on each tap.

**Every 2 s, written only when the value changed (so also once at the start)**
- `lk`.
- `aud`.

**GPS**
- `G`: each GPS fix the trip gets: every 1 s while moving, every 5 s when stopped. Research asks for no GPS of its own.
- `S`: at most once per 950 ms, while GNSS runs.

**Writer**
- `drop`: at the next 2-second flush after lines were dropped, carrying the running total.

## 6. Research id

- **What it is:** 8 random lowercase hex digits from a secure random generator.
- **Where it is kept:** on the phone, outside every backup and phone move (Android: `noBackupFilesDir/research_id`).
- **What it is used for:** only research file names and the `research_id` key. It is not the install id, the auth uid or
  any account or device id. The server learns the uploader from the upload folder (auth uid), not from the file.
- **When it changes:**
  - each time the user switches research recording on: files of different opt-ins can't be linked through the name;
  - after a backup restore or on a new phone, which starts with no id.

## 7. What the files contain (for privacy reviews and consumers)

- **Location:** the full GPS track of the trip, including where it started and ended. The upload (P0-4) trims the first
  and last 300 m; local files keep everything.
- **Phone use:** screen, unlock, lock state, audio mode and route, proximity, light, steps.
- **Device:** the manufacturer and model in the header.
- **Never included:** audio content, camera, contacts, accounts.

## 8. Changing the format

- **Within rr2, update this file and `ResearchFormat.ALL` in the same PR:**
  - a new code;
  - a new header key;
  - a new field appended at the end of a code's fields.
- **Requires rr3 and a contract PR first:**
  - a changed unit, scale, field order or meaning;
  - a changed time base or file layout.
- **Another platform** (iOS) writes the same codes with the same units and scales. It converts from its own units, for
  example CoreMotion's accelerometer reports in g (× 9.80665 → m/s²) and its altimeter in kPa (× 10 → hPa).
  - It writes `os=<name and version>` instead of `android_sdk`.
  - `start_elapsed_ns` comes from its monotonic clock.
  - It leaves out codes it can't record.
