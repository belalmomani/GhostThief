package com.ghostthief.game.game

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract

/**
 * يقرأ **أسماء** العناصر داخل مجلد اختاره المستخدم بنفسه عبر منتقي الملفات الرسمي (SAF).
 * لا نقرأ محتوى أي ملف، ولا نحفظ الأسماء ولا نرسلها: تبقى في الذاكرة لعرضها كأماكن اختباء فقط.
 */
object HideSpots {
    private const val MAX_SCAN = 300
    private const val MAX_NAME = 14

    fun loadAsync(context: Context, treeUri: String?, onLoaded: (List<String>) -> Unit) {
        if (treeUri.isNullOrBlank()) { onLoaded(emptyList()); return }
        val app = context.applicationContext
        Thread {
            val names = try { load(app, treeUri) } catch (t: Throwable) { emptyList() }
            Handler(Looper.getMainLooper()).post { onLoaded(names) }
        }.start()
    }

    fun load(context: Context, treeUri: String): List<String> {
        val tree = Uri.parse(treeUri)
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val out = ArrayList<String>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext() && out.size < MAX_SCAN) {
                val n = c.getString(0)?.trim().orEmpty()
                if (n.isNotEmpty()) out.add(if (n.length > MAX_NAME) n.take(MAX_NAME - 1) + "…" else n)
            }
        }
        return out.distinct()
    }

    /** اسم المجلد لعرضه في الإعدادات. */
    fun folderLabel(treeUri: String?): String =
        treeUri?.let { Uri.parse(it).lastPathSegment?.substringAfterLast(':') } ?: ""
}
