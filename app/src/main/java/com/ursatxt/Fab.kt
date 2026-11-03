package com.ursatxt

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** A superellipse, |x|^4 + |y|^4 = 1, filling [w] by [h] from (x, y). */
fun squirclePath(x: Float, y: Float, w: Float, h: Float, p: Path = Path()): Path {
    p.reset()
    val n = 4.0
    val steps = 96
    for (i in 0..steps) {
        val t = 2 * Math.PI * i / steps
        val c = cos(t); val s = sin(t)
        val px = x + w / 2 + (w / 2) * (sign(c) * abs(c).pow(2 / n)).toFloat()
        val py = y + h / 2 + (h / 2) * (sign(s) * abs(s).pow(2 / n)).toFloat()
        if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
    }
    p.close()
    return p
}

/** A squircle, filled with [fill] or stroked [strokeDp] wide in [stroke]. */
class Squircle(private val c: Context, var fill: Int, var stroke: Int = 0, private val strokeDp: Float = 2f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun draw(cv: Canvas) {
        val b = bounds
        val sw = if (stroke != 0) c.dp(strokeDp) else 0f
        squirclePath(b.left + sw / 2, b.top + sw / 2, b.width() - sw, b.height() - sw, path)
        if (fill != 0) { paint.style = Paint.Style.FILL; paint.color = fill; cv.drawPath(path, paint) }
        if (stroke != 0) { paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; paint.color = stroke; cv.drawPath(path, paint) }
    }
    override fun getOutline(o: android.graphics.Outline) {
        val b = bounds
        if (android.os.Build.VERSION.SDK_INT >= 30) o.setPath(squirclePath(b.left.toFloat(), b.top.toFloat(), b.width().toFloat(), b.height().toFloat()))
        else o.setRoundRect(b, b.width() * 0.3f)
    }
    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { paint.colorFilter = f }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Yeetlist's toast glow, in Ursa amber, round the + button: a soft ring of light that
 * thins and thickens round the shape, blurred past its edge and turned once every 15 s,
 * so the bright patches travel round it. Screen over the page in dark mode, Multiply
 * in light mode, as the pie's beam in UrsaPDF.
 */
class Glow(c: Context, private val sizeDp: Float) : View(c) {
    private val spread = c.dp(16f)          // Yeetlist: inset -16px
    private val blur = c.dp(32f)            // Yeetlist: blur 32px
    // How far the glow reaches past the button: Yeetlist's 16 + 2 × 32 = 80 dp, half again
    // (their call, 3 November 2026, with the inverse-square falloff): 120 dp.
    private val pad = (spread + blur * 2) * 1.5f
    private var mask: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val turn = Matrix()
    private var sweep: SweepGradient? = null
    private var angle = 0f
    private var last = 0L
    private var running = false

    init { setWillNotDraw(false) }

    override fun hasOverlappingRendering() = false

    /** Half the view's width beyond the button on each side. */
    fun reach(): Int = pad.toInt()

    // Screen and Multiply ignore alpha on Android's GPU path (UrsaPDF measured a 60% black
    // box drawing solid black), so every colour here is opaque and the 45% strength is
    // in the colours: Screen of black and Multiply of white leave the page as it is.
    private var lightMask: Bitmap? = null
    private val STRENGTH = 0.45f
    private val PULSE_S = 2f

    /**
     * The glow as light from the button's edge: full at the edge, then 1 / (1 + (d / core)²)
     * by the distance d out from it, rescaled to reach nothing at [pad], core a quarter of
     * that, as the pie's glow in UrsaPDF. Distance is measured on the squircle's own curve.
     */
    private fun build() {
        val s = context.dp(sizeDp)
        val full = (s + 2 * pad).toInt()
        val half = s / 2f
        val c = full / 2f
        val core = 0.25f
        val edge = 1f / (1f + (1f / core) * (1f / core))
        val dark = IntArray(full * full); val light = IntArray(full * full)
        for (y in 0 until full) for (x in 0 until full) {
            val nx = kotlin.math.abs(x + 0.5f - c) / half; val ny = kotlin.math.abs(y + 0.5f - c) / half
            val r = Math.pow(nx.toDouble().pow(4) + ny.toDouble().pow(4), 0.25).toFloat()
            val u = ((r - 1f) * half / pad).coerceIn(0f, 1f)
            val q = u / core
            // The tail eases to nothing, (1 - u²)², so no step shows where the glow ends.
            val tail = (1f - u * u).let { it * it }
            val k = (((1f / (1f + q * q) - edge) / (1f - edge)) * tail).coerceIn(0f, 1f)
            val g = (255 * k).toInt()
            dark[y * full + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            val w = 255 - g
            light[y * full + x] = (0xFF shl 24) or (w shl 16) or (w shl 8) or w
        }
        mask = Bitmap.createBitmap(dark, full, full, Bitmap.Config.ARGB_8888)       // white on black
        lightMask = Bitmap.createBitmap(light, full, full, Bitmap.Config.ARGB_8888) // black on white
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 255) + ((((b shr s) and 255) - ((a shr s) and 255)) * t)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun start() { running = true; last = 0L; postInvalidateOnAnimation() }
    fun stop() { running = false }

    override fun onDraw(cv: Canvas) {
        if (mask == null) build()
        val dark = mask ?: return
        val light = Theme.light
        val bm = if (light) lightMask!! else dark
        val now = System.nanoTime()
        if (last != 0L) angle = (angle + (now - last) / 1e9f / PULSE_S) % 1f
        last = now
        // A plain pulse: the whole glow swells and fades on a sine, from a third of its
        // strength to all of it, once every PULSE_S seconds.
        val k = STRENGTH * (1f / 3 + (2f / 3) * (0.5f - 0.5f * kotlin.math.cos(2 * Math.PI * angle).toFloat()))
        val on = if (light) mix(0xFFFFFFFF.toInt(), ACCENT, k) else mix(0xFF000000.toInt(), ACCENT, k)
        val sw = android.graphics.LinearGradient(0f, 0f, 1f, 0f, on, on, android.graphics.Shader.TileMode.CLAMP)
        val shape = android.graphics.BitmapShader(bm, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        // Dark: light only inside the shape (multiply by white-on-black). Light: white outside
        // the shape (screen with black-on-white).
        paint.shader = android.graphics.ComposeShader(shape, sw, if (light) PorterDuff.Mode.SCREEN else PorterDuff.Mode.MULTIPLY)
        paint.xfermode = PorterDuffXfermode(if (light) PorterDuff.Mode.MULTIPLY else PorterDuff.Mode.SCREEN)
        val x = (width - bm.width) / 2f; val y = (height - bm.height) / 2f
        cv.save()
        cv.translate(x, y)
        cv.drawRect(0f, 0f, bm.width.toFloat(), bm.height.toFloat(), paint)
        cv.restore()
        if (running) postInvalidateOnAnimation()
    }
}
