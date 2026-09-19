package com.example.agent.rootpilot.deepseek

import com.example.agent.rootpilot.model.RootPilotConfig
import java.net.URI

/** Only local development relays may use cleartext transport. */
internal fun RootPilotConfig.apiValidationError(): String? {
    val uri = try {
        URI(baseUrl)
    } catch (_: Exception) {
        return "API 地址格式无效"
    }
    if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
        return "API 地址须为不含凭据、查询参数或片段的服务地址"
    }
    val localRelay = uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]")
    if (uri.scheme != "https" && !localRelay) return "API 地址须使用 HTTPS；本机 Relay 可使用 HTTP"
    if (uri.host.equals("api.deepseek.com", ignoreCase = true) && apiKey.isBlank()) {
        return "请先配置 DeepSeek Token"
    }
    if (apiKey.any { it.isWhitespace() || it.code !in 33..126 }) return "Token 格式无效，请检查空格或换行"
    if (model.isBlank()) return "模型名称不能为空"
    return null
}
