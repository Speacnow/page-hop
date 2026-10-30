package com.pageflow.reader.data

/**
 * 裁剪的"第二段"：检测只负责找出正文框，留白和应用规则都在这里。
 *
 * 之前把留白（1.2%/0.8%）直接写死在检测里，结果正文贴着屏幕边，
 * 看起来就是"裁过头"。现在留白独立可调，并限制单边最多裁 18%。
 */
object CropMath {

    /** 单边最多裁掉页面的这个比例，防止误判把内容裁没 */
    const val MAX_CUT_PER_SIDE = 0.18f

    /** 留白上限 10% */
    const val MAX_PAD = 0.10f

    /**
     * 把正文框按 [pad] 向外扩一圈留白，再夹到页面范围与最大裁剪比例内。
     * @param pad 页面尺寸的比例（0.02 = 2%）
     */
    fun expand(content: CropRect, pad: Float): CropRect {
        val p = pad.coerceIn(0f, MAX_PAD)

        var l = (content.l - p).coerceAtLeast(0f).coerceAtMost(MAX_CUT_PER_SIDE)
        var t = (content.t - p).coerceAtLeast(0f).coerceAtMost(MAX_CUT_PER_SIDE)
        var r = (content.r + p).coerceAtMost(1f).coerceAtLeast(1f - MAX_CUT_PER_SIDE)
        var b = (content.b + p).coerceAtMost(1f).coerceAtLeast(1f - MAX_CUT_PER_SIDE)

        // 极端情况下别把页面裁得太小
        if (r - l < 0.3f) {
            val mid = (l + r) / 2f
            l = (mid - 0.15f).coerceAtLeast(0f)
            r = (mid + 0.15f).coerceAtMost(1f)
        }
        if (b - t < 0.3f) {
            val mid = (t + b) / 2f
            t = (mid - 0.15f).coerceAtLeast(0f)
            b = (mid + 0.15f).coerceAtMost(1f)
        }
        return CropRect(l, t, r, b)
    }
}
