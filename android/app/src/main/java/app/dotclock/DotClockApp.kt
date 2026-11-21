package app.dotclock

import android.app.Application
import android.icu.text.TimeZoneNames
import android.icu.util.ULocale
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.dotclock.core.ClockFormat
import app.dotclock.core.DotFont
import app.dotclock.data.ClockRepository
import app.dotclock.render.AndroidDotCanvas
import app.dotclock.widget.WidgetUpdater
import java.util.concurrent.TimeUnit

class DotClockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installPlatformHooks()
        scheduleSync(this)
        WidgetUpdater.updateAll(this)
    }

    companion object {
        private var installed = false

        /** 把平台相关的能力交给 core：中文点阵栅格化、时区的中文城市名。 */
        fun installPlatformHooks() {
            if (installed) return
            installed = true
            DotFont.rasterizer = AndroidDotCanvas.rasterizer
            val names = TimeZoneNames.getInstance(ULocale.SIMPLIFIED_CHINESE)
            ClockFormat.exemplarCityName = { id -> names.getExemplarLocationName(id) }
        }

        /** 每 30 分钟在有网时后台同步一次时间、位置和天气（对应 macOS 版每 15 分钟的定时器）。 */
        fun scheduleSync(app: Application) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(app).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class SyncWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        DotClockApp.installPlatformHooks()
        ClockRepository.get(applicationContext).sync()
        return Result.success()
    }
}
