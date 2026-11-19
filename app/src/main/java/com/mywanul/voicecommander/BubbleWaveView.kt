package com.mywanul.voicecommander

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * ঢেউ-খেলা বাবল (v1.4): স্ক্রিনের উপরে ভেসে থাকা গোল বাবল, যার চারপাশে
 * ঢেউয়ের মতো বৃত্ত ছড়িয়ে পড়ে — যেমন জুম/মিটে স্ক্রিন শেয়ার করলে
 * ভাসমান বাবল দেখা যায়। কোনো ছবি বা ড্রয়েবল লাগে না, সব Canvas-এ আঁকা।
 *
 * অবস্থা অনুযায়ী রঙ আর ঢেউয়ের গতি বদলায়:
 *  - IDLE: নীল, ধীর হালকা ঢেউ — অপেক্ষা করছে
 *  - LISTENING: সবুজ, দ্রুত ঢেউ — আপনার কথা শুনছে
 *  - SPEAKING: কমলা, দ্রুত ঢেউ — উত্তর বলছে
 *  - PROCESSING: বেগুনি — ভাবছে / উত্তর তৈরি করছে
 */
class BubbleWaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode(val color: Int, val cycleMs: Long, val ringCount: Int) {
        IDLE(0xFF2196F3.toInt(), 2600L, 1),
        LISTENING(0xFF43A047.toInt(), 1100L, 3),
        SPEAKING(0xFFFB8C00.toInt(), 750L, 3),
        PROCESSING(0xFF8E24AA.toInt(), 1500L, 2)
    }

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            restartAnimator()
        }

    private val density = resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val micFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val micStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // 0..1 — one full wave cycle. Rings are spread across the cycle
    // (ring i starts at phase i/ringCount) so waves keep coming.
    private var phase = 0f
    private var animator: ValueAnimator? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Fixed 96dp square — big enough for the core plus the
        // outermost ripple ring (rings reach ~48dp from the centre).
        val size = (96f * density).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimator()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun startAnimator() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = mode.cycleMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun restartAnimator() {
        if (isAttachedToWindow) startAnimator()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val coreR = size * 0.30f
        val maxRingR = size * 0.485f

        // Expanding ripple rings, fading as they grow.
        ringPaint.color = mode.color
        for (i in 0 until mode.ringCount) {
            val rp = (phase + i.toFloat() / mode.ringCount) % 1f
            val radius = coreR + rp * (maxRingR - coreR)
            ringPaint.alpha = ((1f - rp) * 180f).toInt().coerceIn(0, 255)
            ringPaint.strokeWidth = (3f * density) * (1f - rp * 0.4f)
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }

        // Soft glow, then the core — gently breathing (1.00–1.05x).
        val pulse = 1f + 0.05f * sin(phase * 2f * PI.toFloat())
        glowPaint.color = mode.color
        glowPaint.alpha = 35
        canvas.drawCircle(cx, cy, coreR * 1.28f, glowPaint)
        corePaint.color = mode.color
        canvas.drawCircle(cx, cy, coreR * pulse, corePaint)

        drawMic(canvas, cx, cy, coreR)
    }

    /** A simple white microphone: capsule head, U-arc, stem and base. */
    private fun drawMic(canvas: Canvas, cx: Float, cy: Float, coreR: Float) {
        val halfW = coreR * 0.22f
        val capsule = RectF(
            cx - halfW, cy - coreR * 0.55f,
            cx + halfW, cy + coreR * 0.10f
        )
        canvas.drawRoundRect(capsule, halfW, halfW, micFillPaint)
        micStrokePaint.strokeWidth = coreR * 0.10f
        val arc = RectF(
            cx - coreR * 0.34f, cy - coreR * 0.10f,
            cx + coreR * 0.34f, cy + coreR * 0.62f
        )
        canvas.drawArc(arc, 0f, 180f, false, micStrokePaint)
        canvas.drawLine(cx, cy + coreR * 0.62f, cx, cy + coreR * 0.78f, micStrokePaint)
        canvas.drawLine(
            cx - coreR * 0.22f, cy + coreR * 0.78f,
            cx + coreR * 0.22f, cy + coreR * 0.78f, micStrokePaint
        )
    }
}

/**
 * One shared bubble state for the whole app process: the listening
 * service and the speech helper report what the assistant is doing,
 * and the floating bubble (another service, same process) shows it
 * as colour + wave speed. Listeners are always called on the main
 * thread, so they may touch views directly.
 */
object BubbleState {
    @Volatile
    var mode: BubbleWaveView.Mode = BubbleWaveView.Mode.IDLE
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(BubbleWaveView.Mode) -> Unit>()

    @Synchronized
    fun set(mode: BubbleWaveView.Mode) {
        if (this.mode == mode) return
        this.mode = mode
        val snapshot = listeners.toList()
        mainHandler.post {
            snapshot.forEach { listener ->
                try {
                    listener(mode)
                } catch (_: Exception) {
                }
            }
        }
    }

    @Synchronized
    fun addListener(listener: (BubbleWaveView.Mode) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: (BubbleWaveView.Mode) -> Unit) {
        listeners.remove(listener)
    }
}
