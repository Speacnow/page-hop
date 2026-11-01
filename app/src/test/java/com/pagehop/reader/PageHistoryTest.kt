package com.pagehop.reader

import com.pagehop.reader.data.PageHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 前进 / 后退模型（PageHistory）的纯逻辑测试 */
class PageHistoryTest {

    /** 模拟一次"主动跳转 + 停满 1 秒"的落账 */
    private fun PageHistory.jump(from: Int, to: Int) = commit(from, to)

    @Test
    fun `after a jump chain forward is disabled but back walks every step`() {
        val h = PageHistory()
        h.reset(0) // 第 1 页
        h.jump(0, 1) // 2
        h.jump(1, 9) // 10
        h.jump(9, 24) // 25
        h.jump(24, 49) // 50
        h.jump(49, 99) // 100

        // 还没后退过：前进无可前进
        assertTrue(h.canGoBack(99))
        assertFalse(h.canGoForward())

        assertEquals(49, h.back(99))
        assertEquals(24, h.back(49))
        assertEquals(9, h.back(24))
        assertEquals(1, h.back(9))
        assertEquals(0, h.back(1))
        assertNull(h.back(0))
        assertFalse(h.canGoBack(0))

        // 逐级前进回去
        assertTrue(h.canGoForward())
        assertEquals(1, h.forward())
        assertEquals(9, h.forward())
        assertEquals(24, h.forward())
        assertEquals(49, h.forward())
        assertEquals(99, h.forward())
        assertNull(h.forward())
        assertFalse(h.canGoForward())
    }

    @Test
    fun `jump back to an earlier recorded page then back and forward stay symmetric`() {
        val h = PageHistory()
        h.reset(0)
        h.jump(0, 1)
        h.jump(1, 9)
        h.jump(9, 24)
        h.jump(24, 49)
        h.jump(49, 99)
        // 在第 100 页跳到第 25 页（停满 1 秒）
        h.jump(99, 24)

        // 展示列表：目标页被移到队首
        assertEquals(24, h.recentPages().first())

        // 后退回到来源页 100
        assertEquals(99, h.back(24))
        // 前进 = 撤销刚才的后退，回到 25
        assertEquals(24, h.forward())
        // 再后退一次还是 100
        assertEquals(99, h.back(24))
    }

    @Test
    fun `pages flipped through quickly never enter history`() {
        val h = PageHistory()
        h.reset(0)
        h.jump(0, 99) // 只有这一次是"主动跳转 + 停留足够"
        // 之后手动快速翻到 100..105，<1 秒，完全不进历史

        // 后退：直接回到上一个有效历史页（记录点 100）
        assertEquals(99, h.back(105))
        // 再退一级回到第 1 页
        assertEquals(0, h.back(99))
        assertNull(h.back(0))
    }

    @Test
    fun `new jump after going back drops the forward path`() {
        val h = PageHistory()
        h.reset(0)
        h.jump(0, 10)
        h.jump(10, 20)
        assertEquals(10, h.back(20))
        assertTrue(h.canGoForward())

        h.jump(10, 30)
        assertFalse(h.canGoForward())
        assertEquals(10, h.back(30))
        assertEquals(0, h.back(10))
    }

    @Test
    fun `recent list is deduped newest first and capped at six`() {
        val h = PageHistory(maxRecent = 6)
        h.reset(0)
        for (p in 1..10) h.jump(p - 1, p)
        assertEquals(listOf(10, 9, 8, 7, 6, 5), h.recentPages())

        // 回到一个已有的页：移到队首而不是重复插入
        h.jump(10, 3)
        assertEquals(listOf(3, 10, 9, 8, 7, 6), h.recentPages())
        assertEquals(6, h.recentPages().size)
    }

    @Test
    fun `back and forward never modify the recent list`() {
        val h = PageHistory()
        h.reset(0)
        h.jump(0, 7)
        h.jump(7, 70)
        val before = h.recentPages()
        h.back(70)
        h.back(7)
        h.forward()
        h.forward()
        assertEquals(before, h.recentPages())
    }

    @Test
    fun `committing the page we are already on changes nothing`() {
        val h = PageHistory()
        h.reset(0)
        h.jump(0, 10)
        h.jump(10, 20)
        h.jump(20, 30)
        assertEquals(20, h.back(30)) // 现在停在 20，指针左移
        assertTrue(h.canGoForward())

        // 拖进度条又落回 20：不该把前进路径清掉
        h.jump(20, 20)
        assertTrue(h.canGoForward())
        assertEquals(30, h.forward())
    }

    @Test
    fun `reading away from the last jump point moves the anchor but not the list`() {
        val h = PageHistory()
        h.reset(0)
        h.jump(0, 5)
        assertEquals(listOf(5, 0), h.recentPages())
        // 从第 6 页一路读到第 19 页：列表里不该多出这些页
        for (p in 6..18) h.moveTo(p)
        assertEquals(listOf(5, 0), h.recentPages())
        // 但记录点跟着走了：后退回到上次跳转的起点，而不是刚读过去的第 6 页
        assertEquals(0, h.back(18))
    }

    @Test
    fun `reading after going back keeps the forward path`() {
        val h = PageHistory()
        h.reset(0)
        h.jump(0, 10)
        h.jump(10, 20)
        assertEquals(10, h.back(20))
        // 后退之后又往前翻了两页：不该把"前进到第 21 页"这条路径踩掉
        h.moveTo(12)
        assertTrue(h.canGoForward())
        assertEquals(20, h.forward())
    }

    @Test
    fun `opening a book does not create a history entry`() {
        val h = PageHistory()
        h.reset(39) // 上次停在第 40 页
        assertTrue(h.recentPages().isEmpty())
        h.moveTo(40) // 翻到第 41 页
        h.moveTo(41)
        // 打开、沿途翻页都不是跳转，列表还是空的
        assertTrue(h.recentPages().isEmpty())
    }

    @Test
    fun `a jump keeps both the page we left and the page we landed on`() {
        val h = PageHistory()
        h.reset(39) // 上次停在第 40 页
        for (p in 40..44) h.moveTo(p) // 一路读到第 45 页
        h.jump(44, 49) // 跳到第 50 页
        assertEquals(listOf(49, 44), h.recentPages())
        assertEquals(44, h.back(49))
    }

    @Test
    fun `reading on after a jump leaves the list at the two jump ends`() {
        val h = PageHistory()
        h.reset(59) // 第 60 页
        h.jump(59, 79) // 跳到第 80 页
        assertEquals(listOf(79, 59), h.recentPages())
        // 从第 80 页读到第 83 页
        for (p in 80..82) h.moveTo(p)
        assertEquals(listOf(79, 59), h.recentPages()) // 仍然是 60、80
        assertEquals(59, h.back(82)) // 后退回这次跳转的起点，不是刚读过的 80
    }
}
