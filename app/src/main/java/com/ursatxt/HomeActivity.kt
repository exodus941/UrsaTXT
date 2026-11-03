package com.ursatxt

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : Activity() {

    private companion object {
        /** The path strip's height and the gap under it, in dp. */
        const val CRUMB_H = 48
        const val CRUMB_GAP = 16
    }

    private val pickZip = 1
    private val openNote = 2
    private val askFiles = 3

    private lateinit var dir: File
    private var items: List<Notes.Entry> = emptyList()

    private lateinit var frame: FrameLayout
    private lateinit var content: FrameLayout  // everything the modal dims and blurs
    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var version: TextView
    private lateinit var sortBtn: ImageView
    private lateinit var moreBtn: ImageView
    private lateinit var sticky: LinearLayout
    private lateinit var crumbBox: FrameLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var crumbs: LinearLayout
    private lateinit var search: EditText
    private lateinit var searchBox: LinearLayout
    private lateinit var searchIcon: ImageView
    private lateinit var clear: ImageView
    private lateinit var list: ListView
    private val rows by lazy { Adapter() }
    private val spacer by lazy { View(this).apply { layoutParams = AbsListView.LayoutParams(MATCH_PARENT, 0) } }
    private lateinit var empty: TextView
    private lateinit var topFade: Vignette
    private lateinit var bottomFade: Vignette
    private lateinit var modal: Modal
    private lateinit var menu: Menu
    private var bottomInset = 0
    private val onTheme: () -> Unit = { applyTheme() }

    // The + button and what it opens.
    private lateinit var glow: Glow
    private lateinit var fab: FrameLayout
    private lateinit var fabIcon: ImageView
    private lateinit var upBtn: ImageView
    private lateinit var dial: List<Pair<ImageView, TextView>>
    private lateinit var catcher: View
    private var dialOpen = false

    private val prefs by lazy { getSharedPreferences("ursa", MODE_PRIVATE) }
    private var sortKey: String
        get() = prefs.getString("sort", "opened") ?: "opened"
        set(v) { prefs.edit().putString("sort", v).apply() }
    private var ascending: Boolean
        get() = prefs.getBoolean("asc", false)
        set(v) { prefs.edit().putBoolean("asc", v).apply() }
    private var foldersFirst: Boolean
        get() = prefs.getBoolean("foldersFirst", true)
        set(v) { prefs.edit().putBoolean("foldersFirst", v).apply() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Theme.load(this)
        window.edgeToEdge()
        dir = Notes.root(this)
        frame = FrameLayout(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        frame.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        content.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(20), dpi(12), dpi(8), dpi(8))
        }
        title = label("UrsaTXT", 24f, Theme.text(), bold = true)
        version = label(BuildConfig.VERSION_NAME, 13f, Theme.text(), bold = true)
        // TOP gravity, not centre: only then does LinearLayout line the two baselines up.
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(title)
            addView(version, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dpi(8) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        sortBtn = iconButton(R.drawable.ic_sort, "Sort") { sortMenu(it) }
        header.addView(sortBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        moreBtn = iconButton(R.drawable.ic_more, "Options") { mainMenu(it) }
        header.addView(moreBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        root.addView(header)

        val body = FrameLayout(this)
        list = ListView(this).apply {
            divider = null
            selector = getDrawable(android.R.color.transparent)
            clipToPadding = false
            addHeaderView(spacer, null, false)
            adapter = rows
            setOnItemClickListener { _, _, pos, _ ->
                val e = items.getOrNull(pos - headerViewsCount) ?: return@setOnItemClickListener
                if (e.dir) go(e.file) else edit(e.file)
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView, s: Int) {
                    if (s == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
                }
                override fun onScroll(v: AbsListView, first: Int, count: Int, total: Int) = updateFades()
            })
        }
        body.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        empty = label("", 15f, Theme.sub()).apply { gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE; setPadding(dpi(32), 0, dpi(32), 0) }
        body.addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        topFade = Vignette(this, fromBottom = false)
        body.addView(topFade, FrameLayout.LayoutParams(MATCH_PARENT, dpi(96), Gravity.TOP))
        bottomFade = Vignette(this, fromBottom = true)
        body.addView(bottomFade, FrameLayout.LayoutParams(MATCH_PARENT, dpi(48), Gravity.BOTTOM))
        sticky = buildSticky()
        body.addView(sticky, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        sticky.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        buildFab()

        frame.setOnApplyWindowInsetsListener { _, ins ->
            val s = ins.safe()
            root.setPadding(s[0], s[1], s[2], 0)
            @Suppress("DEPRECATION")
            val ime = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.ime()).bottom else ins.systemWindowInsetBottom
            bottomInset = maxOf(s[3], ime)
            fabInsets(s[2], s[3])
            place()
            ins
        }
        setContentView(frame)
        modal = Modal(this, frame, content)
        menu = Menu(this, frame)
        Theme.listeners += onTheme
        applyTheme()
        frame.post { askStorage() }
    }

    private fun iconButton(icon: Int, name: String, act: (View) -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        background = ripple()
        setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
        contentDescription = name
        setOnClickListener { act(it) }
    }

    private fun buildSticky() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        setPadding(dpi(16), dpi(4), dpi(16), dpi(12))

        // The path, when inside a folder: it opens down from under the title bar, %ease250%.
        crumbs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // A strip with its own fill, 48 dp tall like the search box, with a 16 dp gap under
        // it so a press on a crumb cannot land on search.
        crumbScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dpi(4), 0, dpi(4), 0)
            clipToPadding = false
            addView(crumbs, FrameLayout.LayoutParams(WRAP_CONTENT, dpi(CRUMB_H)))
        }
        crumbBox = FrameLayout(context).apply { clipChildren = true; clipToPadding = true }
        crumbBox.addView(crumbScroll, FrameLayout.LayoutParams(MATCH_PARENT, dpi(CRUMB_H), Gravity.TOP))
        addView(crumbBox, LinearLayout.LayoutParams(MATCH_PARENT, 0))

        searchBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), 0, dpi(4), 0)
        }
        searchIcon = ImageView(context).apply { setImageResource(R.drawable.ic_search) }
        searchBox.addView(searchIcon, LinearLayout.LayoutParams(dpi(20), dpi(20)))
        search = EditText(context).apply {
            hint = "Search notes"
            textSize = 16f
            isSingleLine = true
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dpi(12), dpi(12), dpi(8), dpi(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
            setOnEditorActionListener { _, id, ev ->
                val enter = ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
                if (id == EditorInfo.IME_ACTION_SEARCH || enter) { hideKeyboard(); true } else false
            }
        }
        searchBox.addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        clear = iconButton(R.drawable.ic_close, "Clear search") {
            search.setText("")
            hideKeyboard()
            list.setSelection(0)
        }.apply { visibility = View.GONE }
        searchBox.addView(clear, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        addView(searchBox, LinearLayout.LayoutParams(MATCH_PARENT, dpi(48)))
    }

    /** List starts under the sticky block; the top vignette hangs from its lower edge. */
    private fun place() {
        val h = sticky.height
        if (spacer.layoutParams.height != h) { spacer.layoutParams.height = h; spacer.requestLayout() }
        // Room under the last card for the + button, so it never hides it.
        list.setPadding(0, 0, 0, bottomInset + fabRoom)
        topFade.translationY = h.toFloat()
        empty.translationY = h + dp(24f)
        val fadeH = dpi(48) + bottomInset
        if (bottomFade.layoutParams.height != fadeH) { bottomFade.layoutParams.height = fadeH; bottomFade.requestLayout() }
        updateFades()
    }

    private fun updateFades() {
        if (!::bottomFade.isInitialized) return
        topFade.show(list.canScrollVertically(-1))
        bottomFade.show(list.canScrollVertically(1))
    }

    override fun onDestroy() {
        super.onDestroy()
        Theme.listeners -= onTheme
    }

    /** Runs on every frame of a theme change; touches only colours. */
    private fun applyTheme() {
        root.setBackgroundColor(Theme.bg())
        window.decorView.setBackgroundColor(Theme.bg())
        sticky.setBackgroundColor(Theme.bg())
        title.setTextColor(Theme.text())
        version.setTextColor(Theme.text())
        for (b in listOf(sortBtn, moreBtn, clear)) {
            b.setColorFilter(Theme.text())
            (b.background as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        }
        searchBox.background = pill(Theme.button(), 14f)
        searchIcon.setColorFilter(Theme.sub())
        search.setTextColor(Theme.text())
        search.setHintTextColor(Theme.sub())
        empty.setTextColor(Theme.sub())
        paintCrumbs()
        paintFab()
        for (i in 0 until list.childCount) paintCard(list.getChildAt(i))
        topFade.invalidate(); bottomFade.invalidate()
        Theme.applyBars(window)
    }

    // ---- where we are ----

    private fun atRoot() = dir == Notes.root(this)

    /** Into [to], with the list fading out and back in, 250 ms in all. */
    private fun go(to: File) {
        if (to == dir) return
        closeDial()
        hideKeyboard()
        if (search.text.isNotEmpty()) search.setText("")
        val wasRoot = atRoot()
        list.animate().cancel()
        list.animate().alpha(0f).setDuration(Motion.MS / 2).setInterpolator(Motion.FADE).withEndAction {
            dir = to
            Notes.touch(this, to)
            refresh()
            list.setSelection(0)
            list.animate().alpha(1f).setDuration(Motion.MS / 2).setInterpolator(Motion.FADE).start()
        }.start()
        buildCrumbs(to)
        if (wasRoot != (to == Notes.root(this))) {
            showCrumbs(wasRoot)
            showUp(wasRoot)
        }
    }

    private fun up() { if (!atRoot()) dir.parentFile?.let { go(it) } }

    private fun buildCrumbs(at: File) {
        crumbs.removeAllViews()
        val r = Notes.root(this)
        val chain = ArrayList<File>()
        var f: File? = at
        while (f != null && f != r) { chain.add(0, f); f = f.parentFile }
        chain.add(0, r)
        chain.forEachIndexed { k, d ->
            if (k > 0) crumbs.addView(ImageView(this).apply { setImageResource(R.drawable.ic_chevron); tag = "chev" },
                LinearLayout.LayoutParams(dpi(16), dpi(16)).apply { marginStart = dpi(2); marginEnd = dpi(2) })
            crumbs.addView(label(if (d == r) "Root" else d.name, 14f, Theme.sub(), bold = true).apply {
                tag = if (k == chain.size - 1) "here" else "crumb"
                background = ripple()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(12), 0, dpi(12), 0)
                setOnClickListener { go(d) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dpi(CRUMB_H)))
        }
        paintCrumbs()
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun paintCrumbs() {
        crumbScroll.background = pill(Theme.card(), 14f)
        for (i in 0 until crumbs.childCount) {
            val v = crumbs.getChildAt(i)
            when (v.tag) {
                "chev" -> (v as ImageView).setColorFilter(Theme.sub())
                "here" -> (v as TextView).setTextColor(Theme.text())
                else -> (v as TextView).setTextColor(Theme.sub())
            }
            (v.background as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        }
    }

    private var crumbAnim: ValueAnimator? = null
    private fun showCrumbs(show: Boolean) {
        val lp = crumbBox.layoutParams as LinearLayout.LayoutParams
        val from = lp.height
        val full = dpi(CRUMB_H + CRUMB_GAP)
        val to = if (show) full else 0
        crumbAnim?.cancel()
        crumbAnim = ValueAnimator.ofInt(from, to).apply {
            duration = Motion.MS
            interpolator = if (show) Motion.IN else Motion.OUT
            addUpdateListener {
                lp.height = it.animatedValue as Int
                crumbScroll.alpha = lp.height / full.toFloat()
                crumbBox.requestLayout()
            }
            start()
        }
    }

    // ---- the list ----

    private fun refresh() {
        if (!dir.exists()) dir = Notes.root(this)
        val q = search.text.toString().trim()
        clear.fade(search.text.isNotEmpty())
        val all = if (q.isEmpty()) Notes.list(this, dir) else Notes.search(this, dir, q)
        items = sorted(all)
        empty.text = when {
            q.isNotEmpty() && items.isEmpty() -> "No notes contain “$q”"
            items.isEmpty() -> if (atRoot()) "No notes yet. Tap + to write one." else "This folder is empty."
            else -> ""
        }
        empty.fade(empty.text.isNotEmpty())
        rows.notifyDataSetChanged()
        if (q != lastQuery) list.setSelection(0)
        lastQuery = q
        list.post { updateFades() }
    }
    private var lastQuery = ""

    private fun sorted(all: List<Notes.Entry>): List<Notes.Entry> {
        val by: Comparator<Notes.Entry> = when (sortKey) {
            "name" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            "added" -> compareBy { it.added }
            "size" -> compareBy { it.size }
            else -> compareBy { it.opened }
        }
        val dirBy = if (ascending) by else by.reversed()
        // Pinned notes stay on top; then folders, when Folders First is on.
        var c = compareByDescending<Notes.Entry> { it.pinned }
        if (foldersFirst) c = c.thenByDescending { it.dir }
        return all.sortedWith(c.then(dirBy).thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    private fun stamp(t: Long): String {
        val now = System.currentTimeMillis()
        val ago = if (now - t < DateUtils.MINUTE_IN_MILLIS) "Just now"
            else DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS).toString()
        val clock = android.text.format.DateFormat.getTimeFormat(this).format(Date(t))
        val day = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(t))
        return "${ago.replaceFirstChar { it.uppercase() }}  ·  $day, $clock"
    }

    // ---- menus ----

    private fun mainMenu(anchor: View) {
        val rows = listOf(
            Menu.Row("Dark Mode", on = { !Theme.light }) {
                Theme.toggle(this)
                note(if (Theme.light) "Dark Mode off" else "Dark Mode on")
            },
            Menu.Row("Folders First", on = { foldersFirst }) {
                foldersFirst = !foldersFirst
                refresh()
                note(if (foldersFirst) "Folders First on" else "Folders First off")
            },
            Menu.Row("Export Notes", icon = R.drawable.ic_download) { exportAll() },
            Menu.Row("Import Notes", icon = R.drawable.ic_upload) { importPick() },
            Menu.Row("Check for Updates", icon = R.drawable.ic_refresh) { Updater.check(this) },
        )
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menuBack = null
        menu.show(rows, Gravity.TOP or Gravity.END, dpi(12), at[1])
    }

    private fun sortMenu(anchor: View) {
        fun key(k: String, t: String) = Menu.Row(t, mark = { if (sortKey == k) R.drawable.ic_radio_on else R.drawable.ic_radio_off }, stay = true) {
            sortKey = k; refresh()
        }
        fun way(asc: Boolean, t: String) = Menu.Row(t, mark = { if (ascending == asc) R.drawable.ic_radio_on else R.drawable.ic_radio_off }, stay = true) {
            ascending = asc; refresh()
        }
        val rows = listOf(
            key("name", "Name"), key("opened", "Date Accessed"), key("added", "Date Added"), key("size", "Size"),
            Menu.SEP,
            way(true, "Ascending"), way(false, "Descending"),
        )
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menuBack = null
        menu.show(rows, Gravity.TOP or Gravity.END, dpi(60), at[1])
    }

    private fun cardMenu(anchor: View, e: Notes.Entry) {
        val kind = if (e.dir) "folder" else "note"
        val rows = listOf(
            Menu.Row(if (e.pinned) "Unpin" else "Pin", icon = if (e.pinned) R.drawable.ic_unpin else R.drawable.ic_pin) {
                Notes.setPin(this, e.file, !e.pinned)
                refresh()
                note(if (e.pinned) "Unpinned “${e.name}”" else "Pinned “${e.name}”")
            },
            Menu.Row("Rename", icon = R.drawable.ic_rename) { rename(e) },
            Menu.Row("Move/Copy", icon = R.drawable.ic_copy) { moveCopy(e) },
        ) + (if (e.dir) emptyList() else listOf(
            Menu.Row("Share", icon = R.drawable.ic_share) { shareMenu(anchor, e) },
        )) + listOf(
            Menu.Row("Delete", icon = R.drawable.ic_delete) {
                val what = if (e.dir) "“${e.name}” and everything in it will be deleted from this device." else "“${e.name}” will be deleted from this device."
                modal.confirm("Delete $kind?", "$what This cannot be undone.", "Delete") {
                    if (Notes.delete(this, e.file)) { refresh(); note("Deleted “${e.name}”") } else note("Couldn’t delete “${e.name}”")
                }
            },
        )
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menuBack = null
        menu.show(rows, Gravity.TOP or Gravity.END, dpi(12), at[1])
    }

    /** Where Back goes from a menu that replaced another; null closes it. */
    private var menuBack: (() -> Unit)? = null
    private val convert by lazy { Convert(this) }

    /**
     * The card menu's place taken by the editor's share menu, the same formats and
     * words. Return, or the Back button, puts the card menu back.
     */
    private fun shareMenu(anchor: View, e: Notes.Entry) {
        val back = { cardMenu(anchor, e) }
        fun md() = try { e.file.readText() } catch (_: Exception) { "" }
        fun file(ext: String) = "${e.name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(60).ifEmpty { "Untitled note" }}.$ext"
        fun save(name: String, mime: String, text: String) {
            val ok = saveAndShare(name, mime) { it.write(text.toByteArray()) }
            note(if (ok) "Saved “$name” to Downloads" else "Couldn’t save “$name” to Downloads")
        }
        val rows = listOf(
            Menu.Row("Return", icon = R.drawable.ic_back) { back() },
            Menu.Row("MD", icon = R.drawable.ic_download) { save(file("md"), "text/markdown", md()) },
            Menu.Row("HTML", icon = R.drawable.ic_download) {
                convert.run("noteToHtmlPage", e.name, md()) { save(file("html"), "text/html", it) }
            },
            Menu.Row("TXT", icon = R.drawable.ic_download) {
                convert.run("markdownToText", md()) { save(file("txt"), "text/plain", it) }
            },
            Menu.Row("Copy to Clipboard", icon = R.drawable.ic_copy) {
                val text = md().trim()
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText(e.name, text))
                note("Note copied to clipboard")
                sendText(text, e.name)
            },
            Menu.Row("External", icon = R.drawable.ic_share) { sendText(md().trim(), e.name) },
        )
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menu.show(rows, Gravity.TOP or Gravity.END, dpi(12), at[1])
        menuBack = back
    }

    private fun sendText(text: String, title: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        startActivity(Intent.createChooser(i, null))
    }

    private fun rename(e: Notes.Entry) {
        modal.input(if (e.dir) "Rename folder" else "Rename note", e.name, "Rename") { raw ->
            if (raw.isEmpty() || raw == e.name) return@input
            val to = Notes.rename(this, e.file, raw)
            refresh()
            note("Renamed to “${to.name.removeSuffix(Notes.EXT)}”")
        }
    }

    // ---- Move/Copy: the folder tree ----

    private var tree: Tree? = null

    private fun moveCopy(e: Notes.Entry) {
        val t = Tree(this, Notes.root(this), e.file)
        tree = t
        modal.custom("Move/Copy", t.view) { row, make, close ->
            val copy = make("Copy", Theme.accentInk()) {
                val into = t.picked ?: return@make
                close()
                val done = Notes.copy(this, e.file, into)
                refresh()
                note(if (done != null) "Copied “${e.name}” to ${where(into)}" else "Couldn’t copy “${e.name}”")
            }
            val move = make("Move", Theme.accentInk()) {
                val into = t.picked ?: return@make
                close()
                val done = Notes.move(this, e.file, into)
                refresh()
                note(if (done != null) "Moved “${e.name}” to ${where(into)}" else "Couldn’t move “${e.name}”")
            }
            row.addView(make("Cancel", Theme.sub()) { close() })
            row.addView(copy)
            row.addView(move)
            fun enable() {
                val p = t.picked
                val okCopy = p != null
                val okMove = p != null && p != e.file.parentFile
                for ((v, ok) in listOf(copy to okCopy, move to okMove)) {
                    v.isEnabled = ok
                    v.animate().alpha(if (ok) 1f else 0.38f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
                }
            }
            copy.alpha = 0.38f; move.alpha = 0.38f; copy.isEnabled = false; move.isEnabled = false
            t.onPick = { enable() }
            // The tree opens on the folder the item is in, with Create New Folder Here under it.
            t.pick(e.file.parentFile ?: Notes.root(this))
        }
    }

    private fun where(f: File) = if (f == Notes.root(this)) "Root" else "“${f.name}”"

    // ---- the + button ----

    private fun buildFab() {
        glow = Glow(this, 56f)
        val reach = glow.reach()
        content.addView(glow, FrameLayout.LayoutParams(dpi(56) + 2 * reach, dpi(56) + 2 * reach, Gravity.BOTTOM or Gravity.END))
        catcher = View(this).apply {
            visibility = View.GONE
            isClickable = true
            setOnClickListener { closeDial() }
        }
        content.addView(catcher, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        fun small(icon: Int, name: String, act: () -> Unit) = ImageView(this).apply {
            setImageResource(icon)
            background = Squircle(this@HomeActivity, 0, ACCENT)
            foreground = ripple()
            clipToOutline = true
            elevation = dp(4f)
            setPadding(dpi(10), dpi(10), dpi(10), dpi(10))
            contentDescription = name
            setOnClickListener { act() }
        }
        fun tag(text: String) = label(text, 14f, Theme.text(), bold = true).apply {
            setPadding(dpi(12), dpi(6), dpi(12), dpi(6))
            elevation = dp(4f)
            alpha = 0f
            visibility = View.INVISIBLE
        }
        val newNote = small(R.drawable.ic_note_plus, "New Note") { closeDial(); makeNote() }
        val newFolder = small(R.drawable.ic_folder_plus, "New Folder") { closeDial(); makeFolder() }
        dial = listOf(newNote to tag("New Note"), newFolder to tag("New Folder"))
        for ((b, t) in dial) {
            b.alpha = 0f; b.visibility = View.INVISIBLE
            content.addView(t, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
            content.addView(b, FrameLayout.LayoutParams(dpi(44), dpi(44), Gravity.BOTTOM or Gravity.END))
        }
        upBtn = small(R.drawable.ic_up, "Up a folder") { up() }.apply { alpha = 0f; visibility = View.INVISIBLE }
        content.addView(upBtn, FrameLayout.LayoutParams(dpi(44), dpi(44), Gravity.BOTTOM or Gravity.END))

        // The squircle stays put; only the + inside it turns to an ×.
        fabIcon = ImageView(this).apply { setImageResource(R.drawable.ic_plus) }
        fab = FrameLayout(this).apply {
            background = Squircle(this@HomeActivity, ACCENT)
            foreground = ripple()
            clipToOutline = true
            elevation = dp(6f)
            contentDescription = "New"
            setOnClickListener { if (dialOpen) closeDial() else openDial() }
            addView(fabIcon, FrameLayout.LayoutParams(dpi(24), dpi(24), Gravity.CENTER))
        }
        content.addView(fab, FrameLayout.LayoutParams(dpi(56), dpi(56), Gravity.BOTTOM or Gravity.END))
        paintFab()
    }

    private var fabRoom = 0
    private var fabEnd = 0
    private var fabBottom = 0

    /**
     * Where the + sits, from the cards' ⋮ column rather than a fixed corner gap. The list
     * keeps room under its last card for the button, so it never covers a card's ⋮.
     */
    private fun fabInsets(end: Int, bottom: Int) {
        // Sideways it stays clear of the cards' ⋮ buttons: each is a 48 dp target whose right
        // edge is 20 dp from the screen's (16 dp card inset + 4 dp), so the + ends 8 dp to its
        // left, 76 dp in.
        // The same 76 dp from the bottom as from the right edge, or 16 dp over the
        // navigation bar where that is taller.
        fabEnd = end + dpi(16 + 4 + 48 + 8); fabBottom = maxOf(dpi(76), bottom + dpi(16))
        fabRoom = fabBottom - bottom + dpi(56) + dpi(16)
        val reach = glow.reach()
        (fab.layoutParams as FrameLayout.LayoutParams).apply { marginEnd = fabEnd; bottomMargin = fabBottom; rightMargin = fabEnd }
        (glow.layoutParams as FrameLayout.LayoutParams).apply { marginEnd = fabEnd - reach; rightMargin = fabEnd - reach; bottomMargin = fabBottom - reach }
        // The small buttons centre on the + button's column.
        val sEnd = fabEnd + dpi(6)
        for ((b, t) in dial + listOf(upBtn to null)) {
            (b.layoutParams as FrameLayout.LayoutParams).apply { marginEnd = sEnd; rightMargin = sEnd; bottomMargin = fabBottom + dpi(6) }
            t?.let { (it.layoutParams as FrameLayout.LayoutParams).apply { marginEnd = sEnd + dpi(44) + dpi(12); rightMargin = marginEnd; bottomMargin = fabBottom + dpi(6) + dpi(8) } }
        }
        frame.requestLayout()
        placeDial(if (dialOpen) 1f else 0f)
    }

    /** One slot: a 44 dp button and a 16 dp gap. The first slot sits 16 dp over the + button. */
    private fun slot(k: Int) = -(dp(56f) + dp(16f) + k * dp(60f) - dp(6f))

    private fun placeDial(t: Float) {
        // The up button sits in slot 0 while closed and is pushed up two slots as the dial opens.
        upBtn.translationY = slot(0) + (slot(2) - slot(0)) * t
    }

    private var dialAnim: ValueAnimator? = null

    private fun openDial() {
        if (dialOpen) return
        dialOpen = true
        catcher.visibility = View.VISIBLE
        fabIcon.animate().rotation(45f).setDuration(Motion.MS).setInterpolator(Motion.IN).start()
        fab.contentDescription = "Close"
        // One after the other, each starting halfway through the last, %ease250%.
        dial.forEachIndexed { k, (b, t) ->
            for (v in listOf(b, t)) {
                v.animate().cancel()
                v.visibility = View.VISIBLE
                v.translationY = slot(k) + dp(24f)
                v.alpha = 0f
                v.animate().translationY(slot(k)).alpha(1f).setStartDelay(k * Motion.MS / 2).setDuration(Motion.MS)
                    .setInterpolator(Motion.IN).withEndAction(null).start()
            }
        }
        animateUp(1f)
    }

    private fun closeDial() {
        if (!dialOpen) return
        dialOpen = false
        catcher.visibility = View.GONE
        fabIcon.animate().rotation(0f).setDuration(Motion.MS).setInterpolator(Motion.OUT).start()
        fab.contentDescription = "New"
        val n = dial.size
        dial.forEachIndexed { k, (b, t) ->
            for (v in listOf(b, t)) {
                v.animate().cancel()
                v.animate().translationY(slot(k) + dp(24f)).alpha(0f).setStartDelay((n - 1 - k) * Motion.MS / 2).setDuration(Motion.MS)
                    .setInterpolator(Motion.OUT).withEndAction { v.visibility = View.INVISIBLE }.start()
            }
        }
        animateUp(0f)
    }

    private var upT = 0f
    private fun animateUp(to: Float) {
        dialAnim?.cancel()
        dialAnim = ValueAnimator.ofFloat(upT, to).apply {
            duration = (Motion.MS * kotlin.math.abs(to - upT)).toLong() + if (to > upT) Motion.MS / 2 else 0
            interpolator = if (to > upT) Motion.IN else Motion.OUT
            addUpdateListener { upT = it.animatedValue as Float; placeDial(upT) }
            start()
        }
    }

    /** The up button fades in once inside a folder and out at the root. */
    private fun showUp(show: Boolean) {
        upBtn.animate().cancel()
        if (show) { upBtn.visibility = View.VISIBLE }
        upBtn.animate().alpha(if (show) 1f else 0f).setStartDelay(0).setDuration(Motion.MS).setInterpolator(Motion.FADE)
            .withEndAction { if (!show) upBtn.visibility = View.INVISIBLE }.start()
    }

    private fun paintFab() {
        if (!::fab.isInitialized) return
        fabIcon.setColorFilter(BG)
        (fab.foreground as? RippleDrawable)?.setColor(ColorStateList.valueOf(0x33000000))
        // The up button is grey: it moves you, it makes nothing.
        for (b in dial.map { it.first } + upBtn) {
            val ink = if (b === upBtn) Theme.sub() else Theme.accentInk()
            (b.background as Squircle).apply { fill = Theme.bg(); stroke = ink }
            b.background.invalidateSelf()
            b.setColorFilter(ink)
            (b.foreground as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        }
        for ((_, t) in dial) {
            t.setTextColor(Theme.text())
            t.background = pill(Theme.popup(), 10f).apply { setStroke(dpi(1), Theme.outline()) }
        }
        glow.invalidate()
    }

    // ---- making things ----

    private fun makeNote() {
        val f = Notes.newNote(this, dir)
        edit(f)
    }

    private fun makeFolder() {
        modal.input("New folder", "New Folder", "Create") { raw ->
            val f = Notes.newFolder(this, dir, raw.ifEmpty { "New Folder" })
            refresh()
            note("Created “${f.name}”")
        }
    }

    private fun edit(f: File) {
        closeDial()
        hideKeyboard()
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, EditorActivity::class.java).putExtra("path", f.absolutePath), openNote)
        @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ---- backup ----

    private fun exportAll() {
        val name = "UrsaTXT-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".zip"
        var n = 0
        val ok = saveAndShare(name, "application/zip") { n = Notes.export(this, it) }
        note(if (ok) "Exported ${count(n)} to Downloads as $name" else "Couldn’t save the backup to Downloads")
    }

    private fun count(n: Int) = if (n == 1) "1 note" else "$n notes"

    private fun importPick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, pickZip)
    }

    // ---- storage access ----

    private fun askStorage() {
        if (Notes.shared() && (Build.VERSION.SDK_INT >= 30 || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED)) return
        if (prefs.getBoolean("askedFiles", false)) return
        prefs.edit().putBoolean("askedFiles", true).apply()
        modal.choose("Keep notes in Documents?",
            "UrsaTXT saves each note as a .md file in Documents/UrsaTXT, where other apps can open them. Android asks you to allow file access for that. Until then, notes stay inside the app.",
            listOf(
                "Allow" to {
                    if (Build.VERSION.SDK_INT >= 30) {
                        try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                        catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    } else requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), askFiles)
                },
                "Not Now" to {},
            ))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (code == askFiles) { dir = Notes.root(this); refresh() }
    }

    // ---- system ----

    @Deprecated("Deprecated in Java")
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::menu.isInitialized && menu.showing && ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            // The menu is its own window, so a touch reaching this one is outside it: it only closes the menu.
            menu.dismiss(); swallow = true
        }
        if (swallow) { if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) swallow = false; return true }
        return super.dispatchTouchEvent(ev)
    }
    private var swallow = false

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val t = tree
        when {
            menu.showing && menuBack != null -> { val b = menuBack!!; menuBack = null; menu.dismiss(); b() }
            menu.showing -> menu.dismiss()
            modal.showing && t != null && t.editing -> t.stopEditing()
            modal.showing -> { modal.cancel(); tree = null }
            dialOpen -> closeDial()
            search.text.isNotEmpty() -> search.setText("")
            !atRoot() -> up()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        val r = Notes.root(this)
        if (!dir.absolutePath.startsWith(r.absolutePath)) { dir = r; buildCrumbs(r); showCrumbs(false); showUp(false) }
        Notes.sweep(this, dir)
        refresh()
        glow.start()
        Updater.resumed(this, home = true)
    }

    override fun onPause() {
        super.onPause()
        glow.stop()
        Updater.paused(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(req, res, data)
        if (req == openNote) { data?.getStringExtra("note")?.let { note(it) }; refresh(); return }
        val uri = data?.data
        if (req == pickZip && res == RESULT_OK && uri != null) {
            val n = try { contentResolver.openInputStream(uri)?.use { Notes.import(this, it) } ?: -1 } catch (_: Exception) { -1 }
            refresh()
            note(if (n >= 0) "Imported ${count(n)}" else "Couldn’t read that file as an UrsaTXT backup")
        }
    }

    // ---- cards ----

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int): Any = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (items[p].dir) 0 else 1

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val e = items[p]
            val card = (convert as? FrameLayout) ?: card(e.dir)
            card.tag = e
            val box = card.getChildAt(0) as LinearLayout
            val texts = box.findViewWithTag<LinearLayout>("texts")
            val head = texts.getChildAt(0) as LinearLayout
            (head.getChildAt(0) as TextView).text = e.name
            box.findViewWithTag<View>("pin").visibility = if (e.pinned) View.VISIBLE else View.GONE
            if (e.dir) {
                val parts = ArrayList<String>()
                parts += if (e.notes == 1) "1 note" else "${e.notes} notes"
                if (e.folders > 0) parts += if (e.folders == 1) "1 folder" else "${e.folders} folders"
                (texts.getChildAt(1) as TextView).text = parts.joinToString("  ·  ")
            } else {
                val prev = texts.getChildAt(1) as TextView
                prev.text = e.preview
                prev.visibility = if (e.preview.isEmpty()) View.GONE else View.VISIBLE
                (texts.getChildAt(2) as TextView).text = stamp(e.opened)
            }
            box.findViewWithTag<View>("more").setOnClickListener { v -> cardMenu(v, e) }
            paintCard(card)
            return card
        }

        /** A card: a colour strip on its edge, the texts, and a ⋮. */
        private fun card(folder: Boolean) = FrameLayout(this@HomeActivity).apply {
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dpi(16), dpi(4), dpi(16), dpi(4))
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipToOutline = true
                foreground = ripple()
                tag = "box"
            }
            // The strip sits outside the card's 16 dp inset (Yeetlist's rule): 4 dp.
            box.addView(View(context).apply { tag = "strip" }, LinearLayout.LayoutParams(dpi(4), MATCH_PARENT))
            if (folder) box.addView(ImageView(context).apply { setImageResource(R.drawable.ic_folder); tag = "icon" },
                LinearLayout.LayoutParams(dpi(22), dpi(22)).apply { marginStart = dpi(16) })
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                tag = "texts"
                setPadding(dpi(16), dpi(14), 0, dpi(14))
            }
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(label("", 16f, Theme.text(), bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            texts.addView(head)
            if (folder) {
                texts.addView(label("", 12f, Theme.sub()).apply { setPadding(0, dpi(2), 0, 0) })
            } else {
                texts.addView(label("", 14f, Theme.sub()).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dpi(4), 0, 0); tag = "preview" })
                texts.addView(label("", 12f, Theme.sub()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dpi(6), 0, 0); tag = "date" })
            }
            box.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            // A pin, when pinned: the ⋮ glyph's 24 dp, centred on the same line, its glyph 12 dp
            // from the ⋮ glyph (the ⋮ carries 12 dp of padding inside its 48 dp target).
            box.addView(ImageView(context).apply { setImageResource(R.drawable.ic_pin); tag = "pin"; contentDescription = "Pinned" },
                LinearLayout.LayoutParams(dpi(24), dpi(24)).apply { marginStart = dpi(8) })
            box.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_more)
                background = ripple()
                setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
                contentDescription = if (folder) "Folder options" else "Note options"
                isFocusable = false
                tag = "more"
            }, LinearLayout.LayoutParams(dpi(48), dpi(48)).apply { marginEnd = dpi(4) })
            addView(box, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun hueColor(h: Int): Int {
        val hsv = floatArrayOf(h.toFloat(), 0.75f, 1f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun paintCard(v: View) {
        val card = v as? FrameLayout ?: return
        val e = card.tag as? Notes.Entry ?: return
        val box = card.getChildAt(0) as LinearLayout
        box.background = pill(Theme.card(), 12f)
        (box.foreground as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        box.findViewWithTag<View>("strip").setBackgroundColor(if (e.dir) Theme.accentInk() else hueColor(e.hue))
        box.findViewWithTag<ImageView>("icon")?.setColorFilter(Theme.accentInk())
        val texts = box.findViewWithTag<LinearLayout>("texts")
        val head = texts.getChildAt(0) as LinearLayout
        (head.getChildAt(0) as TextView).setTextColor(Theme.text())
        box.findViewWithTag<ImageView>("pin").setColorFilter(Theme.accentInk())
        for (i in 1 until texts.childCount) (texts.getChildAt(i) as TextView).setTextColor(Theme.sub())
        box.findViewWithTag<ImageView>("more").apply {
            setColorFilter(Theme.sub())
            (background as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        }
    }
}
