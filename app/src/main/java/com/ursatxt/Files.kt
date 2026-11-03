package com.ursatxt

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Hands one file in the app's cache to the share sheet, read-only. A file path
 * cannot leave the app on Android 7+, so the share carries this provider's address.
 */
class Files : ContentProvider() {
    companion object {
        private const val AUTH = "com.ursatxt.files"
        fun dir(c: Context) = File(c.cacheDir, "share").apply { mkdirs() }
        fun uri(f: File): Uri = Uri.Builder().scheme("content").authority(AUTH).appendPath(f.name).build()
        fun type(name: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: when (name.substringAfterLast('.').lowercase()) { "md" -> "text/markdown"; else -> "application/octet-stream" }
    }

    private fun file(u: Uri): File? {
        val c = context ?: return null
        val name = u.lastPathSegment ?: return null
        val f = File(dir(c), name)
        return if (f.parentFile == dir(c) && f.exists()) f else null
    }

    override fun onCreate() = true

    override fun openFile(u: Uri, mode: String): ParcelFileDescriptor? {
        val f = file(u) ?: throw java.io.FileNotFoundException(u.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(u: Uri, cols: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = file(u)
        val names = cols ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(names).apply {
            if (f != null) addRow(names.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null })
        }
    }

    override fun getType(u: Uri) = u.lastPathSegment?.let { type(it) }
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/**
 * Writes [bytes] to Downloads as [name] and opens the share sheet for it. Android 10+
 * goes through MediaStore, which needs no permission; older phones write the folder.
 */
fun android.app.Activity.saveAndShare(name: String, mime: String, write: (java.io.OutputStream) -> Unit): Boolean {
    val share = File(Files.dir(this), name)
    try { share.outputStream().use(write) } catch (_: Exception) { return false }
    val saved = try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            }
            val u = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            u != null && (contentResolver.openOutputStream(u)?.use { out -> share.inputStream().use { it.copyTo(out) }; true } ?: false)
        } else {
            val d = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            d.mkdirs()
            val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.')
            var k = 1
            var to = File(d, name)
            while (to.exists()) { k++; to = File(d, "$base ($k).$ext") }
            share.copyTo(to)
            true
        }
    } catch (_: Exception) { false }
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mime
        putExtra(android.content.Intent.EXTRA_STREAM, Files.uri(share))
        putExtra(android.content.Intent.EXTRA_SUBJECT, name.substringBeforeLast('.'))
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(android.content.Intent.createChooser(send, null))
    return saved
}
