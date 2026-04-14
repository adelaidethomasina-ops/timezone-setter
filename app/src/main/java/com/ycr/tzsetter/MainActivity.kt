package com.ycr.tzsetter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        ZipTimezoneLookup.ensureLoaded(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1E5EFF),
                    onPrimary = Color.White,
                    surface = Color(0xFFF7F8FA),
                    background = Color(0xFFF7F8FA),
                )
            ) { AppScreen() }
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
    var authMode by remember { mutableStateOf(SystemTimezoneSetter.getAuthMode(context)) }
    var showGuide by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            authMode = SystemTimezoneSetter.getAuthMode(context)
            kotlinx.coroutines.delay(2000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("时区助手", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
                actions = {
                    TextButton(onClick = { showGuide = true }) { Text("授权设置") }
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
            AuthStatusCard(authMode, onShowGuide = { showGuide = true })

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
                        errorMsg = "无法识别这个邮编。美国 5 位数字（如 97058），加拿大 6 位 FSA（如 M5V3L9）"
                    } else {
                        result = r
                        errorMsg = null
                    }
                    lastApplyResult = null
                }
            )

            errorMsg?.let { ErrorCard(it) }

            result?.let { r ->
                ResultCard(
                    result = r,
                    onApply = {
                        lastApplyResult = SystemTimezoneSetter.setSystemTimezone(context, r.timezoneId)
                        authMode = SystemTimezoneSetter.getAuthMode(context)
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
        AuthGuideDialog(authMode = authMode, onDismiss = { showGuide = false })
    }
}

@Composable
fun AuthStatusCard(mode: SystemTimezoneSetter.AuthMode, onShowGuide: () -> Unit) {
    val (bg, fg, icon, title, subtitle) = when (mode) {
        SystemTimezoneSetter.AuthMode.DEVICE_OWNER -> Tuple5(
            Color(0xFFE8F5E9), Color(0xFF1B5E20),
            Icons.Default.Security, "✓ 设备管理员模式",
            "已注册为 Device Owner，修改时区将直接生效"
        )
        SystemTimezoneSetter.AuthMode.NORMAL_PERMISSION -> Tuple5(
            Color(0xFFE3F2FD), Color(0xFF0D47A1),
            Icons.Default.CheckCircle, "已授权（普通模式）",
            "可尝试直接修改系统时区"
        )
        SystemTimezoneSetter.AuthMode.NONE -> Tuple5(
            Color(0xFFFFF3E0), Color(0xFFE65100),
            Icons.Default.Warning, "未授权",
            "需要通过 ADB 配置一次，点右边查看方法"
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = fg)
                Text(subtitle, fontSize = 13.sp, color = fg)
            }
            if (mode != SystemTimezoneSetter.AuthMode.DEVICE_OWNER) {
                TextButton(onClick = onShowGuide) { Text("设置", color = fg) }
            }
        }
    }
}

private data class Tuple5<A, B, C, D, E>(
    val a: A, val b: B, val c: C, val d: D, val e: E
)

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
            ) { Text("查询时区", modifier = Modifier.padding(vertical = 4.dp)) }
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Schedule, null,
                    tint = Color(0xFF666666), modifier = Modifier.size(16.dp)
                )
                Text(
                    "当地时间 $nowStr",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF444444)
                )
            }
            if (result.country == ZipTimezoneLookup.Country.US && !result.matchedPrecise) {
                Text(
                    "ℹ️ 基于 ZIP3 匹配。跨时区州建议输入完整 5 位邮编。",
                    fontSize = 12.sp, color = Color.Gray
                )
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
            value, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
fun ApplyResultCard(result: SystemTimezoneSetter.Result, onShowGuide: () -> Unit) {
    when (result) {
        is SystemTimezoneSetter.Result.Success -> {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32))
                    Column {
                        Text("✓ 系统时区已更新",
                            fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                        Text(result.tzId, fontSize = 13.sp,
                            color = Color(0xFF2E7D32), fontFamily = FontFamily.Monospace)
                        Text("via ${result.via}", fontSize = 11.sp, color = Color(0xFF558B2F))
                    }
                }
            }
        }
        is SystemTimezoneSetter.Result.PermissionDenied -> {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFE65100))
                        Text("未授权，无法自动设置",
                            fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                    }
                    Text("你的 ROM 需要 Device Owner 模式才能改时区。点下方按钮查看方法。",
                        fontSize = 13.sp, color = Color(0xFF5D4037))
                    Button(
                        onClick = onShowGuide,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))
                    ) { Text("查看授权方法") }
                }
            }
        }
        is SystemTimezoneSetter.Result.Error -> {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Error, null, tint = Color(0xFFC62828))
                    Text("设置失败：${result.message}", color = Color(0xFFC62828))
                }
            }
        }
    }
}

@Composable
fun AuthGuideDialog(
    authMode: SystemTimezoneSetter.AuthMode,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ADB 授权（一次性）", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("方案一：Device Owner（推荐，全自动）",
                    fontWeight = FontWeight.Bold, color = Color(0xFF1E5EFF), fontSize = 14.sp)
                Text("适用于所有 Android 版本和 ROM，包括 Android 10 上权限被提升的情况。",
                    fontSize = 12.sp, color = Color(0xFF555555))

                StepText("1", "清除所有账户",
                    "设置 → 账户 → 移除所有账户（Google、三星、小米、华为等）\n" +
                    "⚠️ 这一步必做，只要有一个账户就会失败")
                StepText("2", "开启 USB 调试",
                    "设置 → 开发者选项 → USB 调试 → 允许")
                StepText("3", "电脑连接手机，执行命令",
                    "把下面命令粘贴到 ADB 终端执行：")

                CopyableCommand(SystemTimezoneSetter.deviceOwnerCommand, context)

                StepText("4", "成功标志",
                    "命令执行后无报错，且看到 `Success: Device owner set to ...`。\n" +
                    "回到本 app，顶部变绿「✓ 设备管理员模式」即完成。")

                Spacer(Modifier.height(8.dp))

                Text("方案二：普通权限（仅部分 ROM 可用）",
                    fontWeight = FontWeight.Bold, color = Color(0xFF546E7A), fontSize = 14.sp)
                Text("若上面方案不方便，可先试：", fontSize = 12.sp, color = Color(0xFF555555))
                CopyableCommand(SystemTimezoneSetter.grantPermissionCommand, context)
                Text("在部分 Android 10/11 ROM 上这条命令会提示 not a changeable permission type，" +
                    "这种情况必须用方案一。",
                    fontSize = 11.sp, color = Color(0xFF999999))

                Spacer(Modifier.height(8.dp))
                Text("💡 常见问题",
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1E5EFF))
                Text("· Device Owner 命令失败且提示 has already been provisioned：\n" +
                        "  账户未清完全，检查所有账户（含 Google 账户、厂商账户）",
                    fontSize = 12.sp, color = Color.Gray)
                Text("· 设为 Device Owner 后要撤销：\n" +
                        "  adb shell dpm remove-active-admin ${SystemTimezoneSetter.ADMIN_COMPONENT}",
                    fontSize = 12.sp, color = Color.Gray)
                Text("· 卸载本 app 会自动解除 Device Owner",
                    fontSize = 12.sp, color = Color.Gray)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("我知道了") } }
    )
}

@Composable
fun CopyableCommand(cmd: String, context: Context) {
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
                cmd, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = Color(0xFF9ECE6A), modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { copyToClipboard(context, cmd) }) {
                Icon(
                    Icons.Default.ContentCopy, "复制",
                    tint = Color(0xFFB0BEC5), modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun StepText(num: String, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            color = Color(0xFF1E5EFF),
            shape = CircleShape,
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
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
