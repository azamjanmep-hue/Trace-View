package com.traceview.app

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** Decodes, orients, downsizes and stores the reference image. */
object ImageLoader {

    private val ALLOWED_TYPES = setOf("image/jpeg", "image/jpg", "image/pjpeg", "image/png", "image/webp")
    private val ALLOWED_EXT = listOf(".jpg", ".jpeg", ".png", ".webp")
    private const val FILE_NAME = "reference_image"
    private const val MAX_SIDE = 2560

    fun isAllowed(cr: ContentResolver, uri: Uri): Boolean {
        val type = cr.getType(uri)?.lowercase()
        if (type != null && type in ALLOWED_TYPES) return true
        val path = uri.lastPathSegment?.lowercase() ?: return false
        return ALLOWED_EXT.any { path.endsWith(it) }
    }

    fun decode(cr: ContentResolver, uri: Uri): Bitmap? {
        // 1) read dimensions only
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val s1 = cr.openInputStream(uri) ?: return null
        s1.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 2) decode at a size that stays sharp but memory-safe
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val s2 = cr.openInputStream(uri) ?: return null
        val bmp = s2.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        // 3) respect camera EXIF orientation (photos taken in portrait)
        val orientation = try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return applyExif(bmp, orientation)
    }

    private fun applyExif(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /** Keeps the last reference image so it is restored next launch. PNG keeps transparency. */
    fun save(ctx: Context, bmp: Bitmap) {
        val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use {
            if (bmp.hasAlpha()) bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            else bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
        val dest = File(ctx.filesDir, FILE_NAME)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    fun load(ctx: Context): Bitmap? {
        val f = File(ctx.filesDir, FILE_NAME)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }
}
