package com.ursatxt

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * The folder tree for Move/Copy: Root and every folder under it, indented by depth,
 * scrolling once taller than half the screen. Picking a folder opens a "Create New
 * Folder Here" row under it; that row turns into a name input in place. Enter makes
 * the folder and picks it; Back drops the name. Every row that comes or goes opens
 * or closes its height and fades, %ease250%.
 */
class Tree(private val a: Activity, private val root: File, private val moving: File) {

    var picked: File? = null; private set
    var onPick: () -> Unit = {}
    val editing get() = input != null

    private val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val max = (a.resources.displayMetrics.heightPixels * 0.45f).toInt()
    val view: View = object : ScrollView(a) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
    }.apply {
        isVerticalScrollBarEnabled = true
        addView(box, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private var create: LinearLayout? = null
    private var input: EditText? = null
    private val rows = LinkedHashMap<File, LinearLayout>()

    init { add(root, 0, box.childCount) }

    /** [f]'s row and its sub-folders' rows, from [at] in the list. Returns the next index. */
    private fun add(f: File, depth: Int, at: Int, animate: Boolean = false): Int {
        val row = folderRow(f, depth)
        box.addView(row, at, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        rows[f] = row
        if (animate) grow(row)
        var k = at + 1
        val kids = (f.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        for (c in kids) k = add(c, depth + 1, k, animate)
        return k
    }

    private fun depthOf(f: File): Int {
        var d = 0; var p: File? = f
        while (p != null && p != root) { d++; p = p.parentFile }
        return d
    }

    /** A folder cannot go into itself or a folder inside it. */
    private fun allowed(f: File) = Notes.canGo(moving, f)

    private fun folderRow(f: File, depth: Int) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        tag = f
        setPadding(a.dpi(8 + 20 * depth), a.dpi(10), a.dpi(12), a.dpi(10))
        background = a.pill(0, 10f)
        foreground = a.ripple()
        val ok = allowed(f)
        addView(ImageView(a).apply { setImageResource(R.drawable.ic_folder); tag = "icon" }, LinearLayout.LayoutParams(a.dpi(20), a.dpi(20)))
        addView(a.label(if (f == root) "Root" else f.name, 15f, Theme.text()).apply {
            tag = "text"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(a.dpi(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        alpha = if (ok) 1f else 0.38f
        isEnabled = ok
        setOnClickListener { pick(f) }
        paintRow(this, false)
    }

    private fun paintRow(row: LinearLayout, on: Boolean) {
        (row.background as android.graphics.drawable.GradientDrawable).setColor(if (on) Theme.selected() else 0)
        row.findViewWithTag<ImageView>("icon").setColorFilter(if (on) Theme.accentInk() else Theme.sub())
        row.findViewWithTag<TextView>("text").apply {
            setTextColor(if (on) Theme.accentInk() else Theme.text())
            setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        (row.foreground as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
    }

    private fun pick(f: File) {
        if (picked == f && create != null) return
        stopEditing()
        picked?.let { rows[it]?.let { r -> paintRow(r, false) } }
        picked = f
        rows[f]?.let { paintRow(it, true) }
        create?.let { shrink(it) }
        val row = rows[f] ?: return
        val c = createRow(depthOf(f) + 1)
        box.addView(c, box.indexOfChild(row) + 1, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        grow(c)
        create = c
        onPick()
    }

    private fun createRow(depth: Int) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        tag = "create"
        setPadding(a.dpi(8 + 20 * depth), a.dpi(10), a.dpi(12), a.dpi(10))
        foreground = a.ripple()
        background = a.pill(0, 10f)
        addView(ImageView(a).apply { setImageResource(R.drawable.ic_folder_plus); setColorFilter(Theme.accentInk()) }, LinearLayout.LayoutParams(a.dpi(20), a.dpi(20)))
        val slot = FrameLayout(a)
        slot.addView(a.label("Create New Folder Here", 15f, Theme.accentInk(), bold = true).apply { tag = "text"; setPadding(a.dpi(12), 0, 0, 0) },
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        addView(slot, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        setOnClickListener { startEditing(this, slot) }
    }

    private fun startEditing(row: LinearLayout, slot: FrameLayout) {
        if (input != null) return
        val text = slot.findViewWithTag<TextView>("text")
        val f = EditText(a).apply {
            hint = "Folder name"
            textSize = 15f
            isSingleLine = true
            setTextColor(Theme.text())
            setHintTextColor(Theme.sub())
            background = null
            setPadding(a.dpi(12), 0, 0, 0)
            imeOptions = EditorInfo.IME_ACTION_DONE
            alpha = 0f
            setOnEditorActionListener { _, id, ev ->
                val enter = ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
                if (id == EditorInfo.IME_ACTION_DONE || enter) { commit(); true } else false
            }
            // Back on the keyboard comes here first: it drops the name.
            setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) { stopEditing(); true } else false
            }
        }
        slot.addView(f, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        text.animate().alpha(0f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
        f.animate().alpha(1f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
        row.isClickable = false
        input = f
        f.requestFocus()
        f.post { a.getSystemService(InputMethodManager::class.java).showSoftInput(f, 0) }
    }

    /** Puts the label back, saving nothing. */
    fun stopEditing() {
        val f = input ?: return
        input = null
        val slot = f.parent as FrameLayout
        val row = slot.parent as LinearLayout
        f.animate().alpha(0f).setDuration(Motion.MS).setInterpolator(Motion.FADE).withEndAction { slot.removeView(f) }.start()
        slot.findViewWithTag<TextView>("text").animate().alpha(1f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
        row.isClickable = true
        a.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(f.windowToken, 0)
    }

    private fun commit() {
        val f = input ?: return
        val name = f.text.toString().trim()
        val parent = picked ?: return
        if (name.isEmpty()) { stopEditing(); return }
        input = null
        a.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(f.windowToken, 0)
        val made = Notes.newFolder(a, parent, name)
        // The new folder takes its place among its siblings, by name.
        val siblings = (parent.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val c = create
        create = null
        c?.let { shrink(it) }
        val i = siblings.indexOf(made)
        val after = if (i > 0) last(siblings[i - 1]) else rows[parent]
        val at = box.indexOfChild(after) + 1
        add(made, depthOf(made), at, animate = true)
        rows[parent]?.let { paintRow(it, false) }
        picked = null
        pick(made)
    }

    /** The last row inside [f]'s branch. */
    private fun last(f: File): View? {
        var v: View? = rows[f]
        val p = f.absolutePath + "/"
        for ((k, r) in rows) if (k.absolutePath.startsWith(p)) v = r
        return v
    }

    private fun grow(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(box.width.takeIf { it > 0 } ?: a.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = v.measuredHeight
        v.layoutParams.height = 0
        v.alpha = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.MS
            interpolator = Motion.IN
            addUpdateListener {
                val t = it.animatedValue as Float
                v.layoutParams.height = (h * t).toInt(); v.alpha = t * if (v.isEnabled || v.tag == "create") 1f else 0.38f
                v.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: android.animation.Animator) { v.layoutParams.height = WRAP_CONTENT; v.requestLayout() }
            })
            start()
        }
    }

    private fun shrink(v: View) {
        val h = v.height
        v.isEnabled = false
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = Motion.MS
            interpolator = Motion.OUT
            addUpdateListener {
                val t = it.animatedValue as Float
                v.layoutParams.height = (h * t).toInt(); v.alpha = t
                v.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: android.animation.Animator) { box.removeView(v) }
            })
            start()
        }
    }
}
