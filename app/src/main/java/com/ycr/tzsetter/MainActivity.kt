package com.ycr.tzsetter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 预加载数据
        ZipTimezoneLookup.ensureLoaded(applicationContext)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF1E5EFF),
                onPrimary = Color.White,
                surface = Color(0xFFF7F8FA),
                background = Color(0xFFF7F8FA),
            )) {
                AppScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    val context = LocalContext.current
    var zipInput by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ZipTimezoneLookup.LookupResult?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var lastApplyResult by remember { mutableStateOf<SystemTimezoneSetter.Result?>(null) }
    var hasPermission by remember { mutableStateOf(SystemTimezoneSetter.hasPermission(context)) }
    var showGuide by remember { mutableStateOf(false) }

    // 定期刷新权限状态
    LaunchedEffect(Unit) {
        while (true) {
            hasPermission = SystemTimezoneSetter.hasPermission(context)
            kotlinx.coroutines.delay(2000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("时区助手", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
                actions = {
                    TextButton(onClick = { showGuide = true }) {
                        Text("ADB 授权")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PermissionStatusCard(hasPermission, onShowGuide = { showGuide = true })

            ZipInputCard(
                zipInput = zipInput,
                onZipChange = {
                    zipInput = it
                    errorMsg = null
                    lastApplyResult = null
                },
                onQuery = {
                    val r = ZipTimezoneLookup.lookup(context, zipInput)
                    if (r == null) {
                        result = null
                        errorMsg = "无法识别这个邮编。美国请输 5 位数字（如 97058），加拿大请输 6 位 FSA（如 M5V3L9）"
                    } else {
                        result = r
                        errorMsg = null
                    }
                    lastApplyResult = null
                }
            )

            errorMsg?.let {
                ErrorCard(it)
            }

            result?.let { r ->
                ResultCard(
                    result = r,
                    onApply = {
                        lastApplyResult = SystemTimezoneSetter.setSystemTimezone(context, r.timezoneId)
                        hasPermission = SystemTimezoneSetter.hasPermission(context)
                    },
                    onOpenSettings = { SystemTimezoneSetter.openDateSettings(context) },
                )

                lastApplyResult?.let { applyRes ->
                    ApplyResultCard(applyRes, onShowGuide = { showGuide = true })
                }
            }

            Spacer(Modifier.height(32.dp))
            FooterText()
        }
    }

    if (showGuide) {
        AdbGuideDialog(onDismiss = { showGuide = false })
    }
}

@Composable
fun PermissionStatusCard(hasPermission: Boolean, onShowGuide: () -> Unit) {
    val bg = if (hasPermission) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
    val fg = if (hasPermission) Color(0xFF1B5E20) else Color(0xFFE65100)
    val icon = if (hasPermission) Icons.Default.CheckCircle else Icons.Default.Warning
    val title = if (hasPermission) "已授权自动模式" else "未授权，当前为手动模式"
    val subtitle = if (hasPermission)
        "查询后点「自动设置」即可修改系统时区"
    else
        "需要先通过 ADB 授权一次，之后永久生效"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(32.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = fg)
                Text(subtitle, fontSize = 13.sp, color = fg)
            }
            if (!hasPermission) {
                TextButton(onClick = onShowGuide) {
                    Text("查看方法", color = fg)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZipInputCard(zipInput: String, onZipChange: (String) -> Unit, onQuery: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("输入邮编", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            OutlinedTextField(
                value = zipInput,
                onValueChange = onZipChange,
                placeholder = { Text("例：97058 / M5V 3L9") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.Characters
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = onQuery,
                enabled = zipInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("查询时区", modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
fun ErrorCard(msg: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Error, null, tint = Color(0xFFC62828))
            Text(msg, color = Color(0xFFC62828), fontSize = 14.sp)
        }
    }
}

@Composable
fun ResultCard(
    result: ZipTimezoneLookup.LookupResult,
    onApply: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var nowStr by remember { mutableStateOf("") }
    LaunchedEffect(result.timezoneId) {
        while (true) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone(result.timezoneId)
            }
            nowStr = sdf.format(Date())
            kotlinx.coroutines.delay(1000)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Public, null, tint = Color(0xFF1E5EFF))
                Text("查询结果", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    if (result.country == ZipTimezoneLookup.Country.US) "🇺🇸 US" else "🇨🇦 CA",
                    fontSize = 13.sp, color = Color.Gray
                )
            }

            InfoRow("邮编", result.zipInput)
            InfoRow("时区 ID", result.timezoneId, mono = true)
            InfoRow("当前偏移", result.offsetDisplay, mono = true)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Schedule, null,
                    tint = Color(0xFF666666),
                    modifier = Modifier.size(16.dp))
                Text("当地时间 $nowStr",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF444444))
            }

            if (result.country == ZipTimezoneLookup.Country.US && !result.matchedPrecise) {
                Text("ℹ️ 基于 ZIP3（前 3 位）匹配。跨时区州建议输入完整 5 位邮编。",
                    fontSize = 12.sp, color = Color.Gray)
            }

            Divider(Modifier.padding(vertical = 4.dp))

            Button(
                onClick = onApply,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("自动设置为系统时区", modifier = Modifier.padding(vertical = 4.dp))
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("打开系统设置（手动）", modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.width(72.dp))
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
fun ApplyResultCard(result: SystemTimezoneSetter.Result, onShowGuide: () -> Unit) {
    when (result) {
        is SystemTimezoneSetter.Result.Success -> {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                Row(Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32))
                    Column {
                        Text("✓ 系统时区已更新", fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                        Text(result.tzId, fontSize = 13.sp,
                            color = Color(0xFF2E7D32), fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        is SystemTimezoneSetter.Result.PermissionDenied -> {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFE65100))
                        Text("未授权，无法自动设置",
                            fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                    }
                    Text("请先通过 ADB 授权（一次性操作，永久有效）",
                        fontSize = 13.sp, color = Color(0xFF5D4037))
                    Button(onClick = onShowGuide,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))) {
                        Text("查看授权方法")
                    }
                }
            }
        }
        is SystemTimezoneSetter.Result.Error -> {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                Row(Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Error, null, tint = Color(0xFFC62828))
                    Text("设置失败：${result.message}", color = Color(0xFFC62828))
                }
            }
        }
    }
}

@Composable
fun AdbGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ADB 一次性授权", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StepText("1", "在手机上打开开发者模式",
                    "设置 → 关于手机 → 连续点击「版本号」7 次")
                StepText("2", "开启 USB 调试",
                    "设置 → 系统 → 开发者选项 → 打开「USB 调试」")
                StepText("3", "电脑连接手机，或使用无线调试",
                    "电脑：用数据线连接并在电脑安装 ADB（platform-tools）\n" +
                    "手机：开启「无线调试」后，用 LADB 等 app 本机执行也可")
                StepText("4", "执行这条命令",
                    "复制下方命令到 ADB 终端：")

                // 可复制的命令框
                val cmd = SystemTimezoneSetter.adbCommand
                Surface(
                    color = Color(0xFF0D1117),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            cmd,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFF9ECE6A),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { copyToClipboard(context, cmd) }) {
                            Icon(Icons.Default.ContentCopy, "复制",
                                tint = Color(0xFFB0BEC5),
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }

                StepText("5", "完成",
                    "授权后无需重启。返回本 app，顶部状态会变成「已授权」。\n" +
                    "之后输入邮编点「自动设置」即可，永久生效（除非卸载本 app）。")

                Spacer(Modifier.height(8.dp))
                Text("💡 常见问题",
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1E5EFF))
                Text("· 提示 Operation not allowed：部分国行 ROM（小米/华为）限制更严，" +
                        "此时可改用 Shizuku 或使用「打开系统设置」手动选择。",
                    fontSize = 12.sp, color = Color.Gray)
                Text("· 提示找不到包：确认已安装本 app，包名 ${SystemTimezoneSetter.PACKAGE_NAME}",
                    fontSize = 12.sp, color = Color.Gray)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("我知道了") } }
    )
}

@Composable
fun StepText(num: String, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            color = Color(0xFF1E5EFF),
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(num, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(detail, fontSize = 12.sp, color = Color(0xFF555555))
        }
    }
}

@Composable
fun FooterText() {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()) {
        Text("📍 ${SystemTimezoneSetter.deviceInfo()}",
            fontSize = 11.sp, color = Color.Gray)
        Spacer(Modifier.height(4.dp))
        Text("数据基于 IANA tzdata + USPS/CanadaPost 邮编分配规则",
            fontSize = 11.sp, color = Color.Gray)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("adb command", text))
    android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
}
