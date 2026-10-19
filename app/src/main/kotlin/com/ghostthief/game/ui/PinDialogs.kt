package com.ghostthief.game.ui

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.ghostthief.game.R
import com.ghostthief.game.logic.ParentLock

/** نوافذ إدخال رمز الأهل. */
object PinDialogs {
    private fun input(ctx: Context) = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(6))
        gravity = Gravity.CENTER
        textSize = 24f
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        hint = "••••"
    }

    /** يطلب الرمز إن كان مفعّلاً؛ وإلا ينفّذ مباشرة. */
    fun ask(ctx: Context, lock: ParentLock, onCancel: () -> Unit = {}, onOk: () -> Unit) {
        if (!lock.isSet()) { onOk(); return }
        val et = input(ctx)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.pin_enter_title)
            .setView(et)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (lock.verify(et.text.toString())) onOk()
                else { Toast.makeText(ctx, R.string.pin_wrong, Toast.LENGTH_SHORT).show(); onCancel() }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    /** تعيين رمز جديد بخطوتين (إدخال ثم تأكيد). */
    fun setNew(ctx: Context, lock: ParentLock, onDone: () -> Unit = {}) {
        val first = input(ctx)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.pin_set_title)
            .setView(first)
            .setPositiveButton(R.string.continue_btn) { _, _ ->
                val pin = first.text.toString()
                if (!ParentLock.isValid(pin)) { Toast.makeText(ctx, R.string.pin_invalid, Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val again = input(ctx)
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.pin_confirm_title)
                    .setView(again)
                    .setPositiveButton(R.string.save) { _, _ ->
                        if (again.text.toString() == pin && lock.set(pin)) {
                            Toast.makeText(ctx, R.string.pin_saved, Toast.LENGTH_SHORT).show(); onDone()
                        } else Toast.makeText(ctx, R.string.pin_mismatch, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
