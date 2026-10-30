package com.pagehop.reader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfiumCore

/**
 * pdfium 的一层薄包装：打开文档、按目标宽度渲染成 Bitmap。
 * 所有方法都必须在 RenderEngine 的单线程里调用（pdfium 不是线程安全的）。
 */
class PdfDoc private constructor(
    private val core: PdfiumCore,
    private val pfd: ParcelFileDescriptor,
    private val document: PdfDocument,
) : AutoCloseable {

    val pageCount: Int get() = document.getPageCount()

    /** 整页的 高/宽 比例（已考虑 /Rotate） */
    fun pageAspect(index: Int): Float {
        val page = document.openPage(index)
        try {
            return aspectOf(page)
        } finally {
            page.close()
        }
    }

    private fun aspectOf(page: PdfPage): Float {
        var w = 0f
        var h = 0f
        try {
            val box = page.getPageBoundingBox()
            w = box.width()
            h = box.height()
        } catch (e: Exception) {
            // ignore
        }
        if (w <= 1f || h <= 1f) {
            val s = page.getPageSize(72)
            w = s.width.toFloat()
            h = s.height.toFloat()
        }
        val rot = try { page.getPageRotation() } catch (e: Exception) { 0 }
        if (rot == 1 || rot == 3) {
            val t = w; w = h; h = t
        }
        if (w <= 1f) return 1.4142f
        return (h / w).coerceIn(0.2f, 6f)
    }

    /** 渲染整页到指定宽度的 Bitmap；失败返回 null */
    /** 目录条目：标题 + 目标页（0 基）+ 层级（0 是顶级） */
    data class TocItem(val title: String, val page: Int, val depth: Int)

    /** 读 PDF 自带的书签/目录树，转成扁平列表 */
    fun tableOfContents(): List<TocItem> {
        val out = ArrayList<TocItem>()
        val roots = runCatching { core.getTableOfContents(document) }.getOrNull()
        fun walk(list: List<PdfDocument.Bookmark>?, depth: Int) {
            if (list == null || depth > 8) return
            for (b in list) {
                val title = b.title?.trim().orEmpty()
                val page = b.pageIdx.toInt()
                if (title.isNotEmpty()) out.add(TocItem(title, page, depth))
                walk(b.children, depth + 1)
            }
        }
        walk(roots, 0)
        return out
    }

    fun render(index: Int, targetWidth: Int): Bitmap? {
        var w = targetWidth.coerceAtLeast(64)
        val page = document.openPage(index)
        try {
            val aspect = aspectOf(page)
            var h = (w * aspect).toInt().coerceAtLeast(64)

            // 防止超大页面把内存吃光
            val maxPixels = 14_000_000L
            val pixels = w.toLong() * h
            if (pixels > maxPixels) {
                val s = Math.sqrt(maxPixels.toDouble() / pixels.toDouble()).toFloat()
                w = (w * s).toInt().coerceAtLeast(64)
                h = (h * s).toInt().coerceAtLeast(64)
            }

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.renderPageBitmap(
                bmp, 0, 0, w, h,
                renderAnnot = false,
                textMask = false,
                canvasColor = 0,
                pageBackgroundColor = 0xFFFFFFFF.toInt(),
            )
            return bmp
        } catch (e: Throwable) {
            return null
        } finally {
            runCatching { page.close() }
        }
    }

    override fun close() {
        runCatching { document.close() }
        runCatching { pfd.close() }
    }

    companion object {
        @Throws(Exception::class)
        fun open(ctx: Context, uri: Uri): PdfDoc {
            val core = PdfiumCore(ctx)
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("无法打开文件")
            val doc = try {
                core.newDocument(pfd)
            } catch (e: Throwable) {
                runCatching { pfd.close() }
                throw e
            }
            return PdfDoc(core, pfd, doc)
        }
    }
}
