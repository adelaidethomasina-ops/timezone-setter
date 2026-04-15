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
 * 系统控制器：统一管理时区 + Mock Location 两项能力。
 *
 * v3.2.2 改动：不再只依赖 isDeviceOwnerApp() 的返回值来决定路径，
 * 而是"盲试"——挨个尝试所有可能的路径，谁不抛异常就用谁。
 * 这样能绕过某些 ROM 上 isDeviceOwnerApp() 误报 false 的 bug。
 */
object SystemTimezoneSetter {

    sealed class Result {
        data class Success(val tzId: String, val via: String) : Result()
        data class PermissionDenied(val tzId: String) : Result()
        data class Error(val message: String) : Result()
    }

    enum class AuthMode { DEVICE_OWNER, NORMAL_PERMISSION, ACCESSIBILITY, NONE }

    fun getAuthMode(context: Context): AuthMode {
        if (isDeviceOwner(context)) return AuthMode.DEVICE_OWNER
        if (hasPermission(context)) return AuthMode.NORMAL_PERMISSION
        if (TimezoneAccessibilityService.isEnabled(context)) return AuthMode.ACCESSIBILITY
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

    /**
     * 改系统时区。先尝试 DO 路径，失败再尝试 AlarmManager。
     * 不依赖 isDeviceOwner() 的返回值，直接"盲试"。
     */
    fun setSystemTimezone(context: Context, tzId: String): Result {
        // 盲试 1：Device Owner API（如果不是 DO 会抛 SecurityException，没关系往下走）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(context, TimezoneAdminReceiver::class.java)
                val ok = dpm.setTimeZone(admin, tzId)
                if (ok) return Result.Success(tzId, "DEVICE_OWNER")
                // 返回 false 但没异常，继续尝试 AlarmManager
            } catch (e: SecurityException) {
                // 不是 DO，降级
            } catch (e: Exception) {
                // 其他异常，降级
            }
        }

        // 盲试 2：AlarmManager.setTimeZone（normal 权限 or 某些 ROM 直接通过）
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setTimeZone(tzId)
            val now = java.util.TimeZone.getDefault().id
            if (now == tzId || java.util.TimeZone.getDefault()
                    .hasSameRules(java.util.TimeZone.getTimeZone(tzId))) {
                return Result.Success(tzId, "ALARM_MANAGER")
            }
        } catch (e: SecurityException) {
            // 没权限，返回 PermissionDenied
            return Result.PermissionDenied(tzId)
        } catch (e: Exception) {
            return Result.Error(e.message ?: e.javaClass.simpleName)
        }

        // 两条路都走不通
        return Result.PermissionDenied(tzId)
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

    /**
     * 解除 Device Owner 身份。
     */
    fun clearDeviceOwner(context: Context): Result {
        if (!isDeviceOwner(context)) {
            return Result.Error("当前不是 Device Owner，无需解除")
        }
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.clearDeviceOwnerApp(context.packageName)
            if (isDeviceOwner(context)) {
                Result.Error("系统未接受解除请求")
            } else {
                Result.Success("cleared", "DEVICE_OWNER_CLEARED")
            }
        } catch (e: SecurityException) {
            Result.Error("无权解除：${e.message}")
        } catch (e: Exception) {
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    const val PACKAGE_NAME = "com.ycr.tzsetter"
    const val ADMIN_COMPONENT = "$PACKAGE_NAME/.TimezoneAdminReceiver"

    val deviceOwnerCommand: String
        get() = "adb shell dpm set-device-owner $ADMIN_COMPONENT"

    val grantPermissionCommand: String
        get() = "adb shell pm grant $PACKAGE_NAME android.permission.SET_TIME_ZONE"
}
