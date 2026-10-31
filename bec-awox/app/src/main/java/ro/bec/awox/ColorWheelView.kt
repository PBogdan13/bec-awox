package ro.bec.awox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Roată de culori: unghiul alege nuanța, distanța față de centru alege saturația. */
class ColorWheelView(ctx: Context, private val onPick: (Int) -> Unit) : View(ctx) {
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * resources.displayMetrics.density
        color = Color.WHITE
        setShadowLayer(6f, 0f, 1f, 0x66000000)
    }
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var markerX = Float.NaN
    private var markerY = Float.NaN

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = min(w, (280 * resources.displayMetrics.density).toInt())
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - 10 * resources.displayMetrics.density
        huePaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED),
            null
        )
        satPaint.shader = RadialGradient(cx, cy, radius, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, radius, huePaint)
        canvas.drawCircle(cx, cy, radius, satPaint)
        if (!markerX.isNaN()) canvas.drawCircle(markerX, markerY, 12 * resources.displayMetrics.density, ring)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val dx = e.x - cx
                val dy = e.y - cy
                val sat = min(1f, hypot(dx, dy) / radius)
                val angle = atan2(dy, dx)
                val hue = ((Math.toDegrees(angle.toDouble()) + 360) % 360).toFloat()
                markerX = cx + cos(angle) * sat * radius
                markerY = cy + sin(angle) * sat * radius
                invalidate()
                onPick(Color.HSVToColor(floatArrayOf(hue, sat, 1f)))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
