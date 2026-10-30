package com.pagehop.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pagehop.reader.data.RecentEntry
import com.pagehop.reader.data.Store
import com.pagehop.reader.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val adapter = RecentAdapter()

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onPicked(uri)
    }

    private val pickAny = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.recentList.layoutManager = LinearLayoutManager(this)
        b.recentList.adapter = adapter

        b.btnOpen.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        b.btnOpen.setOnLongClickListener {
            Toast.makeText(this, "显示所有文件类型", Toast.LENGTH_SHORT).show()
            pickAny.launch(arrayOf("*/*"))
            true
        }

        checkCrashLog()

        // 冷启动直接续读上次那本书（页码由 ReaderActivity 从 Store 里恢复）。
        // savedInstanceState != null 说明是转屏/被系统重建，不该再弹一次阅读器；
        // ACTION_VIEW 说明是外部拿文件过来打开，优先打开那个文件。
        if (savedInstanceState == null && intent?.action != Intent.ACTION_VIEW) {
            openLastBook()
        }
    }

    /** 续读：上次看的那本书还能读就直接进阅读器，读不了就安静留在书库 */
    private fun openLastBook() {
        val last = Store.recent(this).firstOrNull() ?: return
        val uri = Uri.parse(last.uri)
        val readable = runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        if (!readable) return
        openReader(uri, last.name)
    }

    /** 上次崩溃了就把堆栈弹出来，方便一键复制反馈 */
    private fun checkCrashLog() {
        val f = File(filesDir, App.CRASH_FILE)
        if (!f.exists()) return
        val text = runCatching { f.readText() }.getOrNull().orEmpty()
        runCatching { f.delete() }
        if (text.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle("上次运行时出错了")
            .setMessage(text.take(3000))
            .setPositiveButton("复制错误信息") { _, _ ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("crash", text))
                Toast.makeText(this, "已复制，粘贴到对话里发我", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("忽略", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshRecent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { openReader(it, displayName(it)) }
        }
    }

    private fun refreshRecent() {
        val list = Store.recent(this)
        adapter.submit(list)
        b.tvEmpty.isVisible = list.isEmpty()
    }

    private fun onPicked(uri: Uri?) {
        if (uri == null) return
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        openReader(uri, displayName(uri))
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "PDF"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst() && c.columnCount > 0) {
                        val v = c.getString(0)
                        if (!v.isNullOrBlank()) name = v
                    }
                }
        } catch (e: Exception) {
            // 用 fallback
        }
        return name
    }

    private fun openReader(uri: Uri, name: String) {
        startActivity(
            Intent(this, ReaderActivity::class.java).apply {
                data = uri
                putExtra(ReaderActivity.EXTRA_NAME, name)
            }
        )
    }

    private inner class RecentAdapter : RecyclerView.Adapter<RecentAdapter.VH>() {

        private var items: List<RecentEntry> = emptyList()

        fun submit(list: List<RecentEntry>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val root: View) : RecyclerView.ViewHolder(root) {
            val name: TextView = root.findViewById(R.id.tvName)
            val progress: TextView = root.findViewById(R.id.tvProgress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_recent, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.name.text = e.name
            val pct = if (e.total > 0) ((e.page + 1) * 100 / e.total) else 0
            holder.progress.text = "第 ${e.page + 1} 页 / 共 ${e.total} 页 · $pct%"
            holder.root.setOnClickListener { openReader(Uri.parse(e.uri), e.name) }
            holder.root.setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("从列表中移除「${e.name}」？\n（不会删除文件本身）")
                    .setPositiveButton("移除") { _, _ ->
                        Store.removeRecent(this@MainActivity, e.uri)
                        refreshRecent()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }
    }
}
