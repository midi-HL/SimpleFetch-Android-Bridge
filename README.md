# SimpleFetch Android Bridge

把 [SimpleFetch](https://docs.b4qaq.cn/docs/simplefetch/) 网络桥接协议从电脑端 AstroBox 插件（Rust/WASM）原生移植为独立 Android App。

小米手环/Vela 快应用本身不能联网，本应用作为手机端代理：手环通过 Vela 互联通道把 HTTP/SSE 请求发过来，手机代发真实网络请求，再把结果（含大响应分片、SSE 流式事件）回传手环。**不需要电脑，不需要 AstroBox。**

> 上游协议作者：[B4QAQ/SimpleFetch-AstroBoxV2-Plugins](https://github.com/B4QAQ/SimpleFetch-AstroBoxV2-Plugins)（AGPL-3.0）。本工程按同协议二次开发。

---

## 功能

- 自动发现已配对的小米手环（通过 xms-wearable SDK）
- 通道级握手：`start` → `ready` → `SF_HANDSHAKE` → `SF_HANDSHAKE_ACK`
- 心跳保活（3s 间隔，30s 无 PING 自动断开）
- 普通 HTTP 请求（GET/POST/PUT/DELETE/PATCH/HEAD），自动处理 gzip/deflate
- 大响应分片回传（整体 base64 后切片，12KB/片，80ms 间隔，256KB 截断）
- SSE 流式请求，逐事件回传 `SF_SSE_EVENT`
- 文件下载 `SF_DOWNLOAD` → `SF_DL_RESPONSE` 分片回传
- 前台服务保活，手机后台时手环仍可联网
- 手动按钮：打开手环端快应用、重新请求握手
- 运行日志与请求/成功/失败计数

## 界面

深色 Material3 风格。连接页顶部显示手环连接状态与桥接握手状态，中部是手动操作按钮和统计，底部是实时日志。关于页列出上游协议文档与源码链接。

---

## 给最终用户

直接安装 `app-release.apk`：

1. 在小米运动健康里配对手环（小米手环 9 / Vela 手表均可）。
2. 安装本 APK，打开后授予互联权限。
3. 在手环上打开你的快应用，手机端会自动完成 `start/ready` 与 `SF_HANDSHAKE`。
4. 如果握手没自动建立，点「请求握手」；需要从手机唤起手环应用时点「打开手环端应用」。

---

## 给开发者：把它改成你自己的连接器

本工程默认包名是 `com.deepseekhn.wbtest`。**xms-wearable SDK 的互联通道要求手机端 APK 的包名 + 签名与手环端 RPK 的包名 + 签名完全一致**，否则 `addListener` / `sendMessage` 会失败或收不到消息。所以你必须 fork 后改包名、换自己的签名。

### 1. 环境要求

- Android Studio Hedgehog 或更新
- JDK 17
- Android SDK 34（minSdk 27）
- 小米 xms-wearable SDK：把 `xms-wearable-lib_1.4_release.aar` 放到 `app/libs/`（从 [小米 Vela 开发者文档](https://iot.mi.com/vela/quickapp/) 获取，或从本工程 release 附带的 aar 取）
- 手环端快应用工程（用了 `simpleFetch.js` + `interconnect`）

### 2. 改包名

把所有 `com.deepseekhn.wbtest` 替换成你自己的包名（例如 `com.yourcompany.bridge`）：

- `app/build.gradle` 里的 `namespace` 和 `applicationId`
- `app/src/main/java/com/deepseekhn/wbtest/` 整个目录重命名
- 所有 `.kt` 文件里的 `package com.deepseekhn.wbtest...` 与 `import com.deepseekhn.wbtest...`
- `AndroidManifest.xml` 里的 `.MainActivity` / `.bridge.BridgeService`（相对名，改了包名后会自动生效，但建议检查）

### 3. 生成你自己的签名

#### 方式 A：用 keytool 直接生成新 keystore

```bash
keytool -genkeypair -v -keystore mybridge.jks -keyalg RSA -keysize 2048 -validity 10000 -alias mybridge
```

然后修改 `app/build.gradle` 的 `signingConfigs.release`：

```groovy
signingConfigs {
    release {
        storeFile file('mybridge.jks')
        storePassword '你的密码'
        keyAlias 'mybridge'
        keyPassword '你的密码'
    }
}
```

#### 方式 B：你的手环 RPK 已经用 private.pem + certificate.pem 签了

如果你已有 PEM 格式的私钥和证书（例如 Vela 开发者后台导出的），用本工程 `tools/MakeKey.java` 转成 Android 能用的 PKCS12 keystore：

```bash
javac tools/MakeKey.java
java -cp tools MakeKey private.pem certificate.pem mybridge.jks simplefetch simplefetch
```

参数：`private.pem`、`certificate.pem`、输出 jks 路径、storepass、alias。

> **重要**：这个 jks 必须和你手环 RPK 用的是同一对密钥。如果手环 RPK 是用另一套密钥签的，互联通道会因为包名/签名不匹配而拒绝通信。

### 4. 手环端快应用不需要改

手环端 `simpleFetch.js` + `bridge.js` 已经实现了完整的 SF 协议客户端：

- 收到手机端 `start` 自动回 `ready`
- 收到 `SF_HANDSHAKE` 自动回 `SF_HANDSHAKE_ACK`
- 自动 PING/PONG 心跳
- 大响应按 `chunk`/`totalChunks` 重组、整体 base64 解码
- SSE 事件分发

你只要在快应用里 `import` 这个 `simpleFetch.js`，然后正常调 `fetch()` 即可，不需要关心手机端是怎么实现的。

参考实现：[ResonaUI](https://github.com/MingChengNetworkStudio/ResonaUI) 的 `src/common/js/simpleFetch.js`。

### 5. 构建

```bash
# 设置环境
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android/sdk

# Windows PowerShell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-17"
$env:ANDROID_HOME="C:\Android\Sdk"

.\gradlew.bat assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。

---

## 协议速查

完整规范见 [docs.b4qaq.cn/docs/simplefetch/protocol](https://docs.b4qaq.cn/docs/simplefetch/protocol/)。

| 消息 | 方向 | 说明 |
|---|---|---|
| `start`（裸字符串） | 手机→手环 | 通道级握手第一门 |
| `{type:"ready"}` | 手环→手机 | 通道就绪应答 |
| `SF_HANDSHAKE` | 双向 | `{type, data:{}}` |
| `SF_HANDSHAKE_ACK` | 双向 | `{type, status:"OK", data:{}}` |
| `SF_PING` / `SF_PONG` | 双向 | 心跳，带 `ts` |
| `SF_REQUEST` | 手环→手机 | `{id, url, method, headers, body, sse, timeout}` |
| `SF_RESPONSE` | 手机→手环 | `{id, statusCode, headers, body, chunk, totalChunks}` |
| `SF_SSE_EVENT` | 手机→手环 | `{id, event, data}` |
| `SF_SSE_END` / `SF_SSE_ERROR` | 手机→手环 | SSE 结束 |
| `SF_CLOSE` | 手环→手机 | 取消某个 SSE |
| `SF_CLOSE_BRIDGE` | 双向 | 断开桥接 |
| `SF_DOWNLOAD` | 手环→手机 | 文件下载请求 |
| `SF_DL_RESPONSE` | 手机→手环 | `{id, append, isLast, data(base64)}` |
| `SF_DL_FAIL` | 手机→手环 | 下载失败 |

**分片策略**：响应 ≤16KB 且合法 UTF-8 时单条直发；否则整体 base64 后按 12KB 切片回传，片间 80ms。手机端硬截断 256KB。这些参数在 `SfEngine.kt` 顶部可调——片太大 xms-wearable 会静默丢包，片太多/太快会触发手环看门狗重启。

---

## 项目结构

```
app/
  build.gradle
  libs/xms-wearable-lib_1.4_release.aar
  src/main/
    AndroidManifest.xml
    java/com/deepseekhn/wbtest/
      MainActivity.kt          # Compose UI
      bridge/
        BridgeService.kt       # 前台服务保活
        WearableBridge.kt      # xms-wearable 节点/权限/收发/start-ready
        MiHealthHelper.kt      # 打开小米运动健康
      http/
        HttpExecutor.kt        # OkHttp HTTP + SSE
      protocol/
        SfEngine.kt            # SF_* 状态机/分片/心跳
    res/                       # 深色主题 + 自适应图标
tools/
  MakeKey.java                 # PEM → PKCS12 keystore
```

## 不包含什么

- `app/simplefetch.jks`（你的私钥签名）—— 不要提交
- `local.properties`（SDK 路径）
- `build/`、`.gradle/`、`.idea/`
- 任何 `.apk` / `.rpk` 构建产物

## License

[GNU AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html) © 2026 midi-HL

基于 [B4QAQ/SimpleFetch-AstroBoxV2-Plugins](https://github.com/B4QAQ/SimpleFetch-AstroBoxV2-Plugins)（AGPL-3.0）二次开发。修改后的源码必须同样以 AGPL-3.0 开源。
