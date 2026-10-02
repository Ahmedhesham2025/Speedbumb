# Accuracy metrics

How labelled recordings are scored. `tools/replay` computes all of this (`Metrics.compute`); this page is the
definition, and the code must match it. Targets are the P1 exit criteria from `ROADMAP.md`.

## Inputs
- **Runs**: one or more recordings of the same route (`replay --trace run1.csv --trace run2.csv …`, oldest first).
  They are replayed one after another on **one shared map** that starts empty: a fresh engine per recording (one
  trip each, as on the phone), the same store for all. With a single recording the map is empty until the engine
  finds something, so beeps, and the two metrics built on them and on repeat passes, only mean something over
  several runs.
- **Labels**: the passenger's taps, read from the recording's `label` event rows (`Metrics.labels`, built on
  `TraceReader.labels`). An `undo` removes the tap before it. Each label sits where the car was **at the tap time**:
  interpolated between the two GPS fixes around it, or, after the last fix, moved on from that fix along its bearing
  at its speed (for at most 2 s); before the first fix it takes the first fix. Its **speed** is the speed of the GPS
  fix nearest in time (unknown if no fix within 3 s).
  - Hazard labels: `bump`, `pothole_l`, `pothole_r`. `rough` (a rough stretch, not one spot) is not a hazard: it is
    never counted as missed, but a detection on it is not counted as false either.
- **Detections**: the engine's `new_bump` and `hit` events from the replay.
  `hit_repeat` (a second jolt on the same pass, e.g. the rear axle) is not a detection. A detection's time is
  when the engine logged it (≈ 1.2 s after the jolt); its place is the engine's position of the jolt.
- **Beeps**: `beep` events (not `beep_quiet`).
- **Distance**: the engine's trip distance (`TripStats.distanceM`).

## Match
Labels and detections are matched within each run. A detection **matches** a label when it is within **±2 s** of it
and within **15 m + speed × |Δt|** of it, where speed is the label's speed (0 if unknown) and Δt is the time between
the detection and the label, capped at 2 s. The allowance is for fast hits: the tap comes after the wheels hit, so
the car has already moved on (14 m per second at 50 km/h). A label without GPS is matched on time alone.
Each label matches at most one detection and vice versa. Hazard labels are paired first, then `rough` ones (so a
rough tap cannot take a detection away from a hazard); within each group, pairs closest in time go first.

Note: a detection's time is the engine's decision, about 1.2 s after the jolt, so Δt is small when the tap came
about 1.2 s after the hit; the allowance grows for taps much earlier or later than that.

## Metrics and targets

| Metric | Definition | Target |
|---|---|---|
| **Precision** | matched detections ÷ all detections | ≥ 0.90 |
| **Recall ≥ 25 km/h** | matched hazard labels ÷ hazard labels whose speed was ≥ 25 km/h | ≥ 0.95 |
| **Recall overall** | matched hazard labels ÷ all hazard labels | ≥ 0.85 |
| **False warnings / 100 km** | beeps with no label (any kind, any pass, any run) within **30 m** of the warned spot, per 100 km driven (all runs). The warned spot is the beep's distance ahead of the car along its heading; if the beep has no heading or distance, the car's own position. | ≤ 1 |
| **Bump vs pothole** | over matched hazard labels: the spot's kind after that hit (from the event note: `new_bump` first word, `hit` `now=`) equals the label (`bump` → bump, `pothole_l/_r` → pothole). `unsure` counts as wrong. | ≥ 85 % |
| **Pothole side** | over matched `pothole_l`/`pothole_r` labels: the hit's `side=left/right` equals the label. No side (no gyroscope, no clear roll) counts as wrong. | ≥ 80 % |
| **Spot learned within 2 passes** | hazard labels of all runs, in replay order, are grouped into spots (within 15 m of the spot's first label); each label is one pass, so pass 1 and 2 are normally runs 1 and 2. Over spots labelled at least twice: the share where pass 1 or pass 2 has a matched detection. | tracked, no target yet |

A ratio with nothing to count (e.g. no potholes labelled) is reported as `null` / `–`, never as 0 or 1.

Other P1 criteria (harsh-brake F1 ≥ 0.85, driving score ± 7, battery ≤ 4–6 %/h) are measured separately; they are
not computed by the replay tool yet.

## Output
- `metrics.json`: every ratio above (over all runs) plus the raw counts (`labels`, `matched_labels`, `detections`, …);
  with several runs also `runs`, with labels, detections, beeps and false warnings per run.
- A markdown table on stdout with pass/**FAIL** per target, for pasting into PRs, plus a per-run table for several runs.

A "pass" on false warnings from a single recording does not count toward P1: replay all runs of a route together.
