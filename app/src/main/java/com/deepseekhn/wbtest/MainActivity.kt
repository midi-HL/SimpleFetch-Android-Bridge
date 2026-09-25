package com.deepseekhn.wbtest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseekhn.wbtest.bridge.BridgeService
import com.deepseekhn.wbtest.bridge.MiHealthHelper
import com.deepseekhn.wbtest.bridge.UiState
import com.deepseekhn.wbtest.bridge.WearableBridge
import com.deepseekhn.wbtest.protocol.ConnState

class MainActivity : ComponentActivity() {

    private var bridge: WearableBridge? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val s = (service as BridgeService.LocalBinder).service()
            bridge = s.bridge
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            bridge = null
        }
    }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeService.start(this)
        bindService(Intent(this, BridgeService::class.java), connection, BIND_AUTO_CREATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val ctx = LocalContext.current
            var ui by remember { mutableStateOf(UiState()) }
            var tab by remember { mutableStateOf(0) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) {
                    bridge?.let { ui = it.state.value }
                    kotlinx.coroutines.delay(400)
                }
            }
            AppTheme {
                HomeScreen(
                    ui = ui,
                    tab = tab,
                    onTab = { tab = it },
                    onRefresh = { bridge?.refreshNodes() },
                    onOpenMiHealth = { MiHealthHelper.open(ctx) },
                    onOpenWatchApp = { bridge?.openWatchApp() },
                    onManualHandshake = { bridge?.manualHandshake() },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0F1115)
    ) { content() }
}

private val Bg = Color(0xFF0F1115)
private val Surface = Color(0xFF1B1E26)
private val SurfaceVar = Color(0xFF242833)
private val Accent = Color(0xFF2E6BE6)
private val TextPrimary = Color(0xFFE8EAF0)
private val TextSecondary = Color(0xFF9AA1B0)
private val DotOk = Color(0xFF34C759)
private val DotErr = Color(0xFFE5484D)
private val DotWork = Color(0xFFE0A030)
private val DotIdle = Color(0xFF5A6070)

@Composable
fun HomeScreen(
    ui: UiState,
    tab: Int,
    onTab: (Int) -> Unit,
    onRefresh: () -> Unit,
    onOpenMiHealth: () -> Unit,
    onOpenWatchApp: () -> Unit,
    onManualHandshake: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        // 顶部栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "应用图标",
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Text("SimpleFetch 桥接", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceVar)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TabButton("连接", selected = tab == 0) { onTab(0) }
            TabButton("关于", selected = tab == 1) { onTab(1) }
        }

        Spacer(Modifier.height(16.dp))

        if (tab == 0) {
            ConnectTab(ui, onRefresh, onOpenMiHealth, onOpenWatchApp, onManualHandshake)
        } else {
            AboutTab()
        }
    }
}

@Composable
fun TabButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFF2F3542) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (selected) TextPrimary else TextSecondary, fontSize = 14.sp)
    }
}

@Composable
fun ConnectTab(
    ui: UiState,
    onRefresh: () -> Unit,
    onOpenMiHealth: () -> Unit,
    onOpenWatchApp: () -> Unit,
    onManualHandshake: () -> Unit,
) {
    val ctx = LocalContext.current
    val connected = ui.device != null

    Column(Modifier.fillMaxSize()) {
        // 顶部：手环连接情况
        Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (connected) DotOk else DotErr)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (connected) "手环已连接" else "手环未连接",
                        color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        ui.device?.name ?: "请在小米运动健康中配对手环",
                        color = TextSecondary, fontSize = 12.sp
                    )
                }
                SmallButton("刷新", onClick = onRefresh)
            }
        }

        Spacer(Modifier.height(10.dp))

        // 桥接状态 + 打开小米运动健康
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "桥接：" + when (ui.connState) {
                        ConnState.Connected -> "已握手"
                        ConnState.Handshaking -> "握手中…"
                        ConnState.Failed -> "失败"
                        ConnState.Disconnected -> "未连接"
                    },
                    color = TextPrimary, fontSize = 14.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "小米运动健康: " + if (MiHealthHelper.installedPackage(ctx) != null) "已安装" else "未安装",
                    color = TextSecondary, fontSize = 12.sp
                )
            }
            SmallButton("打开小米运动健康", onClick = onOpenMiHealth)
        }

        Spacer(Modifier.height(12.dp))

        // 手动操作：打开手环端应用 / 请求握手
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                modifier = Modifier.weight(1f),
                text = "打开手环端应用",
                bg = Accent,
                onClick = onOpenWatchApp
            )
            ActionButton(
                modifier = Modifier.weight(1f),
                text = "请求握手",
                bg = SurfaceVar,
                onClick = onManualHandshake
            )
        }

        Spacer(Modifier.height(16.dp))

        // 大统计：发送请求 / 成功 / 失败
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox(Modifier.weight(1f), "发送请求", ui.requests.toString(), TextPrimary)
            StatBox(Modifier.weight(1f), "成功", ui.success.toString(), DotOk)
            StatBox(Modifier.weight(1f), "失败", ui.fail.toString(), DotErr)
        }

        Spacer(Modifier.height(14.dp))

        Text("运行日志", color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))

        // 大日志区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF14161C))
                .padding(12.dp)
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (ui.logs.isEmpty()) {
                    Text("等待手环请求…", color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                ui.logs.reversed().forEach { line ->
                    Text(line, color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(3.dp))
                }
            }
        }
    }
}

@Composable
fun StatBox(modifier: Modifier, label: String, value: String, valueColor: Color) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = valueColor, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceVar)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = TextPrimary, fontSize = 12.sp)
    }
}

@Composable
fun ActionButton(modifier: Modifier = Modifier, text: String, bg: Color, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun AboutTab() {
    val ctx = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("关于", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))

        // 说明
        Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("SimpleFetch 桥接 · 手机端网络代理", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "本应用把 SimpleFetch 协议从电脑端 AstroBox 插件原生移植为独立 Android App，" +
                        "让小米手环/Vela 快应用无需电脑即可联网。\n\n" +
                        "手环本身不能联网，快应用通过 Vela 互联通道把每一次 HTTP/SSE 请求发到配对手机，" +
                        "由本应用代发真实网络请求，再把结果回传手环。\n\n" +
                        "你可以把本工程 fork 后改包名、换签名，编译成自己的连接器，" +
                        "配合自己的 Vela 快应用使用。",
                    color = TextSecondary, fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("原项目", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))

        LinkCard("SimpleFetch 插件文档", "协议与接入说明") {
            openUrl(ctx, "https://docs.b4qaq.cn/docs/simplefetch/")
        }
        Spacer(Modifier.height(8.dp))
        LinkCard("通信协议规范", "SF_* 消息格式 / 分片 / SSE") {
            openUrl(ctx, "https://docs.b4qaq.cn/docs/simplefetch/protocol/")
        }
        Spacer(Modifier.height(8.dp))
        LinkCard("SimpleFetch 插件源码", "B4QAQ/SimpleFetch-AstroBoxV2-Plugins (AGPL-3.0)") {
            openUrl(ctx, "https://github.com/B4QAQ/SimpleFetch-AstroBoxV2-Plugins")
        }
        Spacer(Modifier.height(8.dp))
        LinkCard("ResonaUI", "手环端 simpleFetch.js / interconnect.js 来源") {
            openUrl(ctx, "https://github.com/MingChengNetworkStudio/ResonaUI")
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "SimpleFetch Android Bridge · v1.0\n包名 com.deepseekhn.wbtest · 按 AGPL-3.0 二次开发",
            color = TextSecondary, fontSize = 11.sp
        )
    }
}

private fun openUrl(ctx: android.content.Context, url: String) {
    ctx.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
fun LinkCard(title: String, sub: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(title, color = TextPrimary, fontSize = 14.sp)
                Spacer(Modifier.height(2.dp))
                Text(sub, color = TextSecondary, fontSize = 11.sp)
            }
            Text("↗", color = TextSecondary, fontSize = 18.sp)
        }
    }
}
