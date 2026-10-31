package ro.bec.awox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/** Nuanța albului ca o cometă pe o bandă de praf. Lasă în urmă o coadă în culoarea locului. */
class CometSliderView(ctx: Context, private val onChange: (Float) -> Unit) : View(ctx) {

    companion object {
        private fun mixc(a: IntArray, b: IntArray, t: Float) =
            IntArray(3) { (a[it] + (b[it] - a[it]) * t).toInt() }

        /** 0 = cald, 100 = rece */
        fun tempColor(v: Float): Int {
            val t = (v / 100f).coerceIn(0f, 1f)
            val w = intArrayOf(255, 150, 50)
            val m = intArrayOf(255, 243, 223)
            val c = intArrayOf(160, 200, 255)
            val r = if (t < 0.5f) mixc(w, m, t * 2) else mixc(m, c, (t - 0.5f) * 2)
            return Color.rgb(r[0], r[1], r[2])
        }
    }

    /** 0..100 */
    var value = 45f
        private set

    fun setValue(v: Float) {
        value = v.coerceIn(0f, 100f)
    }

    private val d = resources.displayMetrics.density
    private val unit = d * 0.5f
    private val band = 22 * unit
    private val padX get() = 34 * unit

    private val rn = 5 * (60 + 22 * 14)
    private val ru = FloatArray(rn)
    private val roff = FloatArray(rn)
    private val rph = FloatArray(rn)
    private val rsz = FloatArray(rn)
    private val railColor = IntArray(rn)

    private class Bit(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val max: Float, val sz: Float, val color: Int
    )

    private val trail = ArrayList<Bit>()

    init {
        for (i in 0 until rn) {
            ru[i] = Random.nextFloat()
            roff[i] = (Random.nextFloat() + Random.nextFloat() + Random.nextFloat() - 1.5f) / 1.5f * band
            rph[i] = Random.nextFloat() * 6.283f
            rsz[i] = 0.7f + Random.nextFloat() * 1.4f
            railColor[i] = tempColor(ru[i] * 100)
        }
        contentDescription = "Nuanța luminii albe, de la cald la rece"
        isFocusable = true
    }

    private val dot = Paint()
    private val headGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headCore = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private var headX = -1f
    private var prevX = -1f
    private var lastDir = 1f
    private var idleT = 0f
    private var phase = 0f
    private var lastNs = 0L

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (90 * d).toInt())
    }

    private fun xFor(v: Float) = padX + (width - 2 * padX) * v / 100f
    private fun vFor(x: Float) = ((x - padX) / (width - 2 * padX) * 100f).coerceIn(0f, 100f)

    private fun spawn(x: Float, n: Int, dir: Float, speed: Float) {
        val cy = height / 2f
        val col = tempColor(vFor(x))
        repeat(n) {
            trail.add(
                Bit(
                    x + (Random.nextFloat() - 0.5f) * 6 * unit,
                    cy + (Random.nextFloat() - 0.5f) * band * 1.4f,
                    (-dir * (20 + Random.nextFloat() * 40) * (0.4f + speed) + (Random.nextFloat() - 0.5f) * 20) * unit,
                    (Random.nextFloat() - 0.5f) * (20 + speed * 30) * unit,
                    0f, (0.25f + Random.nextFloat() * 0.6f) * 1.2f, 1f + Random.nextFloat() * 2.4f, col
                )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else min(0.05f, (now - lastNs) / 1e9f)
        lastNs = now
        phase += dt
        val cy = height / 2f
        if (headX < 0) {
            headX = xFor(value); prevX = headX
        }

        // banda de praf
        for (i in 0 until rn) {
            val x = padX + (width - 2 * padX) * ru[i]
            val near = max(0f, 1 - abs(x - headX) / (90 * unit))
            val y = cy + roff[i] + sin(phase * 1.5f + rph[i]) * 1.5f * unit - near * sign(roff[i]) * 4 * unit
            val a = 0.2f * 0.78f * (0.6f + 0.4f * sin(phase * 2 + rph[i])) + near * 0.3f
            val c = railColor[i]
            dot.color = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
            val s = rsz[i] * unit * 1.5f
            canvas.drawRect(x - s / 2, y - s / 2, x + s / 2, y + s / 2, dot)
        }

        // capul urmărește valoarea, iar mișcarea lasă coadă
        headX += (xFor(value) - headX) * min(1f, dt * 18)
        val moved = headX - prevX
        prevX = headX
        if (abs(moved) > 0.3f * unit) {
            lastDir = sign(moved)
            val sp = min(3f, abs(moved / unit) / 6)
            spawn(headX, ceil(abs(moved / unit) / 8 * 5).toInt(), lastDir, sp)
        } else {
            idleT += dt
            if (idleT > 0.07f) {
                idleT = 0f; spawn(headX, 1, lastDir, 0.05f)
            }
        }

        val it = trail.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life += dt
            if (p.life > p.max) {
                it.remove(); continue
            }
            p.x += p.vx * dt; p.y += p.vy * dt
            p.vx *= 0.94f; p.vy *= 0.94f
            val k = 1 - p.life / p.max
            dot.color = Color.argb((k * 0.9f * 255).toInt(), Color.red(p.color), Color.green(p.color), Color.blue(p.color))
            val s = p.sz * (0.4f + 0.6f * k) * unit * 1.5f
            canvas.drawRect(p.x - s / 2, p.y - s / 2, p.x + s / 2, p.y + s / 2, dot)
        }

        val hc = tempColor(value)
        val hr = 12 * unit
        headGlow.shader = RadialGradient(
            headX, cy, hr * 3.2f,
            intArrayOf(Color.WHITE, Color.argb(242, Color.red(hc), Color.green(hc), Color.blue(hc)), Color.argb(0, Color.red(hc), Color.green(hc), Color.blue(hc))),
            floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(headX, cy, hr * 3.2f, headGlow)
        canvas.drawCircle(headX, cy, hr * 0.45f, headCore)

        if (isShown) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastNs = 0L
        postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                value = vFor(e.x)
                onChange(value)
                postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
