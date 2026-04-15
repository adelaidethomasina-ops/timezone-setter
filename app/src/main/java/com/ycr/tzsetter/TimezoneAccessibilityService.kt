package com.ycr.tzsetter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 时区自动设置无障碍服务（v3.4 - ColorOS / 一加适配版）。
 *
 * 基于真实 UI dump 编写，针对 ColorOS 12.1 / OxygenOS / 氢 OS 12 优化。
 *
 * 流程：
 *   1. app 跳转到 ACTION_DATE_SETTINGS（日期与时间页）
 *   2. 本服务捕获页面变化
 *   3. STAGE_DATE_PAGE: 找"时区"条目并点击 → 进入时区列表
 *   4. STAGE_TZ_LIST: 找搜索框点击 → 进入搜索状态
 *   5. STAGE_SEARCH: 输入城市中文名 → 点击第一个匹配项
 *   6. STAGE_DONE: 后退两次回到 app
 *
 * 不需要操作"自动设置时区"开关，因为该 ROM 默认关闭，且用户手动设置时区时系统会自动覆盖。
 */
class TimezoneAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TzA11y"

        @Volatile var pendingTimezoneId: String? = null
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var lastStatus: String = ""
            private set
        @Volatile private var inOperation: Boolean = false

        /** 检查无障碍服务是否被用户启用 */
        fun isEnabled(context: Context): Boolean {
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val target = "${context.packageName}/${TimezoneAccessibilityService::class.java.name}"
                enabledServices.split(":").any { it.equals(target, ignoreCase = true) }
            } catch (e: Exception) { false }
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun startAutomation(context: Context, targetTimezoneId: String) {
            pendingTimezoneId = targetTimezoneId
            inOperation = true
            stage = STAGE_DATE_PAGE
            lastStatus = "正在跳转系统设置..."
            val intent = Intent(Settings.ACTION_DATE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        // 阶段
        const val STAGE_DATE_PAGE = 0
        const val STAGE_TZ_LIST = 1
        const val STAGE_SEARCH = 2
        const val STAGE_DONE = 3
        @Volatile var stage: Int = STAGE_DATE_PAGE

        /**
         * IANA 时区 ID → ColorOS 时区列表中显示的中文城市名
         * 列表顺序很重要 —— 第一个是最佳匹配，后续是备选（万一某个版本翻译不一致）
         */
        fun cityNamesForTimezone(tzId: String): List<String> {
            return when (tzId) {
                "America/Los_Angeles" -> listOf("洛杉矶", "Los Angeles")
                "America/New_York" -> listOf("纽约", "New York")
                "America/Chicago" -> listOf("芝加哥", "Chicago")
                "America/Denver" -> listOf("丹佛", "Denver")
                "America/Phoenix" -> listOf("凤凰城", "Phoenix")
                "America/Anchorage" -> listOf("安克雷奇", "Anchorage")
                "America/Adak" -> listOf("埃达克", "Adak")
                "Pacific/Honolulu" -> listOf("檀香山", "火奴鲁鲁", "Honolulu")
                "America/Detroit" -> listOf("底特律", "Detroit")
                "America/Boise" -> listOf("博伊西", "Boise")
                "America/Indiana/Indianapolis" -> listOf("印第安纳波利斯", "Indianapolis")
                "America/Menominee" -> listOf("梅诺米尼", "Menominee")
                "America/Toronto" -> listOf("多伦多", "Toronto")
                "America/Vancouver" -> listOf("温哥华", "Vancouver")
                "America/Edmonton" -> listOf("埃德蒙顿", "Edmonton")
                "America/Winnipeg" -> listOf("温尼伯", "Winnipeg")
                "America/Halifax" -> listOf("哈利法克斯", "Halifax")
                "America/St_Johns" -> listOf("圣约翰斯", "St. Johns")
                "America/Regina" -> listOf("里贾纳", "Regina")
                "America/Iqaluit" -> listOf("伊魁特", "Iqaluit")
                "America/Cambridge_Bay" -> listOf("剑桥湾", "Cambridge Bay")
                "America/Rankin_Inlet" -> listOf("兰金因莱特", "Rankin Inlet")
                "America/Rainy_River" -> listOf("雷尼河", "Rainy River")
                "America/Whitehorse" -> listOf("怀特霍斯", "Whitehorse")
                "America/Yellowknife" -> listOf("耶洛奈夫", "Yellowknife")
                "America/Moncton" -> listOf("蒙克顿", "Moncton")
                "America/St_Thomas" -> listOf("圣托马斯", "St. Thomas")
                "America/Puerto_Rico" -> listOf("波多黎各", "Puerto Rico", "圣胡安")
                "Pacific/Guam" -> listOf("关岛", "Guam")
                "Pacific/Pago_Pago" -> listOf("帕果帕果", "Pago Pago")
                "Pacific/Saipan" -> listOf("塞班", "Saipan")
                else -> {
                    val tail = tzId.substringAfterLast("/").replace("_", " ")
                    listOf(tail)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.i(TAG, "Accessibility service connected")
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    private var lastTickMs: Long = 0
    private var searchInputDone = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!inOperation || event == null) return

        val pkg = event.packageName?.toString() ?: return
        if (!pkg.contains("settings", ignoreCase = true) &&
            !pkg.contains("android", ignoreCase = true)) return

        val now = System.currentTimeMillis()
        if (now - lastTickMs < 250) return
        lastTickMs = now

        Handler(Looper.getMainLooper()).postDelayed({
            tryAutomate()
        }, 200)
    }

    private fun tryAutomate() {
        val tzId = pendingTimezoneId ?: return
        val root = rootInActiveWindow ?: return

        try {
            when (stage) {
                STAGE_DATE_PAGE -> handleDatePage(root)
                STAGE_TZ_LIST -> handleTzList(root, tzId)
                STAGE_SEARCH -> handleSearch(root, tzId)
                STAGE_DONE -> { /* 完成 */ }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Automation error", e)
            lastStatus = "自动操作错误：${e.message}"
        }
    }

    /**
     * 阶段 1：日期与时间页 → 找"时区"条目点击
     *
     * UI 结构（来自 dump_date.xml）：
     *   - text="时区" cls=TextView rid=title
     *   - 父级 LinearLayout 是 clickable
     */
    private fun handleDatePage(root: AccessibilityNodeInfo) {
        // 检查是不是真的在日期页（有"日期和时间"标题或"时区"标题）
        if (!hasNodeWithText(root, "时区") && !hasNodeWithText(root, "日期和时间")) {
            return
        }

        // 找 text="时区" + rid=title 的 TextView，再点它的可点击祖先
        val tzTitle = findTextViewByText(root, "时区") ?: return
        val clickableParent = findClickableAncestor(tzTitle) ?: return
        clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        lastStatus = "已点击「时区」"
        Log.i(TAG, "Clicked 时区 entry")
        stage = STAGE_TZ_LIST
        searchInputDone = false
    }

    /**
     * 阶段 2：时区列表页 → 找搜索框点击 + 输入城市名
     *
     * UI 结构（来自 dump_tzlist.xml）：
     *   - LinearLayout rid=animated_hint_layout (clickable)
     *   - TextView text="搜索城市、国家" rid=animated_hint
     */
    private fun handleTzList(root: AccessibilityNodeInfo, tzId: String) {
        // 必须真的看到了城市列表（含 GMT 字样）才算到达
        if (!hasNodeWithText(root, "GMT") && !hasNodeWithText(root, "搜索城市")) {
            return
        }

        // 找 hint 文本"搜索城市、国家"的节点
        val searchHint = findTextViewByPartialText(root, "搜索城市")
        if (searchHint != null) {
            // 找它的可点击祖先（animated_hint_layout）
            val clickable = findClickableAncestor(searchHint)
            if (clickable != null) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                lastStatus = "已打开搜索框"
                Log.i(TAG, "Clicked search box")
                stage = STAGE_SEARCH
                return
            }
        }

        // 没找到搜索框？降级方案：直接在列表里找城市名
        directClickInList(root, tzId)
    }

    /**
     * 阶段 3：搜索框激活 → 输入城市名 → 等结果出现 → 点击
     */
    private fun handleSearch(root: AccessibilityNodeInfo, tzId: String) {
        val cityNames = cityNamesForTimezone(tzId)

        // 第一步：还没输入过 → 找输入框 EditText 输入
        if (!searchInputDone) {
            val editText = findEditText(root)
            if (editText != null) {
                val firstName = cityNames.firstOrNull() ?: return
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        firstName
                    )
                }
                editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                searchInputDone = true
                lastStatus = "搜索：$firstName"
                Log.i(TAG, "Set search text: $firstName")
                // 等结果加载
                return
            }
            return
        }

        // 第二步：已输入 → 在结果列表中找匹配项点击
        for (city in cityNames) {
            val cityNode = findTextViewByText(root, city)
                ?: findTextViewByPartialText(root, city)
            if (cityNode != null) {
                val clickable = findClickableAncestor(cityNode) ?: cityNode
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                lastStatus = "✓ 已选择：$city"
                Log.i(TAG, "Clicked city: $city")
                stage = STAGE_DONE
                // 1.2 秒后回到 app（两次后退）
                Handler(Looper.getMainLooper()).postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    Handler(Looper.getMainLooper()).postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        Handler(Looper.getMainLooper()).postDelayed({
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            cleanup()
                        }, 400)
                    }, 400)
                }, 1200)
                return
            }
        }

        lastStatus = "搜索结果中未找到城市，请手动选择"
    }

    /**
     * 备用方案：直接在长列表里找城市名（搜索框不可用时）
     */
    private fun directClickInList(root: AccessibilityNodeInfo, tzId: String) {
        val cityNames = cityNamesForTimezone(tzId)
        for (city in cityNames) {
            val cityNode = findTextViewByText(root, city)
            if (cityNode != null) {
                val clickable = findClickableAncestor(cityNode) ?: cityNode
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                lastStatus = "✓ 已选择：$city"
                stage = STAGE_DONE
                Handler(Looper.getMainLooper()).postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    Handler(Looper.getMainLooper()).postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        cleanup()
                    }, 400)
                }, 1000)
                return
            }
        }
        // 找不到就尝试滚动
        val scrollable = findScrollable(root)
        scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun cleanup() {
        inOperation = false
        stage = STAGE_DATE_PAGE
        pendingTimezoneId = null
        searchInputDone = false
    }

    // ============================================================
    // 节点查找工具
    // ============================================================

    private fun findTextViewByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes?.firstOrNull { (it.text?.toString() ?: "") == text }
            ?: nodes?.firstOrNull()
    }

    private fun findTextViewByPartialText(root: AccessibilityNodeInfo, partial: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(partial)
        return nodes?.firstOrNull()
    }

    private fun hasNodeWithText(root: AccessibilityNodeInfo, text: String): Boolean {
        return (root.findAccessibilityNodeInfosByText(text)?.size ?: 0) > 0
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var p = node.parent
        var depth = 0
        while (p != null && depth < 6) {
            if (p.isClickable) return p
            p = p.parent
            depth++
        }
        return null
    }

    private fun findEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = root.className?.toString() ?: ""
        if (cls.contains("EditText", ignoreCase = true)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findEditText(child)
            if (found != null) return found
        }
        return null
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findScrollable(child)
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }
}
