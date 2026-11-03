package com.ursatxt

import android.annotation.SuppressLint
import android.app.Activity
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * The editor's own converter (assets/editor/notes.js), run in a web view nobody sees,
 * so a note shared from the list as HTML or TXT comes out exactly as from the editor.
 * One converter, not a second one in Kotlin.
 */
@SuppressLint("SetJavaScriptEnabled")
class Convert(private val a: Activity) {
    private var web: WebView? = null
    private var ready = false
    private val waiting = ArrayList<() -> Unit>()

    private fun view(): WebView = web ?: WebView(a).also { w ->
        web = w
        w.settings.javaScriptEnabled = true
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                ready = true
                waiting.toList().forEach { it() }
                waiting.clear()
            }
        }
        w.loadDataWithBaseURL("file:///android_asset/editor/", "<script src=\"notes.js\"></script>", "text/html", "utf-8", null)
    }

    /** Runs [fn] (a notes.js function name) on [args] and hands back its string result. */
    fun run(fn: String, vararg args: String, done: (String) -> Unit) {
        val w = view()
        val call = {
            val js = "$fn(...${JSONArray(args.toList())})"
            w.evaluateJavascript(js) { raw ->
                // evaluateJavascript returns the value as JSON: a quoted string.
                val s = try { JSONArray("[$raw]").getString(0) } catch (_: Exception) { "" }
                done(s)
            }
        }
        if (ready) call() else waiting += call
    }

    /** The name the editor would show for [md]: its first heading or line. */
    fun title(md: String, done: (String) -> Unit) = run("titleOf", md, done = done)

    fun destroy() { web?.destroy(); web = null }
}
