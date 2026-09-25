package com.deepseekhn.wbtest.bridge

import android.content.Context
import android.util.Log
import com.deepseekhn.wbtest.protocol.ConnState
import com.deepseekhn.wbtest.protocol.SfEngine
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.NodeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一个已连接的穿戴节点（手环/手表） */
data class NodeInfo(val id: String, val name: String)

/** 暴露给 UI 的整体状态 */
data class UiState(
    val device: NodeInfo? = null,
    val devicesLoading: Boolean = false,
    val connState: ConnState = ConnState.Disconnected,
    val failReason: String = "",
    val requests: Int = 0,
    val success: Int = 0,
    val fail: Int = 0,
    val lastStatus: String = "",
    val logs: List<String> = emptyList(),
)

/**
 * 封装小米穿戴 SDK：节点发现、权限、消息收发，并持有 SimpleFetch 协议引擎。
 * 检测到手环后自动建立 start/ready 通道并握手；不主动唤起手环端快应用。
 */
class WearableBridge(private val context: Context) {

    private val nodeApi: NodeApi = Wearable.getNodeApi(context)
    private val messageApi: MessageApi = Wearable.getMessageApi(context)
    private val authApi = Wearable.getAuthApi(context)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile var node: Node? = null
        private set

    private var listenerRegistered = false

    /** 通道就绪：收到手环回的 {type:"ready"} 后才为 true */
    @Volatile private var channelReady = false

    // 协议引擎：send 回调把 JSON 通过 messageApi 发回手环
    val engine = SfEngine(
        send = { text ->
            val n = node
            if (n != null) {
                messageApi.sendMessage(n.id, text.toByteArray())
                    .addOnFailureListener { Log.w("SF", "send fail: ${it.message}") }
            }
        },
        onStateChange = { publishEngineState() },
    ).also { it.onLog = { m -> log(m) } }

    private val listener = OnMessageReceivedListener { _, message ->
        val text = message.decodeToString()
        Log.d("SF", "recv: $text")
        // 第一道门：手环回 ready 表示双向通道已开，之后才走 SF 握手
        if (text == "ready" || text.contains("\"type\"") && text.contains("ready")) {
            if (!channelReady) {
                channelReady = true
                log("通道就绪（收到 ready），发起握手")
                engine.initiateHandshake()
            }
            return@OnMessageReceivedListener
        }
        engine.onIncoming(text)
    }

    fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.value = _state.value.copy(
            logs = (_state.value.logs + "[$ts] $msg").takeLast(300)
        )
    }

    /** 刷新已连接节点（即小米运动健康生态下手环是否连着） */
    fun refreshNodes() {
        _state.value = _state.value.copy(devicesLoading = true)
        nodeApi.connectedNodes
            .addOnSuccessListener { nodes ->
                _state.value = _state.value.copy(devicesLoading = false)
                if (nodes.isNotEmpty()) {
                    node = nodes[0]
                    _state.value = _state.value.copy(
                        device = NodeInfo(nodes[0].id, nodes[0].name)
                    )
                    log("检测到手环：${nodes[0].name}")
                    ensureAuthAndListen()
                } else {
                    node = null
                    _state.value = _state.value.copy(device = null)
                    log("未检测到手环连接")
                }
            }
            .addOnFailureListener {
                _state.value = _state.value.copy(devicesLoading = false)
                log("获取设备列表失败：${it.message ?: ""}")
            }
    }

    private fun ensureAuthAndListen() {
        val n = node ?: return
        authApi.checkPermissions(n.id, arrayOf(Permission.DEVICE_MANAGER))
            .addOnSuccessListener { results ->
                for ((i, granted) in results.withIndex()) {
                    if (!granted) {
                        authApi.requestPermission(n.id, Permission.DEVICE_MANAGER)
                        log("已申请互联权限")
                    }
                }
                registerListener(n.id)
            }
            .addOnFailureListener { registerListener(n.id) }
    }

    private fun registerListener(nodeId: String) {
        if (listenerRegistered) return
        messageApi.addListener(nodeId, listener)
            .addOnSuccessListener {
                listenerRegistered = true
                sendStart()
                startAutoHandshake()
            }
            .addOnFailureListener { e ->
                if (e.message?.contains("You have registered", ignoreCase = true) == true) {
                    listenerRegistered = true
                    sendStart()
                    startAutoHandshake()
                } else {
                    Log.w("SF", "addListener fail: ${e.message}")
                }
            }
    }

    /** 第一道门：发裸字符串 start，等手环回 ready */
    private fun sendStart() {
        val n = node ?: return
        channelReady = false
        log("发送 start 建立通道...")
        messageApi.sendMessage(n.id, "start".toByteArray())
            .addOnFailureListener { Log.w("SF", "send start fail: ${it.message}") }
    }

    /**
     * 后台自动握手：检测到节点后，若未连接就周期性重发握手。
     * 不主动唤起手环端应用——由用户手动点"打开手环端应用"。
     */
    private fun startAutoHandshake() {
        engine.ensureHeartbeat()
        scope.launch {
            while (true) {
                delay(3000)
                if (node == null) continue
                // 通道未就绪时不发 SF_HANDSHAKE（手环 bridge.js 要先收到 start 才开门）
                if (!channelReady) continue
                if (engine.state == ConnState.Disconnected || engine.state == ConnState.Failed) {
                    engine.initiateHandshake()
                }
            }
        }
    }

    /** 手动：拉起手环端快应用 */
    fun openWatchApp() {
        val n = node ?: run { log("未连接手环，无法打开手环端应用"); return }
        scope.launch {
            nodeApi.launchWearApp(n.id, "pages/index")
                .addOnSuccessListener { log("已拉起手环端快应用") }
                .addOnFailureListener { log("拉起手环应用失败：${it.message ?: ""}") }
        }
    }

    /** 手动：立即请求握手 */
    fun manualHandshake() {
        log("手动请求握手")
        if (!channelReady) sendStart()
        engine.initiateHandshake()
    }

    /** 手动断开（调试用） */
    fun disconnectApp() {
        engine.disconnect()
        log("手动断开桥接")
    }

    fun destroy() {
        node?.let { messageApi.removeListener(it.id) }
        listenerRegistered = false
    }

    private fun publishEngineState() {
        _state.value = _state.value.copy(
            connState = engine.state,
            failReason = engine.failReason,
            requests = engine.stats.requests,
            success = engine.stats.success,
            fail = engine.stats.fail,
            lastStatus = engine.stats.lastStatus,
        )
    }
}
