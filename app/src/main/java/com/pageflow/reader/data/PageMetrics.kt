package com.pageflow.reader.data

/**
 * 一页在列表里占的高度怎么算。
 *
 * 关键点：**必须用"裁边之后"的比例**。
 * 拿 PDF 原始页比例（未裁边）排版，页高会比实际显示的高约 10%；
 * 每次重绑（换主题 / 缩放停手 / 改留白）页高都会先变一下再被渲染结果改回来，
 * 看起来就是"页面闪一下"。这个公式在 PageAdapter 和 ReaderActivity 里共用。
 */
object PageMetrics {

    const val DEFAULT_ASPECT = 1.4142f

    /**
     * @param rawPageAspect PDF 原始页面的高/宽
     * @param crop 实际显示的那块区域（页内比例）
     * @return 显示区域的高/宽
     */
    fun displayAspect(rawPageAspect: Float, crop: CropRect): Float {
        val raw = if (rawPageAspect > 0.1f) rawPageAspect else DEFAULT_ASPECT
        val c = if (crop.w > 0f && crop.h > 0f) crop else CropRect.FULL
        return raw * (c.h / c.w)
    }
}
