/*
 * Modified for Wazza's QCam by Warren Gerdes, 2026. See LICENSE and NOTICE.
 */

package com.wordhint.teleprompter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import kotlin.math.min

/** Contains the camera image at the intended recording ratio and draws a matching no-camera guide. */
class CameraPreviewStage @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val frame = RectF()
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(38, 48, 59)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(137, 146, 159)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 112, 126)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(11f)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(28, 35, 43)
        style = Paint.Style.FILL
    }
    private var cameraAvailable = false
    private var contentWidth = 0
    private var contentHeight = 0

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.rgb(14, 17, 21))
        contentDescription = context.getString(R.string.preview_unavailable)
    }

    fun setCameraAvailable(available: Boolean) {
        cameraAvailable = available
        findViewById<PreviewView>(R.id.cameraPreview)?.alpha = if (available) 1f else 0f
        contentDescription = context.getString(
            if (available) R.string.camera_preview_accessibility else R.string.preview_unavailable
        )
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val availableWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val aspect = if (isLandscape) 16f / 9f else 9f / 16f
        contentWidth = min(availableWidth, (availableHeight * aspect).toInt())
        contentHeight = min(availableHeight, (availableWidth / aspect).toInt())

        findViewById<PreviewView>(R.id.cameraPreview)?.measure(
            MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        val x = paddingLeft + (width - paddingLeft - paddingRight - contentWidth) / 2
        val y = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            height - paddingBottom - contentHeight
        } else {
            paddingTop + (height - paddingTop - paddingBottom - contentHeight) / 2
        }
        frame.set(x.toFloat(), y.toFloat(), (x + contentWidth).toFloat(), (y + contentHeight).toFloat())
        findViewById<PreviewView>(R.id.cameraPreview)?.layout(x, y, x + contentWidth, y + contentHeight)

        val recordControl = findViewById<View>(R.id.recordButton) ?: return
        val params = recordControl.layoutParams as? LayoutParams ?: return
        if (params.gravity and Gravity.BOTTOM == Gravity.BOTTOM) {
            val recordLeft = (width - recordControl.measuredWidth) / 2
            val recordBottom = (frame.bottom.toInt() - params.bottomMargin).coerceAtMost(height - paddingBottom)
            recordControl.layout(
                recordLeft,
                recordBottom - recordControl.measuredHeight,
                recordLeft + recordControl.measuredWidth,
                recordBottom
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cameraAvailable || contentWidth <= 0 || contentHeight <= 0) return

        canvas.drawRoundRect(frame, dp(4f), dp(4f), framePaint)
        canvas.drawRoundRect(frame, dp(4f), dp(4f), borderPaint)
        canvas.drawLine(frame.left, frame.top, frame.right, frame.bottom, crossPaint)
        canvas.drawLine(frame.right, frame.top, frame.left, frame.bottom, crossPaint)

        val label = context.getString(R.string.preview_unavailable)
        val metrics = labelPaint.fontMetrics
        val labelBaseline = frame.centerY() - (metrics.ascent + metrics.descent) / 2f
        val labelWidth = labelPaint.measureText(label)
        val labelRect = RectF(
            frame.centerX() - labelWidth / 2f - dp(7f),
            labelBaseline + metrics.ascent - dp(4f),
            frame.centerX() + labelWidth / 2f + dp(7f),
            labelBaseline + metrics.descent + dp(4f)
        )
        canvas.drawRoundRect(labelRect, dp(4f), dp(4f), labelBackgroundPaint)
        canvas.drawText(label, frame.centerX(), labelBaseline, labelPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
