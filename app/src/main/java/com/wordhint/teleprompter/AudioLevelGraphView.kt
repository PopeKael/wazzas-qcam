/*
 * Modified for Wazza's QCam by Warren Gerdes, 2026. See LICENSE and NOTICE.
 */

package com.wordhint.teleprompter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Displays only timestamped amplitude readings supplied by CameraX while recording. */
class AudioLevelGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private data class Sample(val at: Long, val amplitude: Float)

    private val samples = ArrayDeque<Sample>()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(9f)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 255, 255, 255)
        strokeWidth = dp(1f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private var stateLabel = context.getString(R.string.mic_ready)
    private var unavailable = false

    init {
        setBackgroundResource(R.drawable.camera_tool_button)
        contentDescription = stateLabel
    }

    fun addSample(at: Long, amplitude: Float) {
        unavailable = false
        stateLabel = context.getString(R.string.mic_graph_label)
        samples.addLast(Sample(at, amplitude.coerceIn(0f, 1f)))
        while (samples.isNotEmpty() && at - samples.first().at > WINDOW_MS) samples.removeFirst()
        contentDescription = context.getString(
            if (amplitude >= SPEECH_THRESHOLD) R.string.mic_graph_speech_accessibility
            else R.string.mic_graph_silence_accessibility
        )
        invalidate()
    }

    fun setReady(label: String) {
        samples.clear()
        unavailable = false
        stateLabel = label
        contentDescription = label.replace('\n', ' ')
        invalidate()
    }

    fun setUnavailable(label: String) {
        samples.clear()
        unavailable = true
        stateLabel = label
        contentDescription = label.replace('\n', ' ')
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + dp(5f)
        val right = width - paddingRight - dp(5f)
        val top = paddingTop + dp(5f)
        val labelBaseline = top + labelPaint.textSize
        canvas.drawText(stateLabel.replace('\n', ' '), left, labelBaseline, labelPaint)
        if (samples.isEmpty() || unavailable) return

        val graphTop = labelBaseline + dp(3f)
        val graphBottom = height - paddingBottom - dp(5f)
        if (graphBottom <= graphTop) return
        val mid = (graphTop + graphBottom) / 2f
        canvas.drawLine(left, mid, right, mid, gridPaint)
        val now = samples.last().at
        path.reset()
        var first = true
        for (sample in samples) {
            val x = (right - ((now - sample.at).toFloat() / WINDOW_MS) * (right - left)).coerceIn(left, right)
            val amplitude = if (sample.amplitude < SPEECH_THRESHOLD) 0f
                else (sample.amplitude * AMPLITUDE_GAIN).coerceAtMost(1f)
            val y = mid - amplitude * (graphBottom - graphTop) * 0.46f
            if (first) {
                path.moveTo(x, y)
                first = false
            } else path.lineTo(x, y)
        }
        val last = samples.last().amplitude
        linePaint.color = if (last < SPEECH_THRESHOLD) Color.rgb(144, 160, 174) else Color.rgb(74, 222, 128)
        canvas.drawPath(path, linePaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minWidth = dp(84f).toInt()
        val minHeight = dp(44f).toInt()
        setMeasuredDimension(
            resolveSize(max(minWidth, suggestedMinimumWidth), widthMeasureSpec),
            resolveSize(max(minHeight, suggestedMinimumHeight), heightMeasureSpec)
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val WINDOW_MS = 10_000L
        private const val SPEECH_THRESHOLD = 0.02f
        private const val AMPLITUDE_GAIN = 10f
    }
}
