# Accuracy metrics

How a labelled recording is scored. `tools/replay` computes all of this (`Metrics.compute`); this page is the
definition, and the code must match it. Targets are the P1 exit criteria from `ROADMAP.md`.

## Inputs
- **Labels**: the passenger's taps, read from the recording's `label` event rows (`TraceReader.labels`). An `undo`
  removes the tap before it. Each label sits at the GPS fix nearest in time; its **speed** is that fix's speed
  (unknown if no fix within 3 s).
  - Hazard labels: `bump`, `pothole_l`, `pothole_r`. `rough` (a rough stretch, not one spot) is not a hazard: it is
    never counted as missed, but a detection on it is not counted as false either.
- **Detections**: the engine's `new_bump` and `hit` events from a replay of the recording (fresh, empty map).
  `hit_repeat` (a second jolt on the same pass, e.g. the rear axle) is not a detection. A detection's time is
  when the engine logged it (≈ 1.2 s after the jolt); its place is the engine's position of the jolt.
- **Beeps**: `beep` events (not `beep_quiet`).
- **Distance**: the engine's trip distance (`TripStats.distanceM`).

## Match
A detection **matches** a label when it is within **±2 s** and **15 m** of it (a label without GPS: time only).
Each label matches at most one detection and vice versa; pairs closest in time are paired first.

## Metrics and targets

| Metric | Definition | Target |
|---|---|---|
| **Precision** | matched detections ÷ all detections | ≥ 0.90 |
| **Recall ≥ 25 km/h** | matched hazard labels ÷ hazard labels whose speed was ≥ 25 km/h | ≥ 0.95 |
| **Recall overall** | matched hazard labels ÷ all hazard labels | ≥ 0.85 |
| **False warnings / 100 km** | beeps with no label (any kind, any pass) within **30 m** of the warned spot, per 100 km driven. The warned spot is the beep's distance ahead of the car along its heading. | ≤ 1 |
| **Bump vs pothole** | over matched hazard labels: the spot's kind after that hit (from the event note: `new_bump` first word, `hit` `now=`) equals the label (`bump` → bump, `pothole_l/_r` → pothole). `unsure` counts as wrong. | ≥ 85 % |
| **Pothole side** | over matched `pothole_l`/`pothole_r` labels: the hit's `side=left/right` equals the label. No side (no gyroscope, no clear roll) counts as wrong. | ≥ 80 % |
| **Spot learned within 2 passes** | hazard labels are grouped into spots (within 15 m of the spot's first label); each label is one pass. Over spots labelled at least twice: the share where pass 1 or pass 2 has a matched detection. | tracked, no target yet |

A ratio with nothing to count (e.g. no potholes labelled) is reported as `null` / `–`, never as 0 or 1.

Other P1 criteria (harsh-brake F1 ≥ 0.85, driving score ± 7, battery ≤ 4–6 %/h) are measured separately; they are
not computed by the replay tool yet.

## Output
- `metrics.json`: every ratio above plus the raw counts (`labels`, `matched_labels`, `detections`, …).
- A markdown table on stdout with pass/**FAIL** per target, for pasting into PRs.
