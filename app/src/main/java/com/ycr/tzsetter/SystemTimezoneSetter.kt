package com.ycr.tzsetter

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * 系统时区设置器。
 *
 * 改系统时区需要 android.permission.SET_TIME_ZONE 权限，
 * 该权限是 signature|privileged 级别，普通 app 无法直接获得。
 *
 * 解决方案：通过 ADB 一次性授权：
 *   adb shell pm grant com.ycr.tzsetter android.permission.SET_TIME_ZONE
 *
 * 授权后 AlarmManager.setTimeZone() 即可调用。重启不会丢失。
 */
object SystemTimezoneSetter {

    sealed class Result {
        data class Success(val tzId: String) : Result()
        data class PermissionDenied(val tzId: String) : Result()
        data class Error(val message: String) : Result()
    }

    /** 检查是否已经有 SET_TIME_ZONE 权限 */
    fun hasPermission(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.SET_TIME_ZONE) ==
                PackageManager.PERMISSION_GRANTED
    }

    /** 尝试直接修改系统时区 */
    fun setSystemTimezone(context: Context, tzId: String): Result {
        if (!hasPermission(context)) {
            return Result.PermissionDenied(tzId)
        }
        return try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setTimeZone(tzId)
            // 验证是否真的生效
            val now = java.util.TimeZone.getDefault().id
            if (now == tzId || java.util.TimeZone.getDefault().hasSameRules(java.util.TimeZone.getTimeZone(tzId))) {
                Result.Success(tzId)
            } else {
                // 某些 ROM setTimeZone 静默失败
                Result.Error("调用未报错但时区未变更（当前=$now），可能 ROM 限制")
            }
        } catch (e: SecurityException) {
            Result.PermissionDenied(tzId)
        } catch (e: Exception) {
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 打开系统的日期和时间设置页（作为降级方案） */
    fun openDateSettings(context: Context) {
        val intent = Intent(Settings.ACTION_DATE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 获取设备信息，用于引导页显示 */
    fun deviceInfo(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
    }

    /** 包名，用于引导页显示 ADB 命令 */
    const val PACKAGE_NAME = "com.ycr.tzsetter"

    val adbCommand: String
        get() = "adb shell pm grant $PACKAGE_NAME android.permission.SET_TIME_ZONE"
}
