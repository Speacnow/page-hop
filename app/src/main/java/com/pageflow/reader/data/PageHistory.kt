package com.pageflow.reader.data

/**
 * 阅读位置的前进 / 后退模型（纯逻辑，不碰 UI，方便单元测试）。
 *
 * 两条独立的数据：
 *  - [stack] + [index]：导航栈，按时序正序排列，指针指向当前所在的位置。
 *    后退 = 指针左移，前进 = 指针右移；新的一次主动跳转会砍掉指针右侧的路径。
 *  - [recent]：展示用的"最近访问"列表，去重、最近的在前，最多 [maxRecent] 条。
 *
 * 只有"主动定点跳转"（输页码、点目录 / 书签、文档内链、历史列表）并且在新页面
 * 停留满一段时间之后，才会调用 [commit] 写入。逐页翻页、拖进度条不进历史。
 */
class PageHistory(
    private val maxRecent: Int = 6,
    private val maxStack: Int = 50,
) {

    /** 按时序正序排列的跳转记录 */
    private val stack = ArrayList<Int>()
    private var index = -1

    /** 最近访问（展示用） */
    private val recent = ArrayList<Int>()

    /** 打开书 / 恢复进度时定锚：当前页就是导航栈的第一个点 */
    fun reset(page: Int) {
        stack.clear()
        stack.add(page)
        index = 0
        recent.clear()
        recent.add(page)
    }

    /** 指针所在那一格（记录点） */
    private val here: Int get() = stack.getOrNull(index) ?: -1

    /** 后退可用：要么指针不在栈头，要么当前页已经滚离了记录点（先回记录点） */
    fun canGoBack(current: Int): Boolean =
        if (current != here) stack.isNotEmpty() else index > 0

    /** 前进可用：指针右侧还有被"撤销"掉的路径 */
    fun canGoForward(): Boolean = index >= 0 && index < stack.size - 1

    /**
     * 一次主动跳转在落点停够时间了，正式记账。
     *
     * @param from 跳转前所在的页（可能因为翻页而没被记录过）
     * @param page 跳转目标页
     */
    fun commit(from: Int, page: Int) {
        // 原地"跳"到当前页（例如拖进度条最后落回起点）：什么都不该变，
        // 尤其不能把指针挪到栈尾，否则会平白清掉后面的前进路径
        if (page == from) return
        // 新跳转 = 撤销了之前的后退，指针右侧的路径作废
        if (index < stack.size - 1) stack.subList(index + 1, stack.size).clear()
        // 来源页也进栈，这样"后退"才能精确回到起跳点
        if (from >= 0 && stack.lastOrNull() != from) stack.add(from)
        if (stack.lastOrNull() != page) stack.add(page)
        while (stack.size > maxStack) stack.removeAt(0)
        index = stack.size - 1

        recent.remove(page)
        recent.add(0, page)
        while (recent.size > maxRecent) recent.removeAt(recent.size - 1)
    }

    /** 后退：返回目标页（没有则 null）。不修改展示列表。 */
    fun back(current: Int): Int? {
        val cur = here
        if (cur < 0) return null
        // 翻页 / 拖进度条把位置带走了：先回到最近一次记录点
        if (current != cur) return cur
        if (index <= 0) return null
        index--
        return stack[index]
    }

    /** 前进：返回目标页（没有则 null）。不修改展示列表。 */
    fun forward(): Int? {
        if (!canGoForward()) return null
        index++
        return stack[index]
    }

    /** 展示列表：最近访问在前 */
    fun recentPages(): List<Int> = recent.toList()
}
