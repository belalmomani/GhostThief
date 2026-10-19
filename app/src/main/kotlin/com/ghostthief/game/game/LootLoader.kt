package com.ghostthief.game.game

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.core.graphics.drawable.toBitmap
import com.ghostthief.game.data.Settings
import com.ghostthief.game.logic.Loot
import java.io.File

/** أيقونات التطبيقات المختارة (في الذاكرة فقط). */
object LootCatalog {
    val icons = HashMap<String, Bitmap>()
}

/**
 * يجهّز «الغنائم» التمثيلية: أسماء وأحجام ملفات من مجلد اختاره المستخدم بمنتقي الملفات،
 * وتطبيقات اختارها المستخدم بنفسه. لا نقرأ محتوى أي ملف ولا نعدّل أو نحذف أي شيء،
 * ولا نحفظ أو نرسل شيئاً: كل شيء في الذاكرة للعرض داخل اللعبة فقط.
 */
object LootLoader {
    private const val MAX_SCAN = 300
    private const val MAX_NAME = 14

    fun loadAsync(context: Context, settings: Settings, onLoaded: (List<Loot>) -> Unit) {
        if (!settings.heistEnabled) { onLoaded(emptyList()); return }
        val app = context.applicationContext
        val uri = settings.seekFolderUri
        val pkgs = settings.heistApps
        Thread {
            val result = try { loadFiles(app, uri) + loadApps(app, pkgs) } catch (t: Throwable) { emptyList() }
            Handler(Looper.getMainLooper()).post { onLoaded(result) }
        }.start()
    }

    class ScanResult(val files: List<Loot>, val folders: Int)

    fun loadFiles(context: Context, treeUri: String?): List<Loot> = scan(context, treeUri).files

    /** يفحص المجلد المختار ومجلداته الفرعية حتى مستويين (أسماء وأحجام فقط). */
    fun scan(context: Context, treeUri: String?): ScanResult {
        if (treeUri.isNullOrBlank()) return ScanResult(emptyList(), 0)
        val tree = Uri.parse(treeUri)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val files = ArrayList<Loot>()
        var folders = 0
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(DocumentsContract.getTreeDocumentId(tree) to 0)
        var visited = 0
        try {
            while (queue.isNotEmpty() && files.size < MAX_SCAN && visited < 40) {
                val (docId, depth) = queue.removeFirst(); visited++
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext() && files.size < MAX_SCAN) {
                        val id = c.getString(0).orEmpty()
                        val n = c.getString(1)?.trim().orEmpty()
                        val mime = c.getString(3).orEmpty()
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            folders++
                            if (depth < 2) queue.add(id to depth + 1)
                        } else if (n.isNotEmpty()) {
                            val size = if (c.isNull(2)) 0L else c.getLong(2)
                            files.add(Loot(if (n.length > MAX_NAME) n.take(MAX_NAME - 1) + "…" else n, size, false))
                        }
                    }
                }
            }
        } catch (_: SecurityException) { /* الإذن سُحب */ }
        return ScanResult(files, folders)
    }

    fun loadApps(context: Context, packagesCsv: String): List<Loot> {
        val pm = context.packageManager
        val out = ArrayList<Loot>()
        for (pkg in packagesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(ai).toString().let { if (it.length > MAX_NAME) it.take(MAX_NAME - 1) + "…" else it }
                var size = File(ai.sourceDir).length()
                ai.splitSourceDirs?.forEach { size += File(it).length() }
                try { LootCatalog.icons[label] = pm.getApplicationIcon(pkg).toBitmap(96, 96) } catch (_: Throwable) {}
                out.add(Loot(label, size, true))
            } catch (_: Throwable) { /* التطبيق لم يعد مثبتاً */ }
        }
        return out
    }
}
