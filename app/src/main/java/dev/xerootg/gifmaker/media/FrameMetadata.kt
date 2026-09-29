package dev.xerootg.gifmaker.media

import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dev.xerootg.gifmaker.model.DateSource
import dev.xerootg.gifmaker.model.FrameItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Builds a [FrameItem] for a content URI: image bounds, EXIF orientation and the best available
 * capture date. Date precedence: EXIF DateTimeOriginal → gallery "date taken" → a timestamp in the
 * file name → file modification time → none.
 */
object FrameMetadata {
    private val nameDateTime = Regex("""((?:19|20)\d{2})(\d{2})(\d{2})[-_ .]?(\d{2})(\d{2})(\d{2})""")
    private val nameDate = Regex("""((?:19|20)\d{2})[-_.]?(\d{2})[-_.]?(\d{2})""")

    fun load(context: Context, uri: Uri, id: Long, pickIndex: Int): FrameItem? {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        } catch (_: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var rotation = 0
        var flipped = false
        var exifMillis: Long? = null
        var exifRaw: String? = null
        try {
            resolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                rotation = exif.rotationDegrees
                flipped = exif.isFlipped
                exifMillis = exif.dateTimeOriginal ?: exif.dateTimeDigitized ?: exif.dateTime
                exifRaw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            }
        } catch (_: Exception) {
            // Not a JPEG with usable EXIF; fall through to the other sources.
        }

        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "image"
        var dateTaken: Long? = null
        var modified: Long? = null
        try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.string("_display_name")?.let { name = it }
                    dateTaken = c.long("datetaken")?.takeIf { it > 0 }
                    modified = c.long("last_modified")?.takeIf { it > 0 }
                        ?: c.long("date_modified")?.takeIf { it > 0 }?.let { it * 1000 }
                }
            }
        } catch (_: Exception) {
            // Provider refused the query; the name/date fallbacks below still apply.
        }

        val (millis, source) = when {
            exifMillis != null -> exifMillis to DateSource.EXIF
            dateTaken != null -> dateTaken to DateSource.MEDIA_STORE
            else -> {
                val fromName = dateFromName(name)
                when {
                    fromName != null -> fromName to DateSource.FILENAME
                    modified != null -> modified to DateSource.MODIFIED
                    else -> null to DateSource.NONE
                }
            }
        }

        val label = when (source) {
            DateSource.EXIF -> exifRaw?.let(::prettyExif) ?: format(millis!!)
            DateSource.NONE -> "no date"
            else -> format(millis!!)
        }

        return FrameItem(
            id = id,
            uri = uri,
            name = name,
            dateMillis = millis,
            dateLabel = label,
            dateSource = source,
            rawWidth = bounds.outWidth,
            rawHeight = bounds.outHeight,
            rotation = rotation,
            flipped = flipped,
            pickIndex = pickIndex,
        )
    }

    private fun Cursor.string(column: String): String? {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getString(i) else null
    }

    private fun Cursor.long(column: String): Long? {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getLong(i) else null
    }

    /** "2024:01:05 13:22:10" → "2024-01-05 13:22:10". */
    private fun prettyExif(raw: String): String {
        val parts = raw.trim().split(' ')
        if (parts.size != 2) return raw
        return parts[0].replace(':', '-') + " " + parts[1]
    }

    private fun format(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(millis)

    internal fun dateFromName(name: String): Long? {
        nameDateTime.find(name)?.let { m ->
            val (y, mo, d, h, mi, s) = m.destructured
            toMillis(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())?.let { return it }
        }
        nameDate.find(name)?.let { m ->
            val (y, mo, d) = m.destructured
            toMillis(y.toInt(), mo.toInt(), d.toInt(), 0, 0, 0)?.let { return it }
        }
        return null
    }

    private fun toMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long? {
        if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59 || s !in 0..59) return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.isLenient = false
        cal.clear()
        cal.set(y, mo - 1, d, h, mi, s)
        return try {
            cal.timeInMillis
        } catch (_: Exception) {
            null
        }
    }
}
