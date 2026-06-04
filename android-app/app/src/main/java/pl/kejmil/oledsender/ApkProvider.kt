package pl.kejmil.oledsender

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class ApkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolveApkFile(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = resolveApkFile(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf(file.name, file.length()))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun resolveApkFile(uri: Uri): File {
        val context = requireNotNull(context)
        val name = requireNotNull(uri.lastPathSegment) { "Missing APK name" }
        require(name.endsWith(".apk") && !name.contains('/') && !name.contains('\\')) {
            "Invalid APK name"
        }
        val dir = File(context.cacheDir, UPDATE_DIR).canonicalFile
        val file = File(dir, name).canonicalFile
        require(file.path.startsWith(dir.path) && file.isFile) { "APK not found" }
        return file
    }

    companion object {
        const val UPDATE_DIR = "updates"

        fun uriFor(context: Context, fileName: String): Uri {
            return Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.apkprovider")
                .appendPath(fileName)
                .build()
        }
    }
}
