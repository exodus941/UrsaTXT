package com.ursatxt

import android.content.Context
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Every note is one .md file under [root], and every folder a real folder, so other
 * apps can read them. What a file cannot hold (its colour, a pin, when it was added
 * and opened, whether its name was typed) sits in one index file, [META], keyed by
 * path from the root.
 */
object Notes {
    const val META = ".ursatxt.json"
    const val EXT = ".md"
    /** Yeetlist's starting hues, dealt in turn to new notes; any of the 16 can be picked. */
    private val HUES = intArrayOf(24, 48, 144, 208)
    val SWATCHES = intArrayOf(8, 24, 36, 48, 60, 76, 128, 144, 168, 180, 192, 208, 240, 288, 316, 344)

    class Entry(
        val file: File, val dir: Boolean, val name: String, val rel: String,
        val added: Long, val opened: Long, val size: Long, val pinned: Boolean, val hue: Int,
        val preview: String = "", val notes: Int = 0, val folders: Int = 0,
    )

    private var meta: JSONObject? = null
    private var metaRoot: File? = null

    /** True when the shared folder can be used: Android 11+ needs All files access. */
    fun shared(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    private fun publicRoot() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "UrsaTXT")
    private fun privateRoot(c: Context) = File(c.getExternalFilesDir(null) ?: c.filesDir, "UrsaTXT")

    /**
     * Documents/UrsaTXT once access is granted; the app's own folder before that. Notes
     * written there before access move to the shared folder the first time it is open.
     */
    fun root(c: Context): File {
        val priv = privateRoot(c)
        val r = if (shared() && hasLegacy(c)) publicRoot() else priv
        if (!r.exists()) r.mkdirs()
        if (r != priv && priv.exists() && (priv.list()?.isNotEmpty() == true)) migrate(priv, r)
        if (metaRoot != r) { metaRoot = r; meta = null }
        return r
    }

    private fun hasLegacy(c: Context): Boolean = Build.VERSION.SDK_INT >= 30 ||
        c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun migrate(from: File, to: File) {
        val old = try { JSONObject(File(from, META).readText()) } catch (_: Exception) { JSONObject() }
        val now = load(to)
        from.listFiles()?.forEach { f ->
            if (f.name == META) return@forEach
            val dest = unique(to, f.name, f.isDirectory)
            if (f.renameTo(dest) || (f.copyRecursively(dest) && f.deleteRecursively())) {
                val prefix = f.name
                for (k in old.keys()) if (k == prefix || k.startsWith("$prefix/")) now.put(dest.name + k.substring(prefix.length), old.get(k))
            }
        }
        File(from, META).delete()
        meta = now; metaRoot = to
        store(to)
    }

    // ---- the index ----

    private fun load(r: File): JSONObject {
        if (metaRoot == r && meta != null) return meta!!
        val m = try { JSONObject(File(r, META).readText()) } catch (_: Exception) { JSONObject() }
        meta = m; metaRoot = r
        return m
    }

    private fun store(r: File) {
        val m = meta ?: return
        val tmp = File(r, "$META.tmp")
        tmp.writeText(m.toString())
        if (!tmp.renameTo(File(r, META))) { File(r, META).writeText(m.toString()); tmp.delete() }
    }

    fun rel(c: Context, f: File): String = f.absolutePath.removePrefix(root(c).absolutePath).trimStart('/')

    private fun info(c: Context, f: File): JSONObject {
        val m = load(root(c))
        val k = rel(c, f)
        return m.optJSONObject(k) ?: JSONObject().also { m.put(k, it) }
    }

    private fun edit(c: Context, f: File, block: (JSONObject) -> Unit) {
        block(info(c, f)); store(root(c))
    }

    /** Moves every index entry under [from] to [to], or copies them when [keep]. */
    private fun remap(c: Context, from: File, to: File, keep: Boolean) {
        val m = load(root(c))
        val a = rel(c, from); val b = rel(c, to)
        val keys = m.keys().asSequence().filter { it == a || it.startsWith("$a/") }.toList()
        for (k in keys) {
            val v = m.getJSONObject(k)
            m.put(b + k.substring(a.length), if (keep) JSONObject(v.toString()) else v)
            if (!keep) m.remove(k)
        }
        store(root(c))
    }

    private fun drop(c: Context, f: File) {
        val m = load(root(c)); val a = rel(c, f)
        m.keys().asSequence().filter { it == a || it.startsWith("$a/") }.toList().forEach { m.remove(it) }
        store(root(c))
    }

    // ---- reading ----

    /**
     * A note left blank and unnamed goes. The editor removes one on close; this catches
     * one whose editor never closed, as when the app was stopped. Run only while no
     * editor is open.
     */
    fun sweep(c: Context, dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(EXT) && f.length() == 0L && !named(c, f)) delete(c, f)
        }
    }

    fun list(c: Context, dir: File): List<Entry> =
        (dir.listFiles() ?: emptyArray()).filter { visible(it) }.map { entry(c, it) }

    /** Every note at or under [dir] whose name or text holds [q]. */
    fun search(c: Context, dir: File, q: String): List<Entry> {
        val out = ArrayList<Entry>()
        dir.walkTopDown().onEnter { it == dir || visible(it) }.forEach { f ->
            if (f == dir || !visible(f)) return@forEach
            val nameHit = f.name.removeSuffix(EXT).contains(q, ignoreCase = true)
            if (f.isDirectory) { if (nameHit) out += entry(c, f); return@forEach }
            if (nameHit || (f.length() < 2_000_000 && f.readText().contains(q, ignoreCase = true))) out += entry(c, f)
        }
        return out
    }

    private fun visible(f: File) = !f.name.startsWith(".") && (f.isDirectory || f.name.endsWith(EXT, ignoreCase = true))

    fun entry(c: Context, f: File): Entry {
        val i = info(c, f)
        val added = i.optLong("added", f.lastModified())
        val opened = i.optLong("opened", f.lastModified())
        return if (f.isDirectory) {
            val kids = f.listFiles()?.filter { visible(it) } ?: emptyList()
            Entry(f, true, f.name, rel(c, f), added, opened, f.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                i.optBoolean("pin"), 0, notes = kids.count { !it.isDirectory }, folders = kids.count { it.isDirectory })
        } else {
            Entry(f, false, f.name.removeSuffix(EXT), rel(c, f), added, opened, f.length(),
                i.optBoolean("pin"), hueOf(c, f), preview = preview(f))
        }
    }

    fun hueOf(c: Context, f: File): Int {
        val i = info(c, f)
        if (i.has("hue")) return i.optInt("hue")
        val n = load(root(c)).optInt("#dealt", 0)
        val h = HUES[n % HUES.size]
        i.put("hue", h); load(root(c)).put("#dealt", n + 1); store(root(c))
        return h
    }

    fun named(c: Context, f: File) = info(c, f).optBoolean("named", false)

    /** The first two lines of text, without markdown marks. */
    private fun preview(f: File): String {
        val lines = ArrayList<String>()
        try {
            f.bufferedReader().useLines { seq ->
                for (raw in seq) {
                    val l = raw.trim()
                    if (l.isEmpty()) continue
                    lines += l.replace(Regex("^#{1,6}\\s+"), "")
                        .replace(Regex("^(?:[-*+]|\\d+[.)])\\s+(?:\\[[ xX]]\\s*)?"), "")
                        .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
                        .replace(Regex("\\*\\*|~~|[*_`]"), "")
                        .replace(Regex("\\\\([\\\\`*_~\\[\\]])"), "$1")
                    if (lines.size == 2) break
                }
            }
        } catch (_: Exception) {}
        return lines.joinToString("\n")
    }

    // ---- writing ----

    /** [name] made safe as a file name: the characters Android and desktops refuse go. */
    fun clean(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), " ")
        .replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ').take(80).ifEmpty { "Untitled note" }

    /** A free name in [dir]: "Name", then "Name (2)", "Name (3)". */
    fun unique(dir: File, name: String, folder: Boolean, except: File? = null): File {
        val base = if (folder) name else name.removeSuffix(EXT)
        val ext = if (folder) "" else EXT
        var k = 1
        while (true) {
            val f = File(dir, if (k == 1) "$base$ext" else "$base ($k)$ext")
            if (!f.exists() || f == except) return f
            k++
        }
    }

    fun newNote(c: Context, dir: File): File {
        val f = unique(dir, "Untitled note", false)
        f.writeText("")
        val now = System.currentTimeMillis()
        edit(c, f) { it.put("added", now); it.put("opened", now); it.put("named", false) }
        hueOf(c, f)
        return f
    }

    fun newFolder(c: Context, dir: File, name: String = "New Folder"): File {
        val f = unique(dir, clean(name).let { if (it == "Untitled note") "New Folder" else it }, true)
        f.mkdirs()
        val now = System.currentTimeMillis()
        edit(c, f) { it.put("added", now); it.put("opened", now) }
        return f
    }

    fun touch(c: Context, f: File) = edit(c, f) { it.put("opened", System.currentTimeMillis()) }
    fun setPin(c: Context, f: File, on: Boolean) = edit(c, f) { if (on) it.put("pin", true) else it.remove("pin") }
    fun setHue(c: Context, f: File, h: Int) = edit(c, f) { it.put("hue", h) }

    /** Renames, keeping the index; returns the file as it is now called. */
    fun rename(c: Context, f: File, name: String, typed: Boolean = true): File {
        val to = unique(f.parentFile!!, clean(name), f.isDirectory, except = f)
        if (to != f) {
            if (!f.renameTo(to)) return f
            remap(c, f, to, keep = false)
        }
        if (!to.isDirectory) edit(c, to) { it.put("named", typed) }
        return to
    }

    fun delete(c: Context, f: File): Boolean {
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (ok) drop(c, f)
        return ok
    }

    /** False when [into] is [f] or inside it, where a folder cannot go. */
    fun canGo(f: File, into: File) = !f.isDirectory || (into != f && !into.absolutePath.startsWith(f.absolutePath + "/"))

    fun move(c: Context, f: File, into: File): File? {
        if (!canGo(f, into) || f.parentFile == into) return if (f.parentFile == into) f else null
        val to = unique(into, f.name, f.isDirectory)
        if (!f.renameTo(to)) {
            if (!(f.copyRecursively(to) && f.deleteRecursively())) return null
        }
        remap(c, f, to, keep = false)
        return to
    }

    fun copy(c: Context, f: File, into: File): File? {
        if (!canGo(f, into)) return null
        val to = unique(into, f.name, f.isDirectory)
        if (!f.copyRecursively(to)) return null
        remap(c, f, to, keep = true)
        val now = System.currentTimeMillis()
        edit(c, to) { it.put("added", now); it.put("opened", now) }
        return to
    }

    // ---- backup ----

    /** Every note and folder and the index, at the highest compression. */
    fun export(c: Context, out: java.io.OutputStream): Int {
        val r = root(c)
        var n = 0
        ZipOutputStream(out.buffered()).use { z ->
            z.setLevel(Deflater.BEST_COMPRESSION)
            r.walkTopDown().forEach { f ->
                if (f == r) return@forEach
                val path = rel(c, f)
                if (path.endsWith(".tmp")) return@forEach
                if (f.isDirectory) { z.putNextEntry(ZipEntry("$path/")); z.closeEntry(); return@forEach }
                z.putNextEntry(ZipEntry(path).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
                if (f.name.endsWith(EXT, ignoreCase = true)) n++
            }
        }
        return n
    }

    /**
     * Unpacks a backup into the root. A note whose name is taken arrives beside it as
     * "Name (2)"; a folder of the same name is merged into. Returns the notes added.
     */
    fun import(c: Context, input: java.io.InputStream): Int {
        val r = root(c)
        val m = load(r)
        var incoming = JSONObject()
        val renamed = HashMap<String, String>()
        var n = 0
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val path = e.name.replace('\\', '/').trimStart('/')
                if (path.split('/').any { it == ".." }) continue
                if (path == META) { incoming = try { JSONObject(z.readBytes().decodeToString()) } catch (_: Exception) { JSONObject() }; continue }
                val target = File(r, path)
                if (e.isDirectory) { target.mkdirs(); continue }
                if (!path.endsWith(EXT, ignoreCase = true)) continue
                target.parentFile?.mkdirs()
                val dest = unique(target.parentFile!!, target.name, false)
                dest.outputStream().use { z.copyTo(it) }
                if (e.time > 0) dest.setLastModified(e.time)
                renamed[path] = rel(c, dest)
                n++
            }
        }
        for (k in incoming.keys()) {
            if (k.startsWith("#")) continue
            val to = renamed[k] ?: if (File(r, k).isDirectory && !m.has(k)) k else continue
            m.put(to, incoming.get(k))
        }
        store(r)
        return n
    }
}
