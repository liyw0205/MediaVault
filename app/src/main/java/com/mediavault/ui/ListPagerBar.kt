package com.mediavault.ui

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.mediavault.R

/**
 * 列表分页条（上一页 / 下一页 / 页码），与首页 [HomeFragment] 行为一致。
 */
class ListPagerBar(
    private val root: View,
    private val pageSize: Int = LibraryUi.PAGE_SIZE,
) {
    private val pager = root.findViewById<View>(R.id.listPager)
    private val prev = root.findViewById<MaterialButton>(R.id.listPrevPageBtn)
    private val next = root.findViewById<MaterialButton>(R.id.listNextPageBtn)
    private val info = root.findViewById<TextView>(R.id.listPageInfo)

    var page: Int = 1
        private set

    private var totalItems: Int = 0
    private var onPageChanged: (() -> Unit)? = null
    private var jumpDialogHost: Fragment? = null

    init {
        prev.setOnClickListener {
            if (page > 1) {
                page--
                onPageChanged?.invoke()
            }
        }
        next.setOnClickListener {
            val pages = LibraryUi.pageCount(totalItems, pageSize)
            if (page < pages) {
                page++
                onPageChanged?.invoke()
            }
        }
    }

    fun bindHost(fragment: Fragment) {
        jumpDialogHost = fragment
        info.setOnClickListener { showJumpDialog() }
    }

    fun setOnPageChanged(listener: () -> Unit) {
        onPageChanged = listener
    }

    fun resetPage() {
        page = 1
    }

    fun restorePage(saved: Int) {
        page = saved.coerceAtLeast(1)
    }

    /** @param itemCount 当前列表总条数 */
    fun update(itemCount: Int, enabled: Boolean) {
        totalItems = itemCount
        val pages = LibraryUi.pageCount(itemCount, pageSize)
        if (page > pages) page = pages
        if (!enabled || itemCount <= pageSize) {
            pager.visibility = View.GONE
            return
        }
        pager.visibility = View.VISIBLE
        info.text = root.context.getString(R.string.page_fmt, page, pages)
        prev.isEnabled = page > 1
        next.isEnabled = page < pages
        info.isClickable = jumpDialogHost != null && pages > 1
    }

    fun <T> slice(full: List<T>): List<T> {
        val start = (page - 1) * pageSize
        if (start >= full.size) return emptyList()
        return full.subList(start, minOf(start + pageSize, full.size))
    }

    private fun showJumpDialog() {
        val frag = jumpDialogHost ?: return
        val pages = LibraryUi.pageCount(totalItems, pageSize)
        if (pages <= 1) return
        val input = EditText(frag.requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(page.toString())
            setSelection(text?.length ?: 0)
        }
        input.setTextColor(ContextCompat.getColor(frag.requireContext(), R.color.mv_text))
        input.setHintTextColor(ContextCompat.getColor(frag.requireContext(), R.color.mv_muted))
        MvDialog.showStyled(
            MvDialog.builder(frag.requireContext())
                .setTitle(R.string.page_jump_title)
                .setMessage(frag.getString(R.string.page_jump_hint, pages))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val n = input.text?.toString()?.toIntOrNull() ?: return@setPositiveButton
                    page = n.coerceIn(1, pages)
                    onPageChanged?.invoke()
                }
                .setNegativeButton(android.R.string.cancel, null),
            inputRoot = input,
        )
    }
}