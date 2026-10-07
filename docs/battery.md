# Battery: what the app does and how to measure it

Target (ROADMAP P1): at most 4 % per hour of recording on a flagship phone, 6 % per hour on a budget phone.

## What saves battery while recording (#50)
| Car | GPS | Accelerometer + gyroscope (50 Hz) |
|---|---|---|
| Moving (a fix at 6 km/h or more) | every 1 s | unchanged |
| Stopped (every fix below 5 km/h for 30 s) | every 5 s | unchanged |

The sensors never change, so the engine sees the same samples as before. Two ideas were dropped after review:
switching the gyroscope off (the engine would keep using its last, frozen reading) and batching the accelerometer
while stopped (changing it re-registers the sensor, which discards up to 1 s of queued samples, and the recording's
wake lock keeps the CPU awake anyway, so it saved almost nothing).

Parked after real driving ends the recording: no fix at 5 km/h or more that also moved 30 m for 5 min (setting
`auto_stop_minutes`, 0 = never), never while the car's Bluetooth is connected.
Rules and tests: `app/src/main/java/app/bumpbeeper/auto/PowerPolicy.kt`, `AutoStop.kt`.

## Research recording (opt-in, Sprint 1)
Only while a trip records, and only when `research_recording` is on (off by default). For that trip only it registers:
- accelerometer and gyroscope, and their uncalibrated versions, at 200 Hz;
- gravity, linear acceleration and both rotation vectors at 100 Hz;
- magnetometer, proximity, light, pressure and step detector at their normal rate.

It also listens for GNSS status and for screen, unlock and battery broadcasts, and reads the lock state and the audio
mode and route every 2 s. It asks for no GPS of its own: it reuses the recording's fixes. Everything is unregistered when
the trip stops. Code: `app/src/main/java/app/bumpbeeper/research/`.

The sensors only research uses are batched (up to 1 s), so the sensor hub hands them over in bursts. The accelerometer
and gyroscope are not: the engine reads them at once. The proximity sensor is Android's wake-up one; the trip's wake
lock keeps the phone awake anyway, but its infrared emitter stays on for the whole trip. The file writer runs at
background priority.

Android delivers the fastest rate any listener asks for to every listener of a sensor, so the engine's own accelerometer
and gyroscope listeners would get 200 Hz too: more bandwidth in its raw jolt peaks, a training ring that covers a
quarter of the time, and four times the work. While research runs, BumpService therefore averages them into bins of at
least 10 ms (≤ 100 Hz, about what phones deliver without research) before the engine, the driving monitor and the
debug recording see them (`research/RateAverager.kt`). Still to check: replay the first research drive's 200 Hz `a`
stream against the averaged one to pin the jolt trigger's difference.

Expected cost (not measured yet): about +1–2 % per hour on top of a normal recording. GPS and the wake lock are already
on and cost more. To measure: research files hold a `bat` line at every percent step; compare a trip with research to a
similar trip without it on the same phone.

## How to measure
1. Charge the phone above 80 %, unplug it, and turn on **Debug recording** in Settings.
2. Drive a normal trip of at least 45 minutes (some stops, some open road). Screen off, phone mounted.
3. Share the recordings (Settings → recordings) and open the trip file. Every 5 minutes it has an `event` row of type
   `battery` with the percent in the `peak` column; `power` rows (note `stopped` / `moving`) show when battery saving
   switched on and off.
4. Battery use per hour = (first percent − last percent) ÷ hours between those two rows. Ignore trips shorter than
   30 minutes: one percent step is too coarse.
5. Compare with a recording from an older version on the same phone and a similar route. Note the phone model,
   Android version, and whether navigation or music ran at the same time (they use battery too).
