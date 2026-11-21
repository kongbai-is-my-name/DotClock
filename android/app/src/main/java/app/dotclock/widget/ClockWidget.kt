package app.dotclock.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import app.dotclock.DotClockApp
import app.dotclock.R
import app.dotclock.core.Appearance
import app.dotclock.core.ClockRenderer
import app.dotclock.core.ClockScene
import app.dotclock.data.ClockRepository
import app.dotclock.render.AndroidDotCanvas
import app.dotclock.ui.MainActivity
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 桌面小组件。
 *
 * 用和 App 相同的渲染器把卡片画成位图，再交给桌面显示：点阵、配色、表盘样式与 App 完全一致。
 * 小组件可以自由拉伸，尺寸不同显示的内容不同：小尺寸只有时间，越大依次加上标题行、今日进度、天气和世界时钟。
 * 系统不允许小组件每秒刷新，所以小组件不显示秒，靠精确闹钟在每个整分钟刷新一次（息屏时不唤醒设备，亮屏立即补上）。
 */
class ClockWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        DotClockApp.installPlatformHooks()
        ids.forEach { WidgetUpdater.update(context, manager, it) }
        WidgetUpdater.scheduleNextTick(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        // 用户拉伸了小组件：按新尺寸重新排版
        DotClockApp.installPlatformHooks()
        WidgetUpdater.update(context, manager, id)
    }

    override fun onEnabled(context: Context) = WidgetUpdater.scheduleNextTick(context)

    override fun onDisabled(context: Context) = WidgetUpdater.cancelTick(context)
}

/** 整分钟闹钟，以及开机、改时间、改时区、改语言等系统广播。 */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DotClockApp.installPlatformHooks()
        WidgetUpdater.updateAll(context)
    }
}

object WidgetUpdater {
    private const val TAG = "DotClockWidget"
    private const val ACTION_TICK = "app.dotclock.action.TICK"

    /** 每张位图最多这么多像素，避免超过桌面进程的 RemoteViews 内存上限 */
    private const val MAX_PIXELS = 1_400_000f

    fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ClockWidgetProvider::class.java))

    fun updateAll(context: Context) {
        val ids = ids(context)
        if (ids.isEmpty()) {
            cancelTick(context)
            return
        }
        val manager = AppWidgetManager.getInstance(context)
        ids.forEach { update(context, manager, it) }
        scheduleNextTick(context)
    }

    fun update(context: Context, manager: AppWidgetManager, id: Int) {
        try {
            manager.updateAppWidget(id, buildViews(context, manager.getAppWidgetOptions(id)))
        } catch (e: Exception) {
            Log.w(TAG, "刷新小组件 $id 失败", e)
        }
    }

    private fun buildViews(context: Context, options: Bundle): RemoteViews {
        val density = context.resources.displayMetrics.density
        // 竖屏时宽度取 minWidth、高度取 maxHeight；横屏反过来（AppWidgetManager 的约定）
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH)
        val portrait = views(context, minW, maxH, density)
        val landscape = if (minW == maxW && minH == maxH) portrait else views(context, maxW, minH, density)
        return RemoteViews(landscape, portrait)
    }

    private fun views(context: Context, widthDp: Int, heightDp: Int, density: Float): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_clock)
        views.setImageViewBitmap(R.id.widget_clock, render(context, widthDp.coerceAtLeast(40), heightDp.coerceAtLeast(40), density))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)
        return views
    }

    fun render(context: Context, widthDp: Int, heightDp: Int, density: Float): Bitmap {
        val repo = ClockRepository.get(context)
        val settings = repo.settings.value
        val dark = when (settings.appearance) {
            Appearance.DARK -> true
            Appearance.LIGHT -> false
            Appearance.AUTO -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        var scale = density
        val pixels = widthDp * heightDp * scale * scale
        if (pixels > MAX_PIXELS) scale *= sqrt(MAX_PIXELS / pixels)
        val w = (widthDp * scale).roundToInt()
        val h = (heightDp * scale).roundToInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scene = ClockScene.from(settings, repo.state.value, dark, repo.systemSeed, showSeconds = false)
        ClockRenderer.draw(AndroidDotCanvas(Canvas(bitmap)), scene, 0f, 0f, w.toFloat(), h.toFloat(), scale, minScale = 0.62f)
        return bitmap
    }

    private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1, Intent(context, TickReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 在下一个整分钟（按网络校准后的时间）刷新。RTC 闹钟不会唤醒设备，亮屏后立即补发。 */
    fun scheduleNextTick(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val repo = ClockRepository.get(context)
        val offset = repo.settings.value.let { s -> if (s.useNetworkTime && repo.state.value.timeSynced) repo.state.value.timeOffsetMillis else 0L }
        val now = System.currentTimeMillis() + offset
        val next = (now / 60_000 + 1) * 60_000 - offset + 50
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        try {
            if (canExact) alarm.setExact(AlarmManager.RTC, next, tickIntent(context))
            else alarm.setWindow(AlarmManager.RTC, next, 10_000, tickIntent(context))
        } catch (e: SecurityException) {
            alarm.setWindow(AlarmManager.RTC, next, 10_000, tickIntent(context))
        }
    }

    fun cancelTick(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(tickIntent(context))
    }
}
