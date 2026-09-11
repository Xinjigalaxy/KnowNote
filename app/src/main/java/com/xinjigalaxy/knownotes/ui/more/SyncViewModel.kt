package com.xinjigalaxy.knownotes.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xinjigalaxy.knownotes.R
import com.xinjigalaxy.knownotes.data.model.SyncLogEntry
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import com.xinjigalaxy.knownotes.data.sync.LanInfo
import com.xinjigalaxy.knownotes.data.sync.SYNC_DEFAULT_PORT
import com.xinjigalaxy.knownotes.data.sync.SyncClient
import com.xinjigalaxy.knownotes.data.sync.SyncCoordinator
import com.xinjigalaxy.knownotes.data.sync.SyncServer
import com.xinjigalaxy.knownotes.data.sync.parseSyncAddress
import com.xinjigalaxy.knownotes.ui.components.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * 局域网同步页（需求文档 4 + 7 第三阶段）。
 *
 * 一主多从：
 * - 主机：开着内嵌 HTTP 服务，接待从机；
 * - 从机：填主机地址 + 共享密钥，点「开始同步」推自己的变更、拉主机的变更。
 *
 * 两种角色都在同一页里，因为个人场景下同一台设备经常两头都要当。
 */
class SyncViewModel(
    private val repo: NoteRepository,
    private val server: SyncServer,
    private val client: SyncClient,
    private val coordinator: SyncCoordinator,
    private val prefs: UiPrefs,
    private val appScope: CoroutineScope,
    private val deviceName: String,
) : ViewModel() {

    data class UiState(
        val deviceId: String = "",
        val deviceName: String = "",
        val addresses: List<String> = emptyList(),
        val portText: String = SYNC_DEFAULT_PORT.toString(),
        val key: String = "",
        val hostRunning: Boolean = false,
        val hostPort: Int = SYNC_DEFAULT_PORT,
        val servedRequests: Int = 0,
        val hostError: String? = null,
        val hostAutoStart: Boolean = false,
        val peer: String = "",
        val lastSyncAt: Long = 0L,
        val busy: Boolean = false,
        val message: UiMessage? = null,
        val log: List<SyncLogEntry> = emptyList(),
    )

    private val local = MutableStateFlow(UiState())

    val uiState: StateFlow<UiState> = combine(
        local,
        server.status,
        repo.observeSyncLog(),
    ) { state, status, log ->
        state.copy(
            hostRunning = status.running,
            hostPort = status.port,
            servedRequests = status.servedRequests,
            hostError = status.lastError,
            log = log,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), UiState())

    init {
        local.update {
            it.copy(
                deviceId = repo.deviceId,
                deviceName = deviceName,
                addresses = LanInfo.ipv4Addresses(),
                portText = prefs.hostPort().toString(),
                key = ensureKey(),
                peer = prefs.peerAddress(),
                hostAutoStart = prefs.hostAutoStart(),
            )
        }
        viewModelScope.launch {
            local.update { it.copy(lastSyncAt = repo.lastSyncAt()) }
        }
    }

    fun refreshAddresses() {
        local.update { it.copy(addresses = LanInfo.ipv4Addresses()) }
    }

    // ---------- 设置项 ----------

    fun setPort(text: String) {
        local.update { it.copy(portText = text.filter { c -> c.isDigit() }.take(5)) }
    }

    fun setKey(text: String) {
        val clean = text.trim()
        prefs.saveSyncKey(clean)
        local.update { it.copy(key = clean) }
    }

    fun regenerateKey() {
        val fresh = generateKey()
        prefs.saveSyncKey(fresh)
        local.update { it.copy(key = fresh, message = UiMessage(R.string.key_changed_enter_the_same_one_on_the_other_devi)) }
    }

    fun setPeer(text: String) {
        prefs.savePeerAddress(text.trim())
        local.update { it.copy(peer = text) }
    }

    // ---------- 主机 ----------

    fun startHost() {
        val port = local.value.portText.toIntOrNull()
        if (port == null || port !in 1..65535) {
            local.update { it.copy(message = UiMessage(R.string.port_must_be_between_1_and_65535)) }
            return
        }
        val key = ensureKey()
        prefs.saveHostPort(port)
        server.start(appScope, port, key)
            .onSuccess {
                prefs.saveHostAutoStart(true)
                val ip = LanInfo.ipv4Addresses().firstOrNull()
                local.update {
                    it.copy(
                        hostAutoStart = true,
                        addresses = LanInfo.ipv4Addresses(),
                        // 没有局域网地址时，占位符里放「本机 IP」这条提示本身
                        message = UiMessage(
                            R.string.host_started_enter_ip_port_on_the_other_device,
                            listOf(ip ?: UiMessage(R.string.local_ip), port),
                        ),
                    )
                }
            }
            .onFailure { e ->
                local.update {
                    it.copy(
                        message = UiMessage(
                            R.string.failed_to_start_host_e_message,
                            listOf(e.message ?: e.javaClass.simpleName),
                        ),
                    )
                }
            }
    }

    fun stopHost() {
        server.stop()
        prefs.saveHostAutoStart(false)
        local.update { it.copy(hostAutoStart = false, message = UiMessage(R.string.host_stopped_listening)) }
    }

    // ---------- 从机 ----------

    fun pingHost() {
        val parsed = parseSyncAddress(local.value.peer)
        if (parsed == null) {
            local.update { it.copy(message = UiMessage(R.string.invalid_host_address_e_g_192_168_1_20_or_192_168)) }
            return
        }
        local.update {
            it.copy(
                busy = true,
                message = UiMessage(R.string.probing_parsed_first_parsed_second, listOf(parsed.first, parsed.second)),
            )
        }
        viewModelScope.launch {
            val reachable = client.ping(parsed.first, parsed.second)
            local.update {
                it.copy(
                    busy = false,
                    message = if (reachable) {
                        UiMessage(
                            R.string.reachable_parsed_first_parsed_second_host_is_onl,
                            listOf(parsed.first, parsed.second),
                        )
                    } else {
                        UiMessage(
                            R.string.cannot_reach_parsed_first_parsed_second_check_th,
                            listOf(parsed.first, parsed.second),
                        )
                    },
                )
            }
        }
    }

    fun syncNow() {
        val state = local.value
        val parsed = parseSyncAddress(state.peer)
        if (parsed == null) {
            local.update { it.copy(message = UiMessage(R.string.invalid_host_address_e_g_192_168_1_20_or_192_168)) }
            return
        }
        if (state.key.isBlank()) {
            local.update { it.copy(message = UiMessage(R.string.enter_the_shared_key_first_it_must_match_the_hos)) }
            return
        }
        val (host, port) = parsed
        local.update {
            it.copy(
                busy = true,
                message = UiMessage(R.string.syncing_with_host_port, listOf(host, port)),
            )
        }

        viewModelScope.launch {
            // 一次同步的完整流程（取增量 / 发送 / 落库 / 水位线 / 日志）在 SyncCoordinator 里，
            // 页面只负责把结果讲给用户听 —— 逻辑留在这里就没法被回环测试覆盖了。
            coordinator.syncWith(host, port, state.key)
                .onSuccess { session ->
                    local.update {
                        it.copy(
                            busy = false,
                            lastSyncAt = session.watermark,
                            // 摘要由三段资源拼成，时间戳打平那条只在有冲突时出现
                            message = UiMessage.concat(
                                UiMessage(
                                    R.string.sync_complete_pulled_session_pulled_applied_sess,
                                    listOf(session.pulled, session.applied.changed),
                                ),
                                UiMessage(
                                    R.string.pushed_session_pushed_host_applied_session_peera,
                                    listOf(session.pushed, session.peerApplied),
                                ),
                                if (session.conflicts > 0) {
                                    UiMessage(
                                        R.string.session_conflicts_timestamp_conflicts_resolved_l,
                                        listOf(session.conflicts),
                                    )
                                } else {
                                    null
                                },
                            ),
                        )
                    }
                }
                .onFailure { e ->
                    local.update {
                        it.copy(
                            busy = false,
                            message = UiMessage(
                                R.string.sync_failed_e_message,
                                listOf(e.message ?: e.javaClass.simpleName),
                            ),
                        )
                    }
                }
        }
    }

    fun clearLog() {
        viewModelScope.launch { repo.clearSyncLog() }
    }

    // ---------- 共享密钥 ----------

    private fun ensureKey(): String {
        val existing = prefs.syncKey()
        if (existing.isNotBlank()) return existing
        val generated = generateKey()
        prefs.saveSyncKey(generated)
        return generated
    }

    /** 去掉 0/O/1/I/l 之类容易看错的字符：这个串要被人念着敲到另一台设备上。 */
    private fun generateKey(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
