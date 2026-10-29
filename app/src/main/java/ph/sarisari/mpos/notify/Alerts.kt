package ph.sarisari.mpos.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import ph.sarisari.mpos.PosApp
import ph.sarisari.mpos.domain.Feature
import ph.sarisari.mpos.domain.UtangAging
import ph.sarisari.mpos.util.Expiry
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** One summary notification a day (~8:00 Manila): low stock, expiring/expired, Utang unpaid 30+ days, supplier bills due within 3 days. Local only: no server. */
class AlertsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as PosApp).container; val admin = c.db.admin()
        if (admin.setting("alerts_on") != "1" || !c.license.state().allows(Feature.ALERTS)) return Result.success()
        val today = Expiry.today(); val warn = admin.setting("expiry_warn_days")?.toIntOrNull()?.coerceIn(1, 365) ?: 30
        val low = c.db.catalog().lowStockNow().size
        val exp = c.db.catalog().expiringCount(today.plusDays(warn.toLong()).toString())
        val owing = UtangAging.compute(c.db.sales().allLedger(), System.currentTimeMillis()).count { it.oldestDays >= 30 }
        val bills = c.db.catalog().dueBillCount(today.plusDays(3).toString())
        val parts = listOfNotNull(low.takeIf { it > 0 }?.let { "$it low stock" }, exp.takeIf { it > 0 }?.let { "$it expired/expiring" },
            owing.takeIf { it > 0 }?.let { "$it customers owe 30+ days" }, bills.takeIf { it > 0 }?.let { "$it supplier bills due" })
        if (parts.isEmpty()) return Result.success()
        Alerts.post(applicationContext, "Store check", parts.joinToString(" · "))
        return Result.success()
    }
}

object Alerts {
    private const val CHANNEL = "daily_alerts"

    fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(ctx: Context, title: String, text: String) {
        if (!canPost(ctx)) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Daily store alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(ctx).notify(1001, n) } catch (_: SecurityException) {}
    }

    /** Runs daily; first run at the next 8:00 AM Manila. The worker itself checks the on/off switch and the plan. */
    fun schedule(ctx: Context) {
        val now = ZonedDateTime.now(ZoneId.of("Asia/Manila")); var next = now.withHour(8).withMinute(0).withSecond(0).withNano(0); if (!next.isAfter(now)) next = next.plusDays(1)
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("daily-alerts", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AlertsWorker>(24, TimeUnit.HOURS).setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS).build())
    }
}
