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

    fun loadFiles(context: Context, treeUri: String?): List<Loot> {
        if (treeUri.isNullOrBlank()) return emptyList()
        val tree = Uri.parse(treeUri)
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val out = ArrayList<Loot>()
        try {
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < MAX_SCAN) {
                    val mime = c.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val n = c.getString(0)?.trim().orEmpty()
                    if (n.isEmpty()) continue
                    val size = if (c.isNull(1)) 0L else c.getLong(1)
                    out.add(Loot(if (n.length > MAX_NAME) n.take(MAX_NAME - 1) + "…" else n, size, false))
                }
            }
        } catch (_: SecurityException) { /* الإذن سُحب */ }
        return out
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
