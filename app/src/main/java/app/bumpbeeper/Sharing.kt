package app.bumpbeeper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sharing your data with other people and other apps, and taking in what others share with you.
 *
 *  • Bump file (CSV): opens in Excel / Google Sheets, and in Bump Beeper on another phone (it merges into their map).
 *  • Map file (KML): opens in Google Earth and imports into Google My Maps, with coloured pins.
 *  • Trip report: plain text for WhatsApp / email.
 *  • Receiving: tapping a shared bump file (WhatsApp, Gmail, Files, Drive…) opens Bump Beeper and offers to import it.
 */
object Sharing {
    private val ui = Handler(Looper.getMainLooper())

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())

    /** Asks what to share, then opens Android's share sheet. */
    fun chooseAndShare(a: Activity) {
        val options = arrayOf(
            a.getString(R.string.share_opt_bumps),
            a.getString(R.string.share_opt_kml),
            a.getString(R.string.share_opt_trips),
        )
        AlertDialog.Builder(a)
            .setTitle(a.getString(R.string.share_choose_title))
            .setItems(options) { _, i ->
                when (i) {
                    0 -> shareBumpsCsv(a)
                    1 -> shareKml(a)
                    2 -> shareTripsCsv(a)
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    fun shareBumpsCsv(a: Activity) = shareGenerated(a, "bumps_shared_${stamp()}.csv", "text/csv", a.getString(R.string.share_subject_map)) { db ->
        db.bumpsCsv(Prefs.engineConfig(a))
    }

    fun shareTripsCsv(a: Activity) = shareGenerated(a, "trips_${stamp()}.csv", "text/csv", a.getString(R.string.share_subject_trips)) { db -> db.tripsCsv() }

    fun shareKml(a: Activity) = shareGenerated(
        a, "bumps_map_${stamp()}.kml", "application/vnd.google-earth.kml+xml", a.getString(R.string.share_subject_map),
    ) { db -> kml(db.loadBumps(), Prefs.engineConfig(a)) }

    /** A short trip report as text (WhatsApp, email…). */
    fun shareTrip(a: Activity, t: BumpDb.TripRow) {
        val d = t.drive
        val date = SimpleDateFormat("EEE d MMM, HH:mm", Locale.US).format(Date(t.startTs))
        val score = if (t.score >= 0) a.getString(R.string.share_trip_score_value, t.score, DrivingStats.grade(t.score)) else a.getString(R.string.share_trip_not_scored)
        val text = buildString {
            append(a.getString(R.string.share_trip_head, date)).append("\n")
            append(a.getString(R.string.share_trip_score, score)).append("\n")
            append(a.getString(R.string.share_trip_summary, Ui.km(d.distanceM), Ui.duration(t.durationS), d.avgSpeedKmh.toInt(), d.maxSpeedKmh.toInt())).append("\n")
            append(a.getString(R.string.share_trip_speeding, String.format(Locale.US, "%.0f", d.speedingShare * 100))).append("\n")
            append(a.getString(R.string.share_trip_harsh, d.harshBrakes, d.harshAccels, d.harshCorners, d.swerves)).append("\n")
            append(a.getString(R.string.share_trip_bumps_fast, d.bumpsFast, d.phoneUse)).append("\n")
            append(a.getString(R.string.share_trip_totals, t.hits, t.potholes, t.beeps))
        }
        a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, a.getString(R.string.share_trip_chooser)))
    }

    private fun shareGenerated(a: Activity, name: String, mime: String, subject: String, make: (BumpDb) -> String) {
        Thread {
            val db = BumpDb(a.applicationContext)
            val content = try { make(db) } finally { db.close() }
            val uri = CsvExport.saveUri(a, name, content, mime = mime)
            ui.post {
                if (uri == null) { Toast.makeText(a, a.getString(R.string.share_file_failed), Toast.LENGTH_LONG).show(); return@post }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                a.startActivity(Intent.createChooser(send, a.getString(R.string.share_via)))
                Toast.makeText(a, a.getString(R.string.share_copy_saved), Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    // ---------------------------------------------------------------- KML (Google Earth / My Maps)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun kml(bumps: List<Bump>, cfg: EngineConfig): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
        append("<name>Bump Beeper map</name>\n")
        // KML colours are aabbggrr.
        for ((id, color) in listOf("bump" to "ff26a7ff", "pothole" to "ff5053ef", "harsh" to "ff2828b7", "unsure" to "ffc5beb0", "muted" to "80808080")) {
            append("<Style id=\"$id\"><IconStyle><color>$color</color><scale>1.0</scale>")
            append("<Icon><href>http://maps.google.com/mapfiles/kml/paddle/wht-blank.png</href></Icon></IconStyle></Style>\n")
        }
        for (b in bumps) {
            val style = when {
                b.isMuted(cfg) -> "muted"
                b.isHarsh(cfg) -> "harsh"
                b.kind == BumpKind.POTHOLE -> "pothole"
                b.kind == BumpKind.BUMP -> "bump"
                else -> "unsure"
            }
            val name = when (style) { "harsh" -> "Harsh pothole"; "pothole" -> "Pothole"; "bump" -> "Speed bump"; "muted" -> "Muted spot"; else -> "Bump (unsure)" }
            val side = if (b.kind == BumpKind.POTHOLE && b.side != Side.UNKNOWN) ", ${b.side.label}" else ""
            val desc = String.format(
                Locale.US, "%s%s. Felt %d of %d passes. Direction of travel %.0f°. Average jolt %.1f m/s².",
                name, side, b.hits, b.passes, b.heading, b.peakAvg,
            )
            append("<Placemark><name>${esc("$name #${b.id}")}</name><description>${esc(desc)}</description>")
            append("<styleUrl>#$style</styleUrl><Point><coordinates>")
            append(String.format(Locale.US, "%.7f,%.7f,0", b.lon, b.lat))
            append("</coordinates></Point></Placemark>\n")
        }
        append("</Document></kml>\n")
    }

    // ---------------------------------------------------------------- receiving

    /** If [intent] carries a shared file (Open with / Share to Bump Beeper), offer to import it. Returns true if it did. */
    fun handleIncoming(a: Activity, intent: Intent?, onImported: () -> Unit): Boolean {
        if (intent == null) return false
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri == null) return false
        intent.action = null   // handle once, not again after rotating the screen
        importUri(a, uri, confirm = true, onImported = onImported)
        return true
    }

    /** Reads a bump file and merges it into the map (asking first if [confirm]). */
    fun importUri(a: Activity, uri: Uri, confirm: Boolean, onImported: () -> Unit) {
        Thread {
            val text = try {
                a.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            } catch (e: Exception) { "" }
            val header = text.lineSequence().firstOrNull()?.lowercase(Locale.US)?.removePrefix("﻿") ?: ""
            val looksRight = header.contains("lat") && header.contains("lon") && header.contains("heading")
            val rows = text.lineSequence().count { it.isNotBlank() } - 1
            ui.post {
                if (!looksRight) {
                    Toast.makeText(a, a.getString(R.string.share_not_bump_file), Toast.LENGTH_LONG).show()
                    return@post
                }
                val go = { doImport(a, text, onImported) }
                if (!confirm) { go(); return@post }
                AlertDialog.Builder(a)
                    .setTitle(a.getString(R.string.share_import_title))
                    .setMessage(a.getString(R.string.share_import_msg, rows))
                    .setPositiveButton(R.string.share_import_button) { _, _ -> go() }
                    .setNegativeButton(R.string.common_cancel, null)
                    .show()
            }
        }.start()
    }

    private fun doImport(a: Activity, text: String, onImported: () -> Unit) {
        Thread {
            val db = BumpDb(a.applicationContext)
            val (added, dup, bad) = try { db.importBumpsCsv(text) } finally { db.close() }
            ui.post {
                val later = if (LiveState.recording) a.getString(R.string.share_imported_later) else ""
                Toast.makeText(
                    a, a.getString(R.string.share_imported, added, dup) + (if (bad > 0) a.getString(R.string.share_imported_bad, bad) else "") + "." + later,
                    Toast.LENGTH_LONG,
                ).show()
                onImported()
            }
        }.start()
    }
}
