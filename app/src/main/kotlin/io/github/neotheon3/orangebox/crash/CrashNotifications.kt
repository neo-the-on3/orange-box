package io.github.neotheon3.orangebox.crash

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.neotheon3.orangebox.MainActivity
import io.github.neotheon3.orangebox.R
import io.github.neotheon3.orangebox.core.common.ApplicationScope
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.driving.crash.CrashMonitor
import io.github.neotheon3.orangebox.core.driving.crash.CrashPrompt
import io.github.neotheon3.orangebox.core.driving.crash.CrashTrigger
import io.github.neotheon3.orangebox.driving.DrivingNotifications
import io.github.neotheon3.orangebox.feature.driving.CrashPromptController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Whether any of our activities is started (on screen). */
@Singleton
class AppVisibility @Inject constructor() : Application.ActivityLifecycleCallbacks {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()
    private var started = 0

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    override fun onActivityStarted(activity: Activity) { started++; _visible.value = started > 0 }
    override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0); _visible.value = started > 0 }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/**
 * Notification fallback for the crash prompt (#54) while Orange Box is not on screen: a high-priority
 * alarm-category notification counting down (chronometer), with a full-screen intent to the prompt where
 * the platform allows it, and "I'm OK" / "Call" / "SMS" actions. Call and SMS go through [MainActivity],
 * which opens the dialer / SMS app: nothing is called or sent by the app.
 */
@Singleton
class CrashNotifier @Inject constructor(
    private val controller: CrashPromptController,
    private val visibility: AppVisibility,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun install(context: Context) {
        val app = context.applicationContext
        scope.launch {
            combine(controller.prompt, visibility.visible) { p, visible -> p?.takeIf { !visible && it.trigger != CrashTrigger.MANUAL } }
                .distinctUntilChanged { a, b -> a?.id == b?.id && a?.timedOut == b?.timedOut }
                .collect { p -> if (p == null) cancel(app) else post(app, p) }
        }
    }

    fun post(context: Context, p: CrashPrompt) {
        ensureChannel(context)
        if (!DrivingNotifications.canPost(context)) {
            OrangeLog.i(OrangeLog.Areas.APP, "crash_notification_skipped", "reason" to "no_permission")
            return
        }
        val number = controller.targets.value.dialNumber
        val open = activity(context, 20, p.id, ACTION_OPEN)
        val builder = NotificationCompat.Builder(context, CHANNEL_CRASH)
            .setSmallIcon(R.drawable.ic_stat_drive)
            .setContentTitle(context.getString(R.string.notif_crash_title))
            .setContentText(
                if (p.timedOut) context.getString(R.string.notif_crash_timed_out, number)
                else context.getString(R.string.notif_crash_text, number),
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(if (p.timedOut) activity(context, 21, p.id, ACTION_DIAL) else open)
            .addAction(0, context.getString(R.string.notif_crash_ok), cancelIntent(context, p.id))
            .addAction(0, context.getString(R.string.notif_crash_call, number), activity(context, 21, p.id, ACTION_DIAL))
            .addAction(0, context.getString(R.string.notif_crash_sms), activity(context, 22, p.id, ACTION_SMS))
        val left = p.remainingSeconds
        if (!p.timedOut && left != null) {
            builder.setWhen(System.currentTimeMillis() + left * 1_000L).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true)
        }
        if (DrivingNotifications.canUseFullScreenIntent(context)) builder.setFullScreenIntent(open, true)
        try {
            NotificationManagerCompat.from(context).notify(ID_CRASH, builder.build())
            OrangeLog.i(OrangeLog.Areas.APP, "crash_notification", "timed_out" to p.timedOut)
        } catch (_: SecurityException) {
            OrangeLog.w(OrangeLog.Areas.APP, "crash_notification_skipped", "reason" to "security_exception")
        }
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID_CRASH)

    private fun activity(context: Context, request: Int, id: Long, action: String) = PendingIntent.getActivity(
        context, request,
        Intent(context, MainActivity::class.java).setAction(action).putExtra(EXTRA_PROMPT_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelIntent(context: Context, id: Long) = PendingIntent.getBroadcast(
        context, 23, Intent(context, CrashCancelReceiver::class.java).putExtra(EXTRA_PROMPT_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_CRASH = "crash"
        const val ID_CRASH = 1003
        const val EXTRA_PROMPT_ID = "io.github.neotheon3.orangebox.CRASH_PROMPT_ID"
        const val ACTION_OPEN = "io.github.neotheon3.orangebox.CRASH_OPEN"
        const val ACTION_DIAL = "io.github.neotheon3.orangebox.CRASH_DIAL"
        const val ACTION_SMS = "io.github.neotheon3.orangebox.CRASH_SMS"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_CRASH, context.getString(R.string.channel_crash), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.channel_crash_desc) },
            )
        }

        /**
         * Handles a crash-notification tap in [MainActivity]: dial / SMS open the phone's app for the
         * prompt (if it is still the current one); open just shows the app, where the prompt is drawn.
         */
        fun handle(activity: Activity, intent: Intent?, controller: CrashPromptController): Boolean {
            val action = intent?.action ?: return false
            if (action != ACTION_OPEN && action != ACTION_DIAL && action != ACTION_SMS) return false
            val id = intent.getLongExtra(EXTRA_PROMPT_ID, -1)
            val current = controller.prompt.value?.takeIf { it.id == id }
            when (action) {
                ACTION_DIAL -> if (current != null) controller.dial(activity, id) else activity.startActivity(controller.dialIntent())
                ACTION_SMS -> if (current != null) controller.sms(activity, id) else activity.startActivity(controller.smsIntent(activity))
            }
            return true
        }
    }
}

/** "I'm OK" on the crash notification. */
@AndroidEntryPoint
class CrashCancelReceiver : BroadcastReceiver() {
    @Inject lateinit var monitor: CrashMonitor

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(CrashNotifier.EXTRA_PROMPT_ID, -1)
        if (monitor.prompt.value?.id == id) monitor.cancel()
        NotificationManagerCompat.from(context).cancel(CrashNotifier.ID_CRASH)
    }
}
