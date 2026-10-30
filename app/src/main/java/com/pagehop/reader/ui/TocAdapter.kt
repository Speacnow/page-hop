package com.pagehop.reader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pagehop.reader.R

/**
 * 目录 / 书签列表的适配器。
 * 目录项按层级缩进；书签项显示"第 N 页"，长按删除。
 */
class TocAdapter(
    private val onPick: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
) : RecyclerView.Adapter<TocAdapter.VH>() {

    data class Row(
        val title: String,
        val page: Int,
        val indent: Int = 0,
        val bookmark: Boolean = false,
    )

    private val rows = ArrayList<Row>()

    fun submit(list: List<Row>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    val count: Int get() = rows.size

    class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val title: TextView = root.findViewById(R.id.tvTitle)
        val page: TextView = root.findViewById(R.id.tvPage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_toc, parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.title.text = if (row.bookmark) "第 ${row.page + 1} 页" else row.title
        holder.page.text = if (row.bookmark) "" else "${row.page + 1}"
        val dp = holder.root.resources.displayMetrics.density
        holder.title.setPadding(((14 + row.indent * 14) * dp).toInt(), 0, 0, 0)
        holder.root.setOnClickListener { onPick(row.page) }
        holder.root.setOnLongClickListener {
            if (row.bookmark) {
                onDelete(row.page)
                true
            } else {
                false
            }
        }
    }
}
