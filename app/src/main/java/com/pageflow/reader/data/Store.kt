package com.pageflow.reader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 全部持久化都用简单的 JSON 文件，避免为了几百页的裁剪缓存去上数据库。
 */
object Store {

    private const val RECENT_FILE = "_recent.json"
    private const val MAX_RECENT = 30

    fun idFor(uri: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun booksDir(ctx: Context): File =
        File(ctx.filesDir, "books").apply { if (!exists()) mkdirs() }

    private fun stateFile(ctx: Context, uri: String): File =
        File(booksDir(ctx), "${idFor(uri)}.json")

    private fun recentFile(ctx: Context): File = File(booksDir(ctx), RECENT_FILE)

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    // ---------------- 单本书状态 ----------------

    fun loadState(ctx: Context, uri: String, name: String, total: Int): BookState {
        val f = stateFile(ctx, uri)
        val state = BookState(uri = uri, name = name, total = total)
        if (!f.exists()) return state
        try {
            val o = JSONObject(f.readText())
            state.name = o.optString("name", name)
            state.total = o.optInt("total", total)
            state.page = o.optInt("page", 0)
            state.colorMode = runCatching {
                ColorModeKey.valueOf(o.optString("colorMode", "NORMAL"))
            }.getOrDefault(ColorModeKey.NORMAL)
            state.cropEnabled = o.optBoolean("cropEnabled", true)
            state.cropPad = o.optDouble("cropPad", 0.02).toFloat().coerceIn(0f, CropMath.MAX_PAD)
            state.manualCrop = CropRect.fromJson(o.optJSONObject("manualCrop"))

            o.optJSONObject("pageCrops")?.let { crops ->
                crops.keys().forEach { key ->
                    val idx = key.toIntOrNull() ?: return@forEach
                    CropRect.fromJson(crops.optJSONObject(key))?.let { state.pageCrops[idx] = it }
                }
            }
            o.optJSONArray("highlights")?.let { arr ->
                for (i in 0 until arr.length()) {
                    Highlight.fromJson(arr.getJSONObject(i))?.let { state.highlights.add(it) }
                }
            }
            o.optJSONArray("bookmarks")?.let { arr ->
                for (i in 0 until arr.length()) state.bookmarks.add(arr.optInt(i))
            }
            o.optJSONArray("textNotes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    TextNote.fromJson(arr.optJSONObject(i))?.let { state.textNotes.add(it) }
                }
            }
            o.optJSONArray("strokes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    InkStroke.fromJson(arr.getJSONObject(i))?.let { state.strokes.add(it) }
                }
            }
            state.updatedAt = o.optLong("updatedAt", 0L)

            // v2 起 pageCrops 存的是"正文框"，留白在渲染时另加；
            // 旧版存的是已经加了留白的框，直接沿用会重复裁紧一次，所以作废重算。
            if (o.optInt("cropVer", 1) < 2) state.pageCrops.clear()
        } catch (e: Exception) {
            // 文件坏了就当新书，不阻塞打开
        }
        if (state.page !in 0 until maxOf(1, state.total)) state.page = 0
        return state
    }

    fun saveState(ctx: Context, state: BookState) {
        try {
            state.updatedAt = System.currentTimeMillis()
            val o = JSONObject().apply {
                put("uri", state.uri)
                put("name", state.name)
                put("page", state.page)
                put("total", state.total)
                put("colorMode", state.colorMode.name)
                put("cropEnabled", state.cropEnabled)
                put("cropPad", state.cropPad.toDouble())
                put("cropVer", 2)
                put("manualCrop", state.manualCrop?.toJson())
                put("pageCrops", JSONObject().apply {
                    state.pageCrops.forEach { (k, v) -> put(k.toString(), v.toJson()) }
                })
                put("highlights", JSONArray().apply {
                    state.highlights.forEach { put(it.toJson()) }
                })
                put("bookmarks", org.json.JSONArray().apply {
                    state.bookmarks.forEach { put(it) }
                })
                put("textNotes", org.json.JSONArray().apply {
                    state.textNotes.forEach { put(it.toJson()) }
                })
                put("strokes", JSONArray().apply {
                    state.strokes.forEach { put(it.toJson()) }
                })
                put("updatedAt", state.updatedAt)
            }
            atomicWrite(stateFile(ctx, state.uri), o.toString())
        } catch (e: Exception) {
            // 写盘失败不应崩溃
        }
    }

    // ---------------- 最近打开 ----------------

    fun recent(ctx: Context): MutableList<RecentEntry> {
        val list = mutableListOf<RecentEntry>()
        val f = recentFile(ctx)
        if (!f.exists()) return list
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    RecentEntry(
                        uri = o.getString("uri"),
                        name = o.optString("name", "PDF"),
                        page = o.optInt("page", 0),
                        total = o.optInt("total", 0),
                        updatedAt = o.optLong("ts", 0L),
                    )
                )
            }
        } catch (e: Exception) {
            // ignore
        }
        return list
    }

    private fun saveRecent(ctx: Context, list: List<RecentEntry>) {
        try {
            val arr = JSONArray()
            list.take(MAX_RECENT).forEach { e ->
                arr.put(JSONObject().apply {
                    put("uri", e.uri); put("name", e.name)
                    put("page", e.page); put("total", e.total); put("ts", e.updatedAt)
                })
            }
            atomicWrite(recentFile(ctx), arr.toString())
        } catch (e: Exception) {
            // ignore
        }
    }

    fun touchRecent(ctx: Context, uri: String, name: String, page: Int, total: Int) {
        val list = recent(ctx)
        list.removeAll { it.uri == uri }
        list.add(0, RecentEntry(uri, name, page, total, System.currentTimeMillis()))
        saveRecent(ctx, list)
    }

    fun removeRecent(ctx: Context, uri: String) {
        val list = recent(ctx)
        list.removeAll { it.uri == uri }
        saveRecent(ctx, list)
        runCatching { stateFile(ctx, uri).delete() }
    }
}
