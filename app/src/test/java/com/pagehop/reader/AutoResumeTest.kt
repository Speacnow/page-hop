package com.pagehop.reader

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pagehop.reader.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * 冷启动续读：退出应用后再打开，应该直接进上次那本书。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoResumeTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `cold start opens the last book`() {
        val c = ctx()
        val file = File(c.filesDir, "last-book.pdf").apply { writeText("%PDF-1.4\n") }
        val uri = Uri.fromFile(file)
        Store.touchRecent(c, uri.toString(), "last-book.pdf", 42, 318)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val started = shadowOf(activity).nextStartedActivity

        assertNotNull("冷启动应该直接进入阅读器", started)
        assertEquals(ReaderActivity::class.java.name, started.component?.className)
        assertEquals(uri, started.data)

        Store.removeRecent(c, uri.toString())
    }

    @Test
    fun `without any recent book it stays on the library`() {
        val c = ctx()
        for (e in Store.recent(c)) Store.removeRecent(c, e.uri)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNull("书库为空时不应该自动打开阅读器", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `unreadable last book falls back to the library`() {
        val c = ctx()
        // 权限失效 / 文件被删的情况：不能把一个打不开的文件丢给阅读器
        Store.touchRecent(c, "content://gone/1", "已失效.pdf", 3, 10)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNull("打不开时应该安静留在书库", shadowOf(activity).nextStartedActivity)

        Store.removeRecent(c, "content://gone/1")
    }
}
