package com.pagehop.reader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pagehop.reader.R
import com.pagehop.reader.data.ColorModeKey
import com.pagehop.reader.pdf.PdfDoc
import com.pagehop.reader.pdf.RenderEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 缩略图地图的格子。格子尺寸由外层按列数算好传进来（[cellWidth]/[cellHeight]），
 * 图片用 centerCrop 铺满，所以整张"地图"是无缝的。
 *
 * 缩放时只做"重排不重加载"：同一页的位图保留着，新尺寸的图到位后再替换，
 * 否则每次改列数都会先白一下再出图，也就是用户说的"一闪一闪"。
 */
class ThumbAdapter(
    private val doc: PdfDoc,
    private val docId: String,
    private val colorMode: ColorModeKey,
    /** 阅读器当前所在页（0 基），会高亮出来 */
    private val currentPage: Int,
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<ThumbAdapter.VH>() {

    var cellWidth: Int = 200
    var cellHeight: Int = 272

    /** 长按预览时把右下角的页码角标藏起来，免得挡住内容 */
    var hideNumbers: Boolean = false
    var focusPage: Int = -1
        set(value) {
            if (field == value) return
            val old = field
            field = value
            // focusPage 为 -1 时高亮的是 currentPage，切换时它也要重绑，
            // 否则旧的那格会留着一个已经不该高亮的红框。
            arrayOf(old, currentPage, value).filter { it >= 0 }.distinct()
                .forEach { notifyItemChanged(it) }
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val img: ImageView = root.findViewById(R.id.imgThumb)
        val num: TextView = root.findViewById(R.id.tvNum)
        val current: View = root.findViewById(R.id.vCurrent)
        val currentTint: View = root.findViewById(R.id.vCurrentTint)
        var job: Job? = null
        var boundPosition: Int = -1
        var renderedWidth: Int = 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_thumb, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = doc.pageCount

    override fun onBindViewHolder(holder: VH, position: Int) {
        val lp = holder.root.layoutParams
        if (lp.height != cellHeight) {
            lp.height = cellHeight
            holder.root.layoutParams = lp
        }
        // 图片给死尺寸：置为 match_parent 时 setImageBitmap 会让 ImageView 请求重新布局，
        // 缩略图一张张到货就变成"连续几十次重排"，列表位置会被一路带偏（跳到第 1 页）。
        val ilp = holder.img.layoutParams
        if (ilp.width != cellWidth || ilp.height != cellHeight) {
            ilp.width = cellWidth
            ilp.height = cellHeight
            holder.img.layoutParams = ilp
        }

        // 配色跟随正文的颜色模式：夜间阅读时缩略图也得是暗的，否则整屏刺眼、风格割裂
        holder.img.setColorFilter(ColorModes.filter(colorMode))
        holder.root.setBackgroundColor(ColorModes.background(colorMode))

        // 高亮：长按预览松手后就只标那一页，否则标"当前阅读页"
        val isCurrent = if (focusPage >= 0) position == focusPage else position == currentPage
        holder.current.visibility = if (isCurrent) View.VISIBLE else View.GONE
        holder.currentTint.visibility = if (isCurrent) View.VISIBLE else View.GONE
        holder.num.visibility = if (hideNumbers) View.GONE else View.VISIBLE

        // 换了页才清空；同一页因密度变化重绑时保留旧图，避免闪白
        if (holder.boundPosition != position) {
            holder.boundPosition = position
            holder.renderedWidth = 0
            holder.img.setImageBitmap(null)
            holder.num.text = (position + 1).toString()
        }
        holder.root.setOnClickListener { onPick(position) }

        val w = (cellWidth / 32 * 32).coerceIn(128, 512)
        if (holder.renderedWidth == w && holder.img.drawable != null) return

        holder.job?.cancel()
        holder.renderedWidth = w
        holder.job = scope.launch {
            val bmp = RenderEngine.thumb(doc, docId, position, w)
            if (bmp != null && holder.bindingAdapterPosition == position) {
                holder.img.setImageBitmap(bmp)
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.job?.cancel()
        holder.job = null
        holder.boundPosition = -1
        holder.renderedWidth = 0
        holder.img.setImageBitmap(null)
        super.onViewRecycled(holder)
    }

    fun release() {
        scope.cancel()
    }
}
