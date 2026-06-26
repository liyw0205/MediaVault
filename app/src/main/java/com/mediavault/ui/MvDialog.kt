package com.mediavault.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputEditText
import com.mediavault.R

object MvDialog {
    fun builder(context: Context): AlertDialog.Builder =
        AlertDialog.Builder(context, R.style.ThemeOverlay_MediaVault_AlertDialog)

    /** 弹窗内 EditText / TextInput 前景色 */
    fun styleInputField(view: View) {
        when (view) {
            is EditText -> {
                view.setTextColor(view.context.getColor(R.color.mv_text))
                view.setHintTextColor(view.context.getColor(R.color.mv_hint))
            }
            is TextInputEditText -> {
                view.setTextColor(view.context.getColor(R.color.mv_text))
                view.setHintTextColor(view.context.getColor(R.color.mv_hint))
            }
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                styleInputField(view.getChildAt(i))
            }
        }
    }

    fun showStyled(builder: AlertDialog.Builder, inputRoot: View? = null): AlertDialog {
        val dialog = builder.create()
        dialog.setOnShowListener {
            inputRoot?.let { styleInputField(it) }
            dialog.window?.setBackgroundDrawable(
                ColorDrawable(dialog.context.getColor(R.color.mv_dialog_bg)),
            )
        }
        dialog.show()
        return dialog
    }
}