package ro.bec.awox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Becul desenat ca o sferă din praf luminos: se rotește, respiră, pulsează mai repede
 * când lumina e mai puternică și se împrăștie sub deget.
 * Atingere scurtă = pornit/oprit; tras sus-jos = luminozitate.
 */
class DustSphereView(ctx: Context) : View(ctx) {
    var onToggle: (() -> Unit)? = null
    var onBrightness: ((Float) -> Unit)? = null

    var on = false
    /** 0.05 .. 1 */
    var bri = 0.8f

    private val d = resources.displayMetrics.density
    private val unit = d * 0.5f // o unitate din machetă

    private val target = floatArrayOf(255f, 214f, 160f)
    private val shown = floatArrayOf(255f, 214f, 160f)

    fun setLightColor(c: Int) {
        target[0] = Color.red(c).toFloat(); target[1] = Color.green(c).toFloat(); target[2] = Color.blue(c).toFloat()
    }

    // sfera
    private val sn = 1400
    private val sx = FloatArray(sn)
    private val sy = FloatArray(sn)
    private val sz = FloatArray(sn)
    private val sph = FloatArray(sn)
    private val ssz = FloatArray(sn)
    private val sdx = FloatArray(sn)
    private val sdy = FloatArray(sn)

    // praful din jur
    private val dn = 170
    private val da = FloatArray(dn)
    private val dr = FloatArray(dn)
    private val dsp = FloatArray(dn)
    private val dsz = FloatArray(dn)
    private val dph = FloatArray(dn)
    private val ddx = FloatArray(dn)
    private val ddy = FloatArray(dn)

    init {
        val ga = (Math.PI * (3 - sqrt(5.0))).toFloat()
        for (i in 0 until sn) {
            val y = 1 - (i + 0.5f) / sn * 2
            val r = sqrt(1 - y * y)
            val th = ga * i
            val shell = if (Random.nextFloat() < 0.82f) 1 - Random.nextFloat() * 0.04f else 0.55f + Random.nextFloat() * 0.4f
            sx[i] = cos(th) * r * shell; sy[i] = y * shell; sz[i] = sin(th) * r * shell
            sph[i] = Random.nextFloat() * 6.283f
            ssz[i] = 0.7f + Random.nextFloat() * 1.3f
        }
        for (i in 0 until dn) {
            val r = 1.2f + Random.nextFloat().pow(1.6f) * 1.2f
            da[i] = Random.nextFloat() * 6.283f
            dr[i] = r
            dsp[i] = (0.15f + Random.nextFloat() * 0.5f) * (if (Random.nextBoolean()) -1 else 1) / r
            dsz[i] = 0.6f + Random.nextFloat() * 1.6f
            dph[i] = Random.nextFloat() * 6.283f
        }
        contentDescription = "Bec. Atinge pentru pornit sau oprit, trage în sus sau în jos pentru luminozitate."
    }

    private val dot = Paint()
    private val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF3EEE6.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(10 * d, 0f, d, 0xFF16130F.toInt())
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xBFF3EEE6.toInt()
        textAlign = Paint.Align.CENTER
        setShadowLayer(8 * d, 0f, d, 0xFF16130F.toInt())
    }

    private var lvl = 0f
    private var phase = 0f
    private var ang = 0f
    private var lastNs = 0L
    private var touching = false
    private var touchX = 0f
    private var touchY = 0f
    private var touchAmt = 0f

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * min(1f, t)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (300 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else min(0.05f, (now - lastNs) / 1e9f)
        lastNs = now

        lvl = lerp(lvl, if (on) 1f else 0f, dt * 4)
        for (i in 0..2) shown[i] = lerp(shown[i], target[i], dt * 5)
        touchAmt = lerp(touchAmt, if (touching) 1f else 0f, dt * (if (touching) 8 else 3))

        val k = bri
        val rate = 0.6f + 1.6f * k * lvl
        phase += dt * rate
        ang += dt * 8f / 40f * (0.5f + 0.5f * lvl)
        val breath = sin(phase * 2.2f) * 0.5f + 0.5f
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.19f * (0.95f + 0.07f * breath)

        val cr = shown[0].toInt(); val cg = shown[1].toInt(); val cb = shown[2].toInt()
        val pr: Int; val pg: Int; val pb: Int
        if (lvl > 0.3f) {
            pr = min(255, cr + 50); pg = min(255, cg + 50); pb = min(255, cb + 50)
        } else {
            pr = 170; pg = 160; pb = 148
        }

        // aura
        if (lvl > 0.02f) {
            val a = (0.4f * k * lvl * (0.75f + 0.25f * breath) * 255).toInt().coerceIn(0, 255)
            glowPaint.shader = RadialGradient(
                cx, cy, radius * 2.1f,
                intArrayOf(Color.argb(a, cr, cg, cb), Color.argb(a, cr, cg, cb), Color.argb(0, cr, cg, cb)),
                floatArrayOf(0f, 0.1f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius * 2.1f, glowPaint)
        }

        // sfera
        val ca = cos(ang); val sa = sin(ang)
        val ct = cos(0.35f); val st = sin(0.35f)
        val reach = 85 * unit
        for (i in 0 until sn) {
            val x = sx[i] * ca + sz[i] * sa
            val z = -sx[i] * sa + sz[i] * ca
            val y = sy[i]
            val y2 = y * ct - z * st
            val z2 = y * st + z * ct
            val wave = 1 + 0.035f * sin(phase * 3 + y * 4 + sph[i] * 0.3f)
            var px = cx + x * radius * wave
            var py = cy + y2 * radius * wave
            if (touching && touchAmt > 0.01f) {
                val ex = px - touchX; val ey = py - touchY
                val dist = hypot(ex, ey)
                if (dist < reach && dist > 0) {
                    val f = (reach - dist) / reach * touchAmt * 20 * unit
                    sdx[i] += ex / dist * f * dt * 6
                    sdy[i] += ey / dist * f * dt * 6
                }
            }
            sdx[i] *= 0.9f; sdy[i] *= 0.9f
            px += sdx[i]; py += sdy[i]
            val depth = (z2 + 1) / 2
            val tw = 0.6f + 0.4f * sin(phase * 2 + sph[i])
            var al = (0.12f + 0.88f * lvl * (0.35f + 0.65f * k)) * (0.18f + 0.82f * depth) * tw
            if (lvl <= 0.05f) al *= 0.5f
            dot.color = Color.argb((al * 255).toInt().coerceIn(0, 255), pr, pg, pb)
            val s = ssz[i] * (0.6f + 0.7f * depth) * unit * 1.6f
            canvas.drawRect(px - s / 2, py - s / 2, px + s / 2, py + s / 2, dot)
        }

        // praful din jur
        val dreach = 90 * unit
        for (i in 0 until dn) {
            da[i] += dsp[i] * dt * rate * 0.9f
            dph[i] += dt * (1 + dsz[i])
            val wob = sin(dph[i]) * 0.06f
            var x = cx + cos(da[i]) * radius * (dr[i] + wob)
            var y = cy + sin(da[i]) * radius * (dr[i] + wob) * 0.92f
            if (touching) {
                val ex = x - touchX; val ey = y - touchY
                val dist = hypot(ex, ey)
                if (dist < dreach && dist > 0) {
                    val f = (dreach - dist) / dreach * touchAmt * 60 * unit
                    ddx[i] += ex / dist * f * dt * 4
                    ddy[i] += ey / dist * f * dt * 4
                }
            }
            ddx[i] *= 0.94f; ddy[i] *= 0.94f
            x += ddx[i]; y += ddy[i]
            val tw = 0.5f + 0.5f * sin(dph[i] * 1.7f)
            val fade = max(0f, 1 - (dr[i] - 1.2f) / 1.25f)
            var al = (0.12f + 0.8f * lvl * k) * fade * (0.35f + 0.65f * tw)
            if (lvl <= 0.05f) al *= 0.45f
            dustPaint.color = Color.argb((al * 255).toInt().coerceIn(0, 255), pr, pg, pb)
            canvas.drawCircle(x, y, dsz[i] * unit * 1.4f, dustPaint)
        }

        // text
        textPaint.textSize = 32 * d
        hintPaint.textSize = 11 * d
        val label = if (on) "${(bri * 100).toInt()}%" else "Stins"
        canvas.drawText(label, cx, cy + 8 * d, textPaint)
        canvas.drawText(if (on) "trage pentru luminozitate" else "atinge ca să aprinzi", cx, cy + 26 * d, hintPaint)

        if (isShown) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastNs = 0L
        postInvalidateOnAnimation()
    }

    private var downY = 0f
    private var downBri = 0f
    private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downY = e.y; downBri = bri; moved = false
                touching = true; touchX = e.x; touchY = e.y
                postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = e.x; touchY = e.y
                val dy = downY - e.y
                if (abs(dy) > 6 * d) {
                    moved = true
                    bri = (downBri + dy / (160 * d)).coerceIn(0.05f, 1f)
                    onBrightness?.invoke(bri)
                }
            }
            MotionEvent.ACTION_UP -> {
                touching = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!moved) {
                    performClick()
                    onToggle?.invoke()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                touching = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
