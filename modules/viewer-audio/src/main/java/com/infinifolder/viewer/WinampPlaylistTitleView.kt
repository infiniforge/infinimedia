package com.infinifolder.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.infinifolder.theme.ThemeManager

/**
 * Authentic Winamp Playlist Window Titlebar View.
 * Native dimension is 275 x 20 pixels, dynamically scaled to match the main player's width.
 * Employs two-stage scaling (pixel-scaling up to maximum integer multiple, then smooth-scaling to target width).
 */
class WinampPlaylistTitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val NATIVE_WIDTH = 275
        const val NATIVE_HEIGHT = 20
    }

    var onShadeToggle: (() -> Unit)? = null
    var onMenuRequested: (() -> Unit)? = null
    private var downX = 0f
    private var actionForClick: (() -> Unit)? = null

    private var pixelScaledBitmap: Bitmap? = null
    private var pixelScaledCanvas: Canvas? = null

    private val nearestPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }

    private val smoothPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
        isDither = true
    }

    private val srcRect = Rect()
    private val dstRect = Rect()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Playlist title bar menu"
    }

    override fun performClick(): Boolean {
        super.performClick()
        (actionForClick ?: onMenuRequested)?.invoke()
        actionForClick = null
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                actionForClick = null
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                isPressed = kotlin.math.abs(event.x - downX) <= slop &&
                    event.y >= 0f && event.y < height
                return true
            }
            MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                val isTap = kotlin.math.abs(event.x - downX) <= slop &&
                    event.y >= 0f && event.y < height
                isPressed = false
                if (isTap) {
                    val nativeX = event.x * NATIVE_WIDTH / width.coerceAtLeast(1)
                    actionForClick = if (nativeX >= 254f) onShadeToggle else onMenuRequested
                    performClick()
                }
                actionForClick = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                actionForClick = null
                return true
            }
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val screenW = resources.displayMetrics.widthPixels
        val finalW = if (widthSize > 0) widthSize else screenW
        val calculatedHeight = ((NATIVE_HEIGHT.toFloat() / NATIVE_WIDTH.toFloat()) * finalW).toInt()
        setMeasuredDimension(finalW, calculatedHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        smoothPaint.isFilterBitmap = ThemeManager.smoothSkinScaling
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val titleBmp = ThemeManager.skinBitmap("playlist-titlebar.bmp")
        if (titleBmp != null) {
            val k = w / NATIVE_WIDTH
            if (k >= 2) {
                val pixelW = NATIVE_WIDTH * k
                val pixelH = NATIVE_HEIGHT * k

                if (pixelScaledBitmap == null || pixelScaledBitmap?.width != pixelW || pixelScaledBitmap?.height != pixelH) {
                    pixelScaledBitmap?.recycle()
                    val newBmp = Bitmap.createBitmap(pixelW, pixelH, Bitmap.Config.ARGB_8888)
                    pixelScaledBitmap = newBmp
                    pixelScaledCanvas = Canvas(newBmp)
                }

                srcRect.set(0, 0, NATIVE_WIDTH, NATIVE_HEIGHT)
                dstRect.set(0, 0, pixelW, pixelH)
                pixelScaledCanvas?.drawBitmap(titleBmp, srcRect, dstRect, nearestPaint)

                dstRect.set(0, 0, w, h)
                canvas.drawBitmap(pixelScaledBitmap!!, Rect(0, 0, pixelW, pixelH), dstRect, smoothPaint)
            } else {
                srcRect.set(0, 0, NATIVE_WIDTH, NATIVE_HEIGHT)
                dstRect.set(0, 0, w, h)
                canvas.drawBitmap(titleBmp, srcRect, dstRect, smoothPaint)
            }
        } else {
            // Clean fallback titlebar
            val theme = ThemeManager.getAudioPlayerTheme(context)
            canvas.drawColor(theme.toolbarBackground)
        }
    }
}
