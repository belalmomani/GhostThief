package com.ghostthief.game.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ghostthief.game.BuildConfig
import com.ghostthief.game.R

/** صفحتا "سياسة الخصوصية" و"حول اللعبة". نص الخصوصية يعكس ما هو مضمّن فعلياً في هذا البناء. */
class TextPageActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val which = intent.getStringExtra(EXTRA) ?: ABOUT
        val text = if (which == PRIVACY) privacyText() else getString(R.string.about_text, BuildConfig.VERSION_NAME)
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            this.text = text; textSize = 17f; setTextColor(0xFF263238.toInt())
            setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setLineSpacing(0f, 1.2f)
        }
        title = getString(if (which == PRIVACY) R.string.privacy_policy else R.string.about)
        setContentView(ScrollView(this).apply { addView(tv) })
    }

    private fun privacyText(): String = buildString {
        appendLine("سياسة الخصوصية — Ghost Thief (نسخة أولية)\n")
        appendLine("• نجمع أقل قدر ممكن من البيانات. اللعبة لا تقرأ ملفاتك ولا أسماء ملفاتك ولا بيانات تطبيقات أخرى، ولا تراقب نشاطك خارج اللعبة. المجلدات والملفات داخل اللعبة رسوم كرتونية فقط.\n")
        appendLine("• التخزين المحلي: تُحفظ النقاط وعدد مرات القبض والإنجازات والإعدادات على جهازك فقط حتى تعمل اللعبة بلا إنترنت. يمكنك حذفها من الإعدادات ← إعادة ضبط التقدم.\n")
        appendLine("• الوضع العائم (اختياري): يستخدم إذن «العرض فوق التطبيقات الأخرى» بعد موافقتك الصريحة. يرسم النافذة الصغيرة فقط ولا يقرأ ما يظهر على الشاشة. يمكنك إيقافه في أي وقت.\n")
        if (BuildConfig.FIREBASE_CONFIGURED) {
            appendLine("• المزامنة السحابية : مفعّلة افتراضياً في هذا البناء ويمكن إيقافها من الإعدادات. نستخدم تسجيل دخول Firebase المجهول، ونحفظ في Firestore أرقام التقدم فقط (عدد مرات القبض، النقاط، رموز الإنجازات) مرتبطة بمعرّف مجهول. لا نحفظ اسماً ولا بريداً ولا موقعاً.\n")
        } else {
            appendLine("• هذا البناء غير مرتبط بخدمة سحابية: لا يتم إرسال أي بيانات خارج جهازك.\n")
        }
        if (BuildConfig.ADS_ENABLED) {
            appendLine("• الإعلانات: هذه نسخة تجريبية تتضمن SDK شبكة Start.io بوضع «موجّه للأطفال». يمكن لشخص بالغ إيقاف الإعلانات من الإعدادات. قد تجمع شبكة الإعلانات بيانات تقنية وفق سياستها الخاصة. راجع سياسة Start.io قبل أي نشر.\n")
        } else {
            appendLine("• الإعلانات: هذه النسخة لا تحتوي على أي إعلانات ولا على أي SDK إعلاني.\n")
        }
        appendLine("• وضع البحث (اختياري ومتوقف افتراضياً): عند تفعيله تختار أنت مجلداً بنفسك عبر منتقي الملفات الرسمي في Android، فتقرأ اللعبة أسماء العناصر داخله فقط وتعرضها كأماكن اختباء للحرامي. لا نقرأ محتوى أي ملف، ولا نحفظ الأسماء ولا نرسلها خارج الجهاز. يمكنك إلغاء الربط في أي وقت.\n")
        appendLine("• السرقة التمثيلية (اختيارية ومتوقفة افتراضياً): تعرض اللعبة اسم وحجم ملفات المجلد الذي اخترته، واسم وأيقونة وحجم تطبيقات اخترتها بنفسك من قائمة التطبيقات القابلة للتشغيل. تُستخدم للعرض داخل اللعبة فقط وتبقى في الذاكرة؛ نحفظ محلياً فقط أسماء حزم التطبيقات التي اخترتها، ولا نرسل شيئاً ولا نعدّل أو نحذف أي ملف أو تطبيق.\n")
        appendLine("• لا توجد عمليات شراء ولا محادثات ولا ميزات اجتماعية.\n")
        append("هذه سياسة أولية؛ راجعها وعدّلها قبل النشر على Google Play بما يناسب تطبيقك النهائي والمنطقة التي تنشر فيها.")
    }

    companion object {
        private const val EXTRA = "page"
        const val PRIVACY = "privacy"
        const val ABOUT = "about"
        fun open(ctx: Context, page: String) =
            ctx.startActivity(Intent(ctx, TextPageActivity::class.java).putExtra(EXTRA, page))
    }
}
