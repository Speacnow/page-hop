package com.pagehop.reader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pagehop.reader.R

import com.pagehop.reader.pdf.PdfDoc
import com.pagehop.reader.pdf.RenderEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 目录面板里的"缩略图"页签：单列、从上到下一页一张，点一下跳过去。
 * 不复用网格版的 ThumbAdapter —— 那个是按"格子里塞满宽度"设计的，
 * 塞进半屏的竖列表里尺寸会算错。
 */
class PageRowAdapter(
    private val doc: PdfDoc,
    private val docId: String,
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<PageRowAdapter.VH>() {

    /** 由面板布局完成后设置：行宽（不然会退化成整屏宽度，行就过高、上下留黑） */
    var rowWidth: Int = 0

    /** 固定格子高度（>0 时优先）：2×3 的历史缩略图弹窗用 */
    var fixedCellHeight: Int = 0

    var currentPage: Int = 0
        set(value) {
            val old = field
            field = value
            if (old != value) {
                notifyItemChanged(old)
                notifyItemChanged(value)
            }
        }

    /** 非空时只显示这些页（目录项 / 书签 / 历史记录），否则显示全书 */
    private var onlyPages: List<Int>? = null

    fun showPages(pages: List<Int>) {
        onlyPages = pages
        notifyDataSetChanged()
    }

    /** 显示整本书（目录面板的缩略图页签）；返回是否真的换了数据 */
    fun showAll(): Boolean {
        if (onlyPages == null) return false
        onlyPages = null
        notifyDataSetChanged()
        return true
    }

    fun showOnly(pages: List<Int>) = showPages(pages)

    private fun pageAt(position: Int): Int = onlyPages?.getOrNull(position) ?: position

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.imgRow)
        val num: TextView = v.findViewById(R.id.tvRowNum)
        var job: Job? = null
        var bound = -1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_page_row, parent, false))

    override fun getItemCount(): Int = onlyPages?.size ?: doc.pageCount

    override fun onBindViewHolder(holder: VH, position: Int) {
        val page = pageAt(position)
        holder.img.setColorFilter(ColorModes.filter(ColorModes.active))
        holder.num.text = "${page + 1}"
        holder.num.setTextColor(
            if (page == currentPage) 0xFFFF5252.toInt() else 0xFFFFFFFF.toInt(),
        )
        holder.itemView.setOnClickListener { onPick(page) }

        // 高度按面板宽度和页面比例算，保证整页都能看到
        val rvWidth = (holder.itemView.parent as? RecyclerView)?.width ?: 0
        val w = when {
            rvWidth > 0 -> rvWidth
            rowWidth > 0 -> rowWidth
            else -> holder.itemView.resources.displayMetrics.widthPixels
        }
        val h = if (fixedCellHeight > 0) fixedCellHeight else (w * 1.36f).toInt()
        val lp = holder.img.layoutParams
        if (lp.height != h) {
            lp.height = h
            holder.img.layoutParams = lp
        }

        if (holder.bound == page && holder.img.drawable != null) return
        holder.bound = page
        holder.job?.cancel()
        holder.job = scope.launch {
            val bmp = RenderEngine.thumb(doc, docId, page, 480)
            if (bmp != null && holder.bound == page) {
                holder.img.setImageBitmap(bmp)
            }
        }
    }

    fun release() {
        scope.cancel()
    }
}
