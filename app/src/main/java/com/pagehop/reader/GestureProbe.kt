package com.pagehop.reader

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * 调试用：用合成 MotionEvent 在真机上跑双指/拖动脚本。
 *
 * 存在的理由：`adb shell input` 只能单指，多点触控的 `/dev/input` 又被 SELinux 挡住，
 * 捏合这类手势在真机上没法用 adb 触发。这里直接从 `dispatchTouchEvent` 灌事件，
 * 走的是和真手指完全相同的分发链路。
 *
 * 用法：`am start -n com.pagehop.reader/.ReaderActivity -d <uri> --es probe "命令;命令..."`
 * 命令：`pinch:起始间距:结束间距` / `drag:纵向位移` / `pen`（开关批注）/ `log`
 */
object GestureProbe {

    private const val TAG = "PFProbe"
    private const val STEP_MS = 16L

    private val handler = Handler(Looper.getMainLooper())

    fun run(target: View, script: String, onPen: () -> Unit = {}, log: () -> String) {
        // 用 '+' 分隔：分号/竖线会被设备 shell 吃掉
        val cmds = script.split('+').map { it.trim() }.filter { it.isNotEmpty() }
        Log.d(TAG, "start script=$script")
        next(target, cmds, 0, onPen, log)
    }

    private fun next(
        target: View,
        cmds: List<String>,
        index: Int,
        onPen: () -> Unit,
        log: () -> String,
    ) {
        if (index >= cmds.size) {
            Log.d(TAG, "done")
            return
        }
        val cmd = cmds[index]
        val after = { handler.postDelayed({ next(target, cmds, index + 1, onPen, log) }, 500L) }

        when {
            cmd.startsWith("pinch:") -> {
                val parts = cmd.removePrefix("pinch:").split(':')
                val from = parts.getOrNull(0)?.toFloatOrNull() ?: 400f
                val to = parts.getOrNull(1)?.toFloatOrNull() ?: 1200f
                Log.d(TAG, "pinch $from -> $to")
                pinch(target, from, to) {
                    Log.d(TAG, "pinch done ${log()}")
                    after()
                }
            }

            cmd.startsWith("drag:") -> {
                val dy = cmd.removePrefix("drag:").toFloatOrNull() ?: -600f
                Log.d(TAG, "drag $dy")
                drag(target, dy) {
                    Log.d(TAG, "drag done ${log()}")
                    after()
                }
            }

            cmd.startsWith("drag2:") -> {
                val dy = cmd.removePrefix("drag2:").toFloatOrNull() ?: -600f
                Log.d(TAG, "drag2 $dy")
                drag2(target, dy) {
                    Log.d(TAG, "drag2 done ${log()}")
                    after()
                }
            }

            cmd == "pen" -> {
                Log.d(TAG, "toggle pen")
                onPen()
                after()
            }

            cmd == "log" -> {
                Log.d(TAG, "state ${log()}")
                after()
            }

            else -> {
                Log.d(TAG, "unknown cmd ${cmd}")
                after()
            }
        }
    }

    private fun props(i: Int) = MotionEvent.PointerProperties().apply {
        id = i
        toolType = MotionEvent.TOOL_TYPE_FINGER
    }

    private fun coords(x: Float, y: Float) = MotionEvent.PointerCoords().apply {
        this.x = x
        this.y = y
        pressure = 1f
        size = 1f
    }

    private fun one(action: Int, x: Float, y: Float): MotionEvent = MotionEvent.obtain(
        0L, SystemClock.uptimeMillis(), action, 1, arrayOf(props(0)), arrayOf(coords(x, y)),
        0, 0, 1f, 1f, 0, 0, 0, 0,
    )

    private fun two(action: Int, x0: Float, y0: Float, x1: Float, y1: Float): MotionEvent =
        MotionEvent.obtain(
            0L, SystemClock.uptimeMillis(), action, 2,
            arrayOf(props(0), props(1)), arrayOf(coords(x0, y0), coords(x1, y1)),
            0, 0, 1f, 1f, 0, 0, 0, 0,
        )

    private val pointerDown =
        MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private val pointerUp =
        MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /** 以视图中心为焦点，两指间距从 from 线性变到 to */
    fun pinch(target: View, from: Float, to: Float, steps: Int = 12, onDone: () -> Unit) {
        val cx = target.width / 2f
        val cy = target.height * 0.45f
        var span = from
        var i = 1

        fun pt(offset: Float) = cx - span / 2f + offset

        target.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, cx - span / 2f, cy))
        target.dispatchTouchEvent(two(pointerDown, cx - span / 2f, cy, cx + span / 2f, cy))

        lateinit var step: Runnable
        step = Runnable {
            span = from + (to - from) * i / steps
            target.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, cx - span / 2f, cy, cx + span / 2f, cy))
            i++
            if (i <= steps) {
                handler.postDelayed(step, STEP_MS)
            } else {
                val x0 = pt(0f)
                target.dispatchTouchEvent(two(pointerUp, cx - span / 2f, cy, cx + span / 2f, cy))
                target.dispatchTouchEvent(one(MotionEvent.ACTION_UP, x0, cy))
                handler.postDelayed(onDone, 300L)
            }
        }
        handler.postDelayed(step, STEP_MS)
    }

    /** 单指从中心纵向拖 dy */
    fun drag(target: View, dy: Float, steps: Int = 14, onDone: () -> Unit) {
        val cx = target.width / 2f
        val y0 = target.height * 0.5f
        var i = 1

        target.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, cx, y0))

        lateinit var step: Runnable
        step = Runnable {
            val y = y0 + dy * i / steps
            target.dispatchTouchEvent(one(MotionEvent.ACTION_MOVE, cx, y))
            i++
            if (i <= steps) {
                handler.postDelayed(step, STEP_MS)
            } else {
                target.dispatchTouchEvent(one(MotionEvent.ACTION_UP, cx, y0 + dy))
                handler.postDelayed(onDone, 300L)
            }
        }
        handler.postDelayed(step, STEP_MS)
    }

    /** 双指保持间距、整体纵向拖 dy（批注模式下的翻页手势） */
    fun drag2(target: View, dy: Float, steps: Int = 14, onDone: () -> Unit) {
        val cx = target.width / 2f
        val y0 = target.height * 0.45f
        val span = 320f
        var i = 1

        target.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, cx - span / 2f, y0))
        target.dispatchTouchEvent(two(pointerDown, cx - span / 2f, y0, cx + span / 2f, y0))

        lateinit var step: Runnable
        step = Runnable {
            val y = y0 + dy * i / steps
            target.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, cx - span / 2f, y, cx + span / 2f, y))
            i++
            if (i <= steps) {
                handler.postDelayed(step, STEP_MS)
            } else {
                target.dispatchTouchEvent(two(pointerUp, cx - span / 2f, y0 + dy, cx + span / 2f, y0 + dy))
                target.dispatchTouchEvent(one(MotionEvent.ACTION_UP, cx - span / 2f, y0 + dy))
                handler.postDelayed(onDone, 300L)
            }
        }
        handler.postDelayed(step, STEP_MS)
    }
}
