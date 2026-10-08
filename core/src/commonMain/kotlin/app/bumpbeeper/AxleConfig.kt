package app.bumpbeeper

/** Every number of [AxleSignature]. Units: milliseconds, m/s², rad, Hz, km/h; fractions where it says so. */
class AxleConfig {
    /** The rear impulse is looked for this fraction either side of wheelbase / speed. */
    var dtTolerance = 0.35
    /** Impulses are judged in this band: no body bounce or pocket sway, nothing near Nyquist at 50 Hz. */
    var bandLowHz = 2.0
    var bandHighHz = 15.0
    /** Too slow a phone, too slow a car (Δt past any window) or too fast (impulses blur): UNKNOWN. */
    var minRateHz = 30.0
    var minSpeedKmh = 3.0
    var minDtMs = 70.0
    /** The envelope (RMS) window: this fraction of the expected Δt, within [envMinMs]..[envMaxMs]. */
    var envFraction = 0.25
    var envMinMs = 30.0
    var envMaxMs = 150.0
    /** The filters settle this long before anything is judged; the span judged reaches this far past the windows. */
    var warmupMs = 500.0
    var spanMarginMs = 400.0
    /** The front impulse is the one the trigger falls in, or one starting at most this long after it. */
    var frontSearchMs = 150.0
    /** Two envelope humps are two impulses only when it dips below 1 / this of the smaller one between them... */
    var riseRatio = 1.4
    /** ...and are never closer than this × the envelope window (the lobes of one impulse). */
    var sepFraction = 1.0
    /** An impulse's time: its first top at this fraction of its strongest (pocket ringing builds up after a hit). */
    var onsetFraction = 0.6
    /** The rear (later) peak may be at most this × the front one; stronger, the trigger may not be the front axle. */
    var maxRearRatio = 1.5
    // Score parts, each a ramp from no credit to full credit:
    /** weaker ÷ stronger impulse... */
    var minBalance = 0.25
    var goodBalance = 0.5
    /** ...the weaker one over the road's shaking before the jolt... */
    var snrLow = 2.5
    var snrHigh = 5.0
    /** ...how far the envelope dips between them... */
    var riseGood = 2.5
    /** ...and, for pairs at least [quietMinMs] apart, its median between them ÷ the weaker ([quietBad] → none). */
    var quietMinMs = 250.0
    var quietGood = 0.65
    var quietBad = 0.85
    /**
     * Other impulses at least this × the weaker of a pair halve its score unless a wheelbase from another one
     * (± [explainTolerance] of Δt: a wide hump crossed by both axles); with more than [maxImpulses] around, or one
     * whose strong tops spread over [trainFraction] of Δt, it is a train (rumble strip, rough road): score 0.
     */
    var otherFraction = 0.6
    var explainTolerance = 0.15
    var maxImpulses = 4
    var trainFraction = 0.75
    /** A jolt with less than this share of its raw peak in the band (a buzz or click above it) can't be judged. */
    var minBandShare = 0.35
    /** Pitch: a car turns a few mrad over a bump; more is the phone turning (a pocket). Inverted order halves the score. */
    var pitchMinRad = 0.004
    var pitchMaxRad = 0.05
    var pitchWrongFactor = 0.5
    /** [AxleVerdict.BOTH] from this score. */
    var bothMinScore = 0.6
    /**
     * [AxleVerdict.ONE] only when nothing at either window reaches this much evidence (balance × snr × dip), and the
     * front stood out of the noise (a rear [visibleRatio] of it would, by [visibleSnr]), died down below
     * [decayFraction] of its peak before the rear window, the windows stayed below [quietFraction] of it, and nothing
     * else in the whole span reached [loneFraction] of it (the rear may be there at another spacing: GPS speed lags
     * in hard braking).
     */
    var oneMaxEvidence = 0.3
    var visibleRatio = 0.5
    var visibleSnr = 3.0
    var decayFraction = 0.5
    var quietFraction = 0.25
    var loneFraction = 0.35
    /** Braking: the most speed lost in the [brakeWindowMs] before the jolt, from GPS; [brakeMinDropKmh] = braked. */
    var brakeWindowMs = 3000L
    var brakeMinDropKmh = 4.0
    /** [AxleSignature.decideWindowMs] = Δt + [decideMarginMs], within [decideMinMs]..[decideMaxMs]. */
    var decideMinMs = 1200L
    var decideMarginMs = 400L
    var decideMaxMs = 1600L
}

enum class AxleVerdict {
    /** Both axles felt, a wheelbase apart: a road feature under the car. */
    BOTH,
    /** One impulse where the rear axle would clearly have shown: not a bump under the car (handling, a knock). */
    ONE,
    /** Can't tell; never read this as ONE. */
    UNKNOWN,
}

/** What [AxleSignature.analyze] found. Peaks are |acceleration| in the impulse band, m/s². */
class AxleResult(
    val verdict: AxleVerdict,
    /** 0..1, how clearly two axle impulses showed; NaN when the analysis could not run ([reason]). */
    val score: Double,
    /** Measured front → rear gap of the best pair, ms; NaN without one. */
    val dtMs: Double,
    /** Wheelbase / speed, ms. */
    val expectedDtMs: Double,
    val frontPeak: Double,
    val rearPeak: Double,
    /** The trigger was the rear axle (the front one, weaker, came before it). */
    val rearFirst: Boolean,
    /** +1 nose up at the front impulse and down at the rear, -1 the other way round, 0 unknown or not car-sized. */
    val pitchOrder: Int,
    /** Speed lost in the window before the jolt, km/h; NaN without GPS fixes there. */
    val brakeDropKmh: Double,
    /** [brakeDropKmh] ≥ [AxleConfig.brakeMinDropKmh]; null when unknown. */
    val braked: Boolean?,
    /** pair | single | slow | fast | no_data | no_front | above_band | second_stronger | unclear | window | noisy | ringing | other_impulse | busy */
    val reason: String,
)
