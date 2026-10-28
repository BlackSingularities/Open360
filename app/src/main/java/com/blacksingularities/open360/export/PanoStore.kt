package com.blacksingularities.open360.export

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Zapisana panorama z galerii aplikacji. */
data class PanoItem(val uri: Uri, val name: String, val width: Int, val height: Int, val date: Long, val size: Long)

/** Metadane GPano: gdzie w pełnej sferze leży zapisany obraz. */
data class PanoGeometry(val fullW: Int, val fullH: Int, val left: Int, val top: Int, val w: Int, val h: Int, val heading: Float = 0f) {
    /** Kadr w ułamkach pełnej sfery (x, y, w, h) – dla shadera przeglądarki. */
    fun cropFractions() = floatArrayOf(
        left.toFloat() / fullW, top.toFloat() / fullH, w.toFloat() / fullW, h.toFloat() / fullH,
    )
}

object PanoStore {
    private const val FOLDER = "Open360"
    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)

    fun save(
        context: Context,
        bmp: Bitmap,
        quality: Int,
        geometry: PanoGeometry?,
        prefix: String = "PANO",
    ): Uri? {
        val bytes = ByteArrayOutputStream(bmp.width * bmp.height / 3).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
        val data = if (geometry != null) insertXmp(bytes, xmp(geometry)) else bytes
        val name = prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
            put(MediaStore.Images.Media.WIDTH, bmp.width)
            put(MediaStore.Images.Media.HEIGHT, bmp.height)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(data) } ?: error("brak strumienia")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun xmp(g: PanoGeometry): String = """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Open360">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"
   GPano:ProjectionType="equirectangular"
   GPano:UsePanoramaViewer="True"
   GPano:CaptureSoftware="Open360"
   GPano:StitchingSoftware="Open360"
   GPano:PoseHeadingDegrees="${g.heading.roundToInt()}"
   GPano:FullPanoWidthPixels="${g.fullW}"
   GPano:FullPanoHeightPixels="${g.fullH}"
   GPano:CroppedAreaImageWidthPixels="${g.w}"
   GPano:CroppedAreaImageHeightPixels="${g.h}"
   GPano:CroppedAreaLeftPixels="${g.left}"
   GPano:CroppedAreaTopPixels="${g.top}"/>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""

    /** Wstawia segment APP1 z XMP zaraz po znaczniku SOI. */
    private fun insertXmp(jpeg: ByteArray, xmp: String): ByteArray {
        if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return jpeg
        val payload = XMP_HEADER + xmp.toByteArray(Charsets.UTF_8)
        val len = payload.size + 2
        if (len > 65535) return jpeg
        val out = ByteArrayOutputStream(jpeg.size + len + 4)
        out.write(jpeg, 0, 2)
        out.write(0xFF); out.write(0xE1)
        out.write(len shr 8); out.write(len and 0xFF)
        out.write(payload)
        out.write(jpeg, 2, jpeg.size - 2)
        return out.toByteArray()
    }

    /** Odczyt GPano z pliku (pierwsze 128 KB) – null, gdy brak. */
    fun readGeometry(context: Context, uri: Uri): PanoGeometry? = runCatching {
        val head = context.contentResolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(128 * 1024)
            var n = 0
            while (n < buf.size) {
                val r = s.read(buf, n, buf.size - n)
                if (r <= 0) break
                n += r
            }
            String(buf, 0, n, Charsets.ISO_8859_1)
        } ?: return null
        fun field(name: String): Int? {
            val re = Regex("GPano:$name(?:=\"|>)\\s*([0-9.]+)")
            return re.find(head)?.groupValues?.get(1)?.toFloatOrNull()?.roundToInt()
        }
        val fw = field("FullPanoWidthPixels") ?: return null
        val fh = field("FullPanoHeightPixels") ?: (fw / 2)
        val w = field("CroppedAreaImageWidthPixels") ?: fw
        val h = field("CroppedAreaImageHeightPixels") ?: fh
        PanoGeometry(fw, fh, field("CroppedAreaLeftPixels") ?: 0, field("CroppedAreaTopPixels") ?: 0, w, h, (field("PoseHeadingDegrees") ?: 0).toFloat())
    }.getOrNull()

    /**
     * Wczytuje zdjęcie do przeglądarki, pomniejszone do [maxW]. Gdy brak metadanych, przyjmuje
     * pełne 360° w poziomie i proporcjonalny zakres w pionie (wyśrodkowany na horyzoncie).
     */
    fun load(context: Context, uri: Uri, maxW: Int): Pair<Bitmap, PanoGeometry>? = runCatching {
        val r = context.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / sample > maxW || o.outWidth.toLong() * o.outHeight / (sample * sample) > 48_000_000L) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        if (bmp.width > maxW) {
            val s = maxW.toFloat() / bmp.width
            bmp = Bitmap.createScaledBitmap(bmp, maxW, max(1, (bmp.height * s).roundToInt()), true)
        }
        val g = readGeometry(context, uri) ?: run {
            val fullW = o.outWidth
            val fullH = max(o.outHeight, fullW / 2)
            PanoGeometry(fullW, fullH, 0, (fullH - o.outHeight) / 2, o.outWidth, o.outHeight)
        }
        Pair(bmp, g)
    }.getOrNull()

    fun list(context: Context): List<PanoItem> {
        val out = ArrayList<PanoItem>()
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
        )
        val sel = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%" + Environment.DIRECTORY_PICTURES + "/" + FOLDER + "%")
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args,
                MediaStore.Images.Media.DATE_ADDED + " DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += PanoItem(
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        c.getString(1) ?: "",
                        c.getInt(2), c.getInt(3), c.getLong(4) * 1000L, c.getLong(5),
                    )
                }
            }
        }
        return out
    }

    fun delete(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    fun thumbnail(context: Context, uri: Uri, width: Int): Bitmap? = runCatching {
        context.contentResolver.loadThumbnail(uri, android.util.Size(width, width / 2), null)
    }.getOrNull()
}
