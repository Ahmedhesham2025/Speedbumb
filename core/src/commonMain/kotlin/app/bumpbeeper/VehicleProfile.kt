package app.bumpbeeper

import kotlin.math.max

/** The car's geometry and how its axes are found in the phone ([VehicleProfile], [VehicleFrame]). Units: metres, m/s². */
class VehicleConfig {
    /** Wheelbase before anything is learned: a typical compact or family car... */
    var wheelbaseStartM = 2.6
    /** ...and the range it is kept in (small hatchback to long saloon or van). */
    var wheelbaseMinM = 2.2
    var wheelbaseMaxM = 3.2
    /** One axle hit's Δt × speed outside this is a wrong pairing or a bad speed: ignored. */
    var measuredMinM = 1.8
    var measuredMaxM = 3.8
    /** The newest hit keeps at least this weight: another car (a shared phone) is mostly learned in about 15 hits. */
    var minWeight = 0.1
    /** Forward must lean at least this far from up (sine of the angle) to tell where the nose points... */
    var forwardMinSine = 0.25
    /** ...and gravity be at least this strong; less is free fall or garbage. */
    var minGravity = 5.0
}

/**
 * This car's wheelbase, learned from axle hits: each one measures it as Δt × speed (the rear wheels hit a wheelbase
 * after the front ones). Starts at [VehicleConfig.wheelbaseStartM], which counts like one earlier hit, so the first
 * few hits move it most. A steady bias in GPS speed at bumps ends up in the learned value too; that is fine, as the
 * detector uses the same speed to predict Δt. Plain state ([wheelbaseM], [hits]) that the app stores ([encode]).
 */
class VehicleProfile(val cfg: VehicleConfig = VehicleConfig()) {
    var wheelbaseM = cfg.wheelbaseStartM
        private set
    /** Axle hits learned from. */
    var hits = 0
        private set

    /**
     * One hit with both axles felt, [dtMs] apart at [speedMps]. False (and nothing learned) when the measurement is
     * outside [VehicleConfig.measuredMinM]..[VehicleConfig.measuredMaxM] or not a number.
     */
    fun learn(dtMs: Double, speedMps: Double): Boolean {
        val m = dtMs / 1000.0 * speedMps
        if (!(m >= cfg.measuredMinM && m <= cfg.measuredMaxM)) return false
        val w = max(1.0 / (hits + 2), cfg.minWeight)
        wheelbaseM = (wheelbaseM + (m - wheelbaseM) * w).coerceIn(cfg.wheelbaseMinM, cfg.wheelbaseMaxM)
        hits++
        return true
    }

    /** Put back stored values; anything unusable gives the starting value. */
    fun restore(wheelbaseM: Double, hits: Int) {
        val ok = wheelbaseM >= cfg.wheelbaseMinM && wheelbaseM <= cfg.wheelbaseMaxM
        this.wheelbaseM = if (ok) wheelbaseM else cfg.wheelbaseStartM
        this.hits = if (ok) max(hits, 0) else 0
    }

    /** "2.6400;7": wheelbase (dot decimal, any locale) and hits. */
    fun encode(): String = formatFixed(wheelbaseM, 4) + ";" + hits

    companion object {
        /** A profile from [encode]'s text; null or unreadable text gives a fresh one. */
        fun decode(s: String?, cfg: VehicleConfig = VehicleConfig()): VehicleProfile {
            val p = VehicleProfile(cfg)
            val parts = s?.split(';') ?: return p
            val w = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return p
            p.restore(w, parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0)
            return p
        }
    }
}
