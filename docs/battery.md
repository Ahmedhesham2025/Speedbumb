# Battery: what the app does and how to measure it

Target (ROADMAP P1): at most 4 % per hour of recording on a flagship phone, 6 % per hour on a budget phone.

## What saves battery while recording (#50)
| Car | GPS | Accelerometer (50 Hz) | Gyroscope |
|---|---|---|---|
| Moving (a fix at 6 km/h or more) | every 1 s | delivered at once | on |
| Stopped (every fix below 5 km/h for 30 s) | every 5 s | batched, delivered once a second with exact timestamps | off |

The accelerometer rate never changes, so the engine sees the same samples whenever the car moves.
Parked after real driving (3 min by default, setting `auto_stop_minutes`, 0 = never) ends the recording.
Rules and tests: `app/src/main/java/app/bumpbeeper/auto/PowerPolicy.kt`, `AutoStop.kt`.

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
