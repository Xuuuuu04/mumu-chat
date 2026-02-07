package mumu.xsy.mumuchat.system

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings

data class ResolvedActivity(
    val packageName: String,
    val className: String
)

data class StartActivityResult(
    val started: Boolean,
    val resolved: ResolvedActivity? = null,
    val error: String? = null
)

object SystemIntents {
    val preferredClockPackages = listOf(
        "com.android.deskclock",
        "com.miui.deskclock"
    )

    val preferredCalendarPackages = listOf(
        "com.xiaomi.calendar",
        "com.miui.calendar",
        "com.android.calendar"
    )

    val preferredMiuiSecurityCenterPackages = listOf(
        "com.miui.securitycenter"
    )

    fun startActivityBestEffort(
        context: Context,
        intent: Intent,
        preferredPackages: List<String> = emptyList()
    ): StartActivityResult {
        val pm = context.packageManager
        val resolved = resolveBestActivity(pm, intent, preferredPackages)
        val finalIntent = Intent(intent).apply {
            if (resolved != null) component = ComponentName(resolved.packageName, resolved.className)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(finalIntent)
            StartActivityResult(started = true, resolved = resolved)
        } catch (e: ActivityNotFoundException) {
            StartActivityResult(started = false, resolved = resolved, error = e.message ?: "ActivityNotFoundException")
        } catch (e: SecurityException) {
            StartActivityResult(started = false, resolved = resolved, error = e.message ?: "SecurityException")
        } catch (e: Exception) {
            StartActivityResult(started = false, resolved = resolved, error = e.message ?: "Exception")
        }
    }

    fun resolveCandidates(context: Context, intent: Intent): List<ResolvedActivity> {
        val pm = context.packageManager
        val infos = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return infos.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            val pkg = ai.packageName ?: return@mapNotNull null
            val cls = ai.name ?: return@mapNotNull null
            ResolvedActivity(pkg, cls)
        }
    }

    fun buildSetTimerIntent(seconds: Int?, message: String?, skipUi: Boolean): Intent {
        return Intent(AlarmClock.ACTION_SET_TIMER).apply {
            if (seconds != null && seconds > 0) putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (!message.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
        }
    }

    fun buildInsertCalendarEventIntent(
        title: String,
        startMs: Long,
        endMs: Long,
        location: String?,
        notes: String?
    ): Intent {
        return Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            if (!location.isNullOrBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            if (!notes.isNullOrBlank()) putExtra(CalendarContract.Events.DESCRIPTION, notes)
        }
    }

    fun buildAppNotificationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    }

    fun buildAppDetailsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun buildMiuiAutoStartIntent(): Intent {
        return Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        }
    }

    fun buildRequestIgnoreBatteryOptimizationsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun buildRequestExactAlarmPermissionIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= 31) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
    }

    private fun resolveBestActivity(
        pm: PackageManager,
        intent: Intent,
        preferredPackages: List<String>
    ): ResolvedActivity? {
        val candidates = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                val pkg = ai.packageName ?: return@mapNotNull null
                val cls = ai.name ?: return@mapNotNull null
                ResolvedActivity(pkg, cls)
            }
        if (candidates.isEmpty()) return null
        if (preferredPackages.isNotEmpty()) {
            for (p in preferredPackages) {
                candidates.firstOrNull { it.packageName == p }?.let { return it }
            }
        }
        return candidates.first()
    }
}

