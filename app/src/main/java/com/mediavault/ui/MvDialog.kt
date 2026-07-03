package com.mediavault.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputEditText
import com.mediavault.R

object MvDialog {
    fun builder(context: Context): AlertDialog.Builder =
        AlertDialog.Builder(context, R.style.ThemeOverlay_MediaVault_AlertDialog)

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
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                styleInputField(view.getChildAt(i))
            }
        }
    }

    private fun applyDialogChrome(dialog: AlertDialog, inputRoot: View?, focusPositiveButton: Boolean) {
        val ctx = dialog.context
        val bg = ctx.getColor(R.color.mv_dialog_bg)
        val text = ctx.getColor(R.color.mv_text)
        val textSecondary = ctx.getColor(R.color.mv_text_secondary)
        dialog.window?.setBackgroundDrawable(ColorDrawable(bg))
        dialog.findViewById<TextView>(android.R.id.title)?.setTextColor(text)
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(textSecondary)
        inputRoot?.let {
            it.setBackgroundColor(bg)
            styleInputField(it)
        }
        dialog.listView?.let { list -> styleListView(list, bg, text) }
        if (focusPositiveButton && dialog.listView == null && inputRoot == null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.post {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
            }
        }
    }

    private fun styleListView(list: ListView, bg: Int, text: Int) {
        list.setBackgroundColor(bg)
        list.cacheColorHint = bg
        val ctx = list.context
        list.divider = ColorDrawable(ctx.getColor(R.color.mv_line))
        list.dividerHeight = ctx.resources.getDimensionPixelSize(R.dimen.mv_dialog_list_divider)
        list.isFocusable = true
        list.isFocusableInTouchMode = true
        for (i in 0 until list.childCount) {
            (list.getChildAt(i) as? TextView)?.setTextColor(text)
        }
        list.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                (child as? TextView)?.setTextColor(text)
            }
            override fun onChildViewRemoved(parent: View?, child: View?) {}
        })
        list.post {
            if (list.count > 0 && !list.hasFocus()) {
                list.setSelection(0)
                list.requestFocus()
            }
        }
    }

    fun showStyled(
        builder: AlertDialog.Builder,
        inputRoot: View? = null,
        focusPositiveButton: Boolean = false,
    ): AlertDialog {
        val dialog = builder.create()
        dialog.setOnShowListener { applyDialogChrome(dialog, inputRoot, focusPositiveButton) }
        dialog.show()
        return dialog
    }

    fun show(
        builder: AlertDialog.Builder,
        inputRoot: View? = null,
        focusPositiveButton: Boolean = false,
    ): AlertDialog =
        showStyled(builder, inputRoot, focusPositiveButton)
}
