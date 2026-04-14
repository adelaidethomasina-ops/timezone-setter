package com.ycr.tzsetter

import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * 系统控制器（兼容 v2 命名）。统一管理时区 + Mock Location 两项能力。
 */
object SystemTimezoneSetter {

    sealed class Result {
        data class Success(val tzId: String, val via: String) : Result()
        data class PermissionDenied(val tzId: String) : Result()
        data class Error(val message: String) : Result()
    }

    enum class AuthMode { DEVICE_OWNER, NORMAL_PERMISSION, NONE }

    fun getAuthMode(context: Context): AuthMode {
        if (isDeviceOwner(context)) return AuthMode.DEVICE_OWNER
        if (hasPermission(context)) return AuthMode.NORMAL_PERMISSION
        return AuthMode.NONE
    }

    fun hasPermission(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.SET_TIME_ZONE) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun isDeviceOwner(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) { false }
    }

    /** 设置系统时区 */
    fun setSystemTimezone(context: Context, tzId: String): Result {
        val mode = getAuthMode(context)
        return when (mode) {
            AuthMode.DEVICE_OWNER -> trySetViaDeviceOwner(context, tzId)
            AuthMode.NORMAL_PERMISSION -> trySetViaAlarmManager(context, tzId, "NORMAL")
            AuthMode.NONE -> Result.PermissionDenied(tzId)
        }
    }

    private fun trySetViaDeviceOwner(context: Context, tzId: String): Result {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, TimezoneAdminReceiver::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val ok = dpm.setTimeZone(admin, tzId)
                if (ok) Result.Success(tzId, "DEVICE_OWNER")
                else trySetViaAlarmManager(context, tzId, "DEVICE_OWNER_FALLBACK")
            } else {
                trySetViaAlarmManager(context, tzId, "DEVICE_OWNER_LEGACY")
            }
        } catch (e: SecurityException) {
            Result.PermissionDenied(tzId)
        } catch (e: Exception) {
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun trySetViaAlarmManager(context: Context, tzId: String, via: String): Result {
        return try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setTimeZone(tzId)
            val now = java.util.TimeZone.getDefault().id
            if (now == tzId || java.util.TimeZone.getDefault()
                    .hasSameRules(java.util.TimeZone.getTimeZone(tzId))) {
                Result.Success(tzId, via)
            } else {
                Result.Error("调用未报错但时区未变更（当前=$now）")
            }
        } catch (e: SecurityException) {
            Result.PermissionDenied(tzId)
        } catch (e: Exception) {
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun openDateSettings(context: Context) {
        val intent = Intent(Settings.ACTION_DATE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun deviceInfo(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
    }

    const val PACKAGE_NAME = "com.ycr.tzsetter"
    const val ADMIN_COMPONENT = "$PACKAGE_NAME/.TimezoneAdminReceiver"

    val deviceOwnerCommand: String
        get() = "adb shell dpm set-device-owner $ADMIN_COMPONENT"

    val grantPermissionCommand: String
        get() = "adb shell pm grant $PACKAGE_NAME android.permission.SET_TIME_ZONE"
}
