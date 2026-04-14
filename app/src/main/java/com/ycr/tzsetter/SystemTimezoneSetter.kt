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
 * 系统时区设置器。
 *
 * 两条授权路径：
 *  1. 普通权限路径：SET_TIME_ZONE 是 normal 权限，安装即得；
 *     部分 ROM（包括 Android 10 的某些三星/小米/华为机型）把它提升为
 *     signature|privileged，此时路径 1 失效。
 *  2. Device Owner 路径：通过 ADB 把本 app 设为 Device Owner 后，
 *     以设备管理员身份调用即可绕过限制。
 *     命令：adb shell dpm set-device-owner com.ycr.tzsetter/.TimezoneAdminReceiver
 *     前提：手机上没有添加任何账户。
 */
object SystemTimezoneSetter {

    sealed class Result {
        data class Success(val tzId: String, val via: String) : Result()
        data class PermissionDenied(val tzId: String) : Result()
        data class Error(val message: String) : Result()
    }

    enum class AuthMode {
        /** 已注册为 Device Owner —— 最稳 */
        DEVICE_OWNER,
        /** 有 SET_TIME_ZONE 权限（普通路径）—— 可能可用 */
        NORMAL_PERMISSION,
        /** 两条路都没拿到 */
        NONE
    }

    /** 当前授权模式 */
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
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 尝试改系统时区，优先走 Device Owner 路径。
     */
    fun setSystemTimezone(context: Context, tzId: String): Result {
        val mode = getAuthMode(context)

        // Device Owner 路径
        if (mode == AuthMode.DEVICE_OWNER) {
            return trySetViaDeviceOwner(context, tzId)
        }

        // 普通权限路径
        if (mode == AuthMode.NORMAL_PERMISSION) {
            return trySetViaAlarmManager(context, tzId, via = "NORMAL")
        }

        return Result.PermissionDenied(tzId)
    }

    private fun trySetViaDeviceOwner(context: Context, tzId: String): Result {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, TimezoneAdminReceiver::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Android 9.0+ 的 DPM 接口：setTimeZone(admin, tz) 返回 boolean
                val ok = dpm.setTimeZone(admin, tzId)
                if (ok) {
                    Result.Success(tzId, via = "DEVICE_OWNER")
                } else {
                    // 有些 ROM 的 DPM.setTimeZone 静默失败，降级到 AlarmManager
                    trySetViaAlarmManager(context, tzId, via = "DEVICE_OWNER_FALLBACK")
                }
            } else {
                trySetViaAlarmManager(context, tzId, via = "DEVICE_OWNER_LEGACY")
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
                    .hasSameRules(java.util.TimeZone.getTimeZone(tzId))
            ) {
                Result.Success(tzId, via = via)
            } else {
                Result.Error("调用未报错但时区未变更（当前=$now）")
            }
        } catch (e: SecurityException) {
            Result.PermissionDenied(tzId)
        } catch (e: Exception) {
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 打开系统日期时间设置（降级） */
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
