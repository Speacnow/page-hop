package com.pageflow.reader.pdf

import android.graphics.Bitmap
import android.util.LruCache
import com.pageflow.reader.data.CropMath
import com.pageflow.reader.data.CropRect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * 一页的渲染结果。
 * @param crop 显示用的裁剪框（已含留白）
 * @param content 检测出来的正文框（未加留白），用于回写缓存
 */
data class RenderedPage(
    val bitmap: Bitmap,
    val crop: CropRect?,
    val content: CropRect?,
    val aspect: Float,
)

/**
 * PDF 渲染调度：pdfium 全局有锁，所有渲染串行放在一个后台线程里做，
 * 主线程只拿结果。位图用按字节计量的 LruCache 缓存。
 */
object RenderEngine {

    val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "pdfium-render").apply { priority = Thread.NORM_PRIORITY - 1 }
        }.asCoroutineDispatcher()

    private const val CACHE_BYTES = 96 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 载入一页。
     *
     * @param cropHint 已知的显示裁剪框；null 表示未知
     * @param analyze 是否需要在低分辨率下检测正文框
     * @param pad 留白比例（页面尺寸的百分比）
     */
    suspend fun loadPage(
        doc: PdfDoc,
        docId: String,
        page: Int,
        screenWidth: Int,
        cropHint: CropRect?,
        analyze: Boolean,
        pad: Float,
    ): RenderedPage? = withContext(dispatcher) {
        try {
            var display = cropHint
            var content: CropRect? = null

            if (analyze) {
                content = analyzeCrop(doc, page)
                display = content?.let { CropMath.expand(it, pad) } ?: CropRect.FULL
            }

            val c = display ?: CropRect.FULL
            val contentW = c.w.coerceIn(0.3f, 1f)

            // 裁边之后正文要铺满屏宽，所以按比例反向放大渲染分辨率
            val wanted = (screenWidth / contentW).toInt()
                .coerceIn(screenWidth, (screenWidth * 1.55f).toInt())
            val bucket = (wanted / 16) * 16

            // 留白比例会影响最终裁剪框，缓存键要带上，否则改留白不生效
            val key = "$docId#$page#$bucket#${(pad * 1000).toInt()}"
            val bmp = cache.get(key) ?: doc.render(page, bucket)?.also { cache.put(key, it) }
                ?: return@withContext null

            val aspect = (bmp.height.toFloat() / bmp.width.toFloat()) * (c.h / c.w)
            RenderedPage(bmp, display, content, aspect)
        } catch (e: Throwable) {
            null
        }
    }

    suspend fun thumb(doc: PdfDoc, docId: String, page: Int, width: Int): Bitmap? =
        withContext(dispatcher) {
            val key = "$docId#$page#t$width"
            thumbCache.get(key) ?: doc.render(page, width)?.also { thumbCache.put(key, it) }
        }

    suspend fun pageAspects(doc: PdfDoc): FloatArray = withContext(dispatcher) {
        val n = doc.pageCount
        val out = FloatArray(n) { 1.4142f }
        for (i in 0 until n) {
            out[i] = try { doc.pageAspect(i) } catch (e: Exception) { 1.4142f }
        }
        out
    }

    /** 低分辨率检测正文框；失败返回 null（表示这本页不裁） */
    private fun analyzeCrop(doc: PdfDoc, page: Int): CropRect? {
        val small = doc.render(page, 360) ?: return null
        return try {
            CropDetector.detect(small)
        } finally {
            if (!small.isRecycled) small.recycle()
        }
    }
}
