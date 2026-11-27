package com.infinifolder.viewer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.widget.RemoteViews
import com.infinifolder.theme.ThemeManager
import com.infinifolder.viewer.audio.R
import kotlin.math.min
import kotlin.math.roundToInt

/** One-row Winamp windowshade widget. Its visible player stays at the top of the host box. */
class WinampMiniPlayerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        widgetIds.forEach { updateWidget(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        updateWidget(context, manager, widgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshWidgets(context)
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val options = manager.getAppWidgetOptions(widgetId)
        val widthCandidates = listOf(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
        ).filter { it > 0 }
        val heightCandidates = listOf(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT),
        ).filter { it > 0 }
        val availableWidthDp = widthCandidates.maxOrNull() ?: DEFAULT_WIDTH_DP
        val availableHeightDp = heightCandidates.maxOrNull() ?: DEFAULT_HEIGHT_DP
        val aspectWidthLimitDp = (availableHeightDp * NATIVE_WIDTH.toFloat() / NATIVE_HEIGHT).toInt()
        val visibleWidthDp = min(availableWidthDp, aspectWidthLimitDp).coerceAtLeast(1)
        val visibleHeightDp = (visibleWidthDp * NATIVE_HEIGHT.toFloat() / NATIVE_WIDTH)
            .roundToInt()
            .coerceAtLeast(1)
        val density = context.resources.displayMetrics.density
        val bitmapWidth = (visibleWidthDp * density).roundToInt().coerceAtLeast(1)
        val bitmapHeight = (bitmapWidth * NATIVE_HEIGHT.toFloat() / NATIVE_WIDTH).roundToInt().coerceAtLeast(1)

        val appContext = context.applicationContext
        val theme = ThemeManager.getAudioPlayerTheme(appContext)
        val service = AudioPlayerService.currentService
        val track = service?.currentTrack
        val trackTitle = if (track == null) {
            "Infinimedia"
        } else if (!track.artist.isNullOrBlank() && track.artist != "Unknown Artist") {
            track.artist + " - " + track.title
        } else {
            track.title
        }
        val title = (if (service?.isPlaying == true) "> " else "|| ") + trackTitle

        val views = RemoteViews(context.packageName, R.layout.winamp_mini_player_widget)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setViewLayoutWidth(
                R.id.widget_player_frame,
                visibleWidthDp.toFloat(),
                TypedValue.COMPLEX_UNIT_DIP,
            )
            views.setViewLayoutHeight(
                R.id.widget_player_frame,
                visibleHeightDp.toFloat(),
                TypedValue.COMPLEX_UNIT_DIP,
            )
        }
        views.setImageViewBitmap(
            R.id.widget_player_skin,
            renderWindowshade(appContext, bitmapWidth, bitmapHeight),
        )
        views.setTextViewText(R.id.widget_open_title, title)
        val nativeScaleX = bitmapWidth.toFloat() / NATIVE_WIDTH
        val nativeScaleY = bitmapHeight.toFloat() / NATIVE_HEIGHT
        views.setTextViewTextSize(
            R.id.widget_open_title,
            TypedValue.COMPLEX_UNIT_PX,
            8.5f * nativeScaleY,
        )
        val titleLeftInsetPx = (TITLE_TEXT_LEFT_INSET * nativeScaleX).roundToInt()
        // Keep the full 14px strip available to font ascent/descent. Vertical padding here
        // clips descenders such as the g in “Dogs” and varies with output rounding.
        views.setViewPadding(R.id.widget_open_title, titleLeftInsetPx, 0, 0, 0)
        views.setTextColor(R.id.widget_open_title, theme.textPrimary)
        val openPlayer = playerPendingIntent(context, widgetId)
        views.setOnClickPendingIntent(R.id.widget_root, openPlayer)
        views.setOnClickPendingIntent(R.id.widget_open_logo, openPlayer)
        views.setOnClickPendingIntent(R.id.widget_title_start_gap, openPlayer)
        views.setOnClickPendingIntent(R.id.widget_title_end_gap, openPlayer)
        views.setOnClickPendingIntent(R.id.widget_open_title, openPlayer)
        views.setOnClickPendingIntent(R.id.widget_eject, openPlayer)
        views.setOnClickPendingIntent(R.id.widget_open_gap, openPlayer)
        views.setOnClickPendingIntent(R.id.widget_open_window_buttons, openPlayer)
        views.setOnClickPendingIntent(R.id.widget_previous, servicePendingIntent(context, widgetId, AudioPlayerService.ACTION_PREV))
        views.setOnClickPendingIntent(R.id.widget_play, servicePendingIntent(context, widgetId, AudioPlayerService.ACTION_TOGGLE))
        views.setOnClickPendingIntent(R.id.widget_pause, servicePendingIntent(context, widgetId, AudioPlayerService.ACTION_TOGGLE))
        views.setOnClickPendingIntent(R.id.widget_stop, servicePendingIntent(context, widgetId, AudioPlayerService.ACTION_STOP))
        views.setOnClickPendingIntent(R.id.widget_next, servicePendingIntent(context, widgetId, AudioPlayerService.ACTION_NEXT))
        manager.updateAppWidget(widgetId, views)
    }

    private fun renderWindowshade(context: Context, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.density = context.resources.displayMetrics.densityDpi
        val canvas = Canvas(bitmap)
        canvas.scale(width.toFloat() / NATIVE_WIDTH, height.toFloat() / NATIVE_HEIGHT)

        ThemeManager.getAudioPlayerTheme(context)
        val titleBar = ThemeManager.skinBitmap("titlebar.bmp")
        val bitmapPaint = Paint().apply { isFilterBitmap = false }
        if (titleBar != null && titleBar.width >= 302 && titleBar.height >= 43) {
            // Winamp's skin layout specifies the active windowshade strip at
            // (27, 29), exactly 275x14. The inactive strip starts at y=42.
            canvas.drawBitmap(titleBar, Rect(27, 29, 302, 43), Rect(0, 0, 275, 14), bitmapPaint)
        } else if (titleBar != null && titleBar.width >= 302 && titleBar.height >= 14) {
            // Older skins without a windowshade row can still provide their main title strip.
            canvas.drawBitmap(titleBar, Rect(27, 0, 302, 14), Rect(0, 0, 275, 14), bitmapPaint)
            canvas.drawBitmap(titleBar, Rect(9, 0, 18, 9), Rect(254, 3, 263, 12), bitmapPaint)
            canvas.drawBitmap(titleBar, Rect(18, 0, 27, 9), Rect(264, 3, 273, 12), bitmapPaint)
        } else {
            val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, NATIVE_HEIGHT.toFloat(),
                    intArrayOf(Color.rgb(55, 63, 76), Color.rgb(24, 30, 39)),
                    null, android.graphics.Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, NATIVE_WIDTH.toFloat(), NATIVE_HEIGHT.toFloat(), background)
        }

        return bitmap
    }

    private fun servicePendingIntent(context: Context, widgetId: Int, action: String): PendingIntent {
        val intent = Intent(context, AudioPlayerService::class.java)
            .setAction(action)
            .setData(Uri.parse("infinimedia-widget://" + widgetId + "/" + action.substringAfterLast('.')))
        return PendingIntent.getService(
            context,
            widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun playerPendingIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, AudioPlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "com.infinifolder.viewer.action.REFRESH_MINI_PLAYER_WIDGET"
        private const val NATIVE_WIDTH = 275
        private const val NATIVE_HEIGHT = 14
        private const val TITLE_TEXT_LEFT_INSET = 5
        private const val DEFAULT_WIDTH_DP = 210
        private const val DEFAULT_HEIGHT_DP = 48

        fun requestRefresh(context: Context) {
            context.sendBroadcast(
                Intent(context, WinampMiniPlayerWidgetProvider::class.java)
                    .setAction(ACTION_REFRESH),
            )
        }

        private fun refreshWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, WinampMiniPlayerWidgetProvider::class.java)
            val widgetIds = manager.getAppWidgetIds(provider)
            widgetIds.forEach { widgetId ->
                val receiver = WinampMiniPlayerWidgetProvider()
                receiver.updateWidget(context, manager, widgetId)
            }
        }
    }
}
