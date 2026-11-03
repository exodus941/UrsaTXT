package com.ursatxt

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.File

/**
 * One note, in Yeetlist's editor (assets/editor) themed to UrsaPDF. The page holds
 * the text; this side owns the file and its index entry.
 */
class EditorActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var frame: FrameLayout
    @Volatile private var file: File? = null
    private var gone = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Theme.load(this)
        window.edgeToEdge()
        val path = intent.getStringExtra("path")
        val f = path?.let { File(it) }
        if (f == null || !f.exists()) { finish(); return }
        file = f
        Notes.touch(this, f)

        frame = FrameLayout(this).apply { setBackgroundColor(Theme.bg()) }
        web = WebView(this).apply {
            setBackgroundColor(Theme.bg())
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.domStorageEnabled = false
            settings.textZoom = 100
            isVerticalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
            // The page's errors reach logcat under "UrsaJS".
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.println(if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) android.util.Log.ERROR else android.util.Log.INFO,
                        "UrsaJS", "${m.message()} (${m.sourceId()?.substringAfterLast('/')}:${m.lineNumber()})")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                // A blank note is for writing: the caret goes in and the keyboard comes up.
                override fun onPageFinished(v: WebView, url: String) {
                    val blank = try { file?.readText()?.isBlank() == true } catch (_: Exception) { false }
                    if (!blank) return
                    v.requestFocus()
                    v.evaluateJavascript("document.getElementById('noteBody').focus()", null)
                    v.postDelayed({ getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(v, 0) }, 100)
                }
                // A link in a note opens in the phone's browser, never inside the editor.
                override fun shouldOverrideUrlLoading(v: WebView, r: android.webkit.WebResourceRequest): Boolean {
                    if (r.url.scheme == "file") return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (_: Exception) {}
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "Ursa")
        }
        frame.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.setOnApplyWindowInsetsListener { _, ins ->
            val s = ins.safe()
            @Suppress("DEPRECATION")
            val ime = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.ime()).bottom else 0
            frame.setPadding(s[0], s[1], s[2], maxOf(s[3], ime))
            ins
        }
        setContentView(frame)
        Theme.applyBars(window)
        web.loadUrl("file:///android_asset/editor/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!::web.isInitialized) { @Suppress("DEPRECATION") super.onBackPressed(); return }
        web.evaluateJavascript("back()", null)
    }

    override fun onPause() {
        super.onPause()
        if (::web.isInitialized && !gone) web.evaluateJavascript("save({ now: true })", null)
    }

    override fun onDestroy() {
        if (::web.isInitialized) { web.removeJavascriptInterface("Ursa"); web.destroy() }
        super.onDestroy()
    }

    private fun leave(msg: String? = null) {
        if (gone) return
        gone = true
        msg?.let { setResult(RESULT_OK, Intent().putExtra("note", it)) }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    inner class Bridge {
        @JavascriptInterface
        fun load(): String {
            val f = file ?: return "{}"
            val p = getSharedPreferences("ursa", MODE_PRIVATE)
            return JSONObject().apply {
                put("name", f.name.removeSuffix(Notes.EXT))
                put("named", Notes.named(this@EditorActivity, f))
                put("md", try { f.readText() } catch (_: Exception) { "" })
                put("hue", Notes.hueOf(this@EditorActivity, f))
                put("zoom", p.getInt("zoom", 100))
                put("light", Theme.light)
            }.toString()
        }

        /** [name] is empty while the note has no name of its own. */
        @JavascriptInterface
        fun save(name: String, md: String) {
            val f = file ?: return
            try { f.writeText(md) } catch (_: Exception) { return }
            file = if (name.isNotBlank()) Notes.rename(this@EditorActivity, f, name, typed = true)
            else { if (Notes.named(this@EditorActivity, f)) Notes.rename(this@EditorActivity, f, f.name.removeSuffix(Notes.EXT), typed = false) else f }
        }

        @JavascriptInterface
        fun setHue(h: Int) { file?.let { Notes.setHue(this@EditorActivity, it, h) } }

        @JavascriptInterface
        fun setZoom(z: Int) { getSharedPreferences("ursa", MODE_PRIVATE).edit().putInt("zoom", z).apply() }

        @JavascriptInterface
        fun exportNote(kind: String, name: String, text: String) = runOnUiThread {
            val mime = when (kind) { "html" -> "text/html"; "txt" -> "text/plain"; else -> "text/markdown" }
            val ok = saveAndShare(name, mime) { it.write(text.toByteArray()) }
            note(if (ok) "Saved “$name” to Downloads" else "Couldn’t save “$name” to Downloads")
        }

        @JavascriptInterface
        fun copy(text: String, title: String) = runOnUiThread {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, text))
            note("Note copied to clipboard")
            sendText(text, title)
        }

        @JavascriptInterface
        fun share(text: String, title: String) = runOnUiThread { sendText(text, title) }

        @JavascriptInterface
        fun deleteNote() = runOnUiThread {
            val f = file ?: return@runOnUiThread
            val name = f.name.removeSuffix(Notes.EXT)
            file = null
            leave(if (Notes.delete(this@EditorActivity, f)) "Deleted “$name”" else "Couldn’t delete “$name”")
        }

        /** Leaves. A note left blank and unnamed is removed; an unnamed one takes [adopt]. */
        @JavascriptInterface
        fun done(adopt: String) = runOnUiThread {
            val f = file
            if (f != null && f.exists()) {
                val blank = try { f.readText().isBlank() } catch (_: Exception) { false }
                val named = Notes.named(this@EditorActivity, f)
                if (blank && !named) { Notes.delete(this@EditorActivity, f); file = null }
                else if (!named && adopt.isNotBlank()) file = Notes.rename(this@EditorActivity, f, adopt, typed = true)
            }
            leave()
        }
    }

    private fun sendText(text: String, title: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        startActivity(Intent.createChooser(i, null))
    }
}
