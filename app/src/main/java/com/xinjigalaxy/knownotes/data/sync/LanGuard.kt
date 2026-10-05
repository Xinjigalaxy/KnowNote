package com.xinjigalaxy.knownotes.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress

/**
 * 局域网边界（v1.10.1）：只在局域网里同步。
 *
 * 约束来源：同步走的是明文 HTTP，内容（含图片）在链路上是裸的。在局域网里这可控，
 * 一旦对端在公网、或手机在移动数据上把笔记推出去，就等于把笔记交给中间链路。所以：
 *
 * - **从机方向**：当前网络不是局域网（蜂窝网），或对端地址不在局域网网段（公网 IP、
 *   域名解析到公网），同步**不启动** —— 手动点了也会被拦下并说明原因（见 `SyncCoordinator`）。
 * - **主机方向**：来连的地址不在局域网，直接 403（见 `SyncServer`）。
 * - **中心方向**（server/knownote_hub.py）：同一套规矩，另有 `lan_only` 开关。
 *
 * 判定做成可注入的接口（[LanScopeCheck]），是为了让测试能塞一个「就是不在局域网」的判定进来，
 * 把被拦的那条路真的跑一遍（只测「能同步」是发现不了这种问题的）。
 */
enum class LanScope {
    /** 局域网内，可以同步。 */
    LAN,

    /** 当前网络不是局域网（移动数据等）。 */
    NOT_LAN_NETWORK,

    /** 对端地址不在局域网网段（公网地址，或域名解析到公网）。 */
    NOT_LAN_PEER,
}

/** 判定入口：生产用 [LanGuard]，测试塞一个固定值。 */
fun interface LanScopeCheck {
    fun scopeOf(peerHost: String): LanScope
}

/** 被局域网边界拦下时抛的异常 —— 上层据此讲人话，而不是丢一句英文技术描述给用户。 */
class LanBlockedException(val scope: LanScope) : IllegalStateException(
    when (scope) {
        LanScope.NOT_LAN_NETWORK -> "Sync blocked: the device is not on a local network (mobile data?)"
        LanScope.NOT_LAN_PEER -> "Sync blocked: the peer is not on the local network"
        LanScope.LAN -> "Sync blocked"
    }
)

/**
 * 地址分类：纯函数，单独放一份，好让 JVM 单测直接验（不碰 Android API）。
 *
 * 「局域网」= RFC1918（10/8、172.16/12、192.168/16）+ 环回（127/8）+ 链路本地（169.254/16），
 * 以及 IPv6 的 `::1`、ULA（fc00::/7）、链路本地（fe80::/10）。
 * **刻意不算**运营商大内网 100.64.0.0/10：手机在移动数据上就是那个网段，
 * 把它算进来这条规矩就形同虚设（与中心侧 `is_lan_address` 保持一致）。
 */
object LanAddress {

    private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** 是不是局域网地址。非 IP 字面量（域名、空串、乱码）一律当「不是」—— 保守的方向才是安全的方向。 */
    fun isLan(text: String): Boolean {
        val address = parse(text) ?: return false
        val bytes = address.address
        return when (bytes.size) {
            4 -> isLanV4(bytes)
            16 -> isLanV6(bytes)
            else -> false
        }
    }

    /** [InetAddress] 版本（主机侧判断对端地址时用）。 */
    fun isLan(address: InetAddress): Boolean = address.address.let { bytes ->
        when (bytes.size) {
            4 -> isLanV4(bytes)
            16 -> isLanV6(bytes)
            else -> false
        }
    }

    /**
     * 对端地址算不算局域网里的。
     *
     * 域名要解析：**所有**解析结果都在局域网内才算（同时指向公网的域名不老实，宁可不连）；
     * 解析不了也算「不是」。注意会做一次 DNS 查询，**别在主线程调用**。
     */
    fun isLanPeer(host: String): Boolean {
        val cleaned = clean(host)
        if (cleaned.isEmpty()) return false
        parse(cleaned)?.let { return isLan(it) }        // IP 字面量：不查 DNS
        val resolved = runCatching { InetAddress.getAllByName(cleaned) }.getOrNull() ?: return false
        if (resolved.isEmpty()) return false
        return resolved.all { isLan(it) }
    }

    /** 去掉 `[...]`（IPv6 方括号写法）、`]` 后面那一截（`:端口`）与路径尾巴。 */
    private fun clean(text: String): String =
        text.trim().removePrefix("[").substringBefore("]").substringBefore("/")

    private fun isLanV4(bytes: ByteArray): Boolean {
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        return a == 10 ||
            a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
    }

    private fun isLanV6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        if (bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte()) return true   // ::1
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        if (first and 0xFE == 0xFC) return true                                              // fc00::/7
        return first == 0xFE && second and 0xC0 == 0x80                                      // fe80::/10
    }

    /** 只解析 IP **字面量**（含 v4-mapped、`IP:端口` 与 `[IPv6]:端口` 写法）；域名返回 null，交给调用方决定要不要查。 */
    private fun parse(text: String): InetAddress? {
        var candidate = clean(text)
        if (candidate.count { it == ':' } == 1 && candidate.contains('.')) {
            candidate = candidate.substringBefore(":")     // 192.168.1.5:8765
        }
        val literal = IPV4_LITERAL.matches(candidate) || candidate.contains(':')
        if (!literal) return null
        return runCatching { InetAddress.getByName(candidate) }.getOrNull()
    }
}

/**
 * 生产用的判定：系统网络状态 + 对端地址。
 *
 * - 蜂窝网（且没有 Wi-Fi / 以太网 / VPN）＝不在局域网：这就是「人在外面，别把笔记推出去」那一条。
 * - VPN 视为中性：Clash 这类代理常年在跑，把它当成「不在局域网」会让同步莫名其妙全被拦。
 * - 没有活动网络、或拿不到 ConnectivityManager 时**放行**：让真正的连接去失败，
 *   报「连不上主机」比报「不在局域网」准确。
 */
class LanGuard(private val context: Context) : LanScopeCheck {

    override fun scopeOf(peerHost: String): LanScope {
        if (!onLanNetwork()) return LanScope.NOT_LAN_NETWORK
        if (!LanAddress.isLanPeer(peerHost)) return LanScope.NOT_LAN_PEER
        return LanScope.LAN
    }

    private fun onLanNetwork(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val capabilities = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }
            .getOrNull() ?: return true
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            return true
        }
        return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}
