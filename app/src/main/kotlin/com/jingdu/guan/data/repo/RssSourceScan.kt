package com.jingdu.guan.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 一次检测的结果：分成三类，各自多少、都有谁 */
data class ScanReport(
    val total: Int = 0,
    val alive: Int = 0,
    /** 确定失效（域名没了 / 404）—— 直接删 */
    val dead: List<String> = emptyList(),
    /** 连不上或被拒：可能是需要代理，也可能只是抽风 —— 连续几次才删 */
    val needProxy: List<String> = emptyList(),
    /** 这次实际删掉几个 */
    val removed: Int = 0,
    /** 跳过（地址本身有问题） */
    val skipped: Int = 0,
)

/**
 * 订阅源扫描：探活 + 分类 + 按规则删除。
 *
 * 删除规则（照鲸读）：
 * - Dead（域名没了、404）→ **立即删**，这种等下去也不会好
 * - NeedProxy（401/403/超时）→ **连续 [SuspiciousCounter.MAX_SUSPICIOUS] 次才删**，
 *   因为这一档里混着「被墙」和「今天网络差」，删早了用户换个网络就找不回来
 * - Alive → 清零计数
 */
class RssSourceScan(
    private val context: android.content.Context,
    private val repo: RssRepository,
    private val lightCheck: SourceLightCheck,
    private val counter: SuspiciousCounter,
    private val quarantine: SourceQuarantine,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** 进度：已查 / 总数 */
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress

    private val _report = MutableStateFlow<ScanReport?>(null)
    val report: StateFlow<ScanReport?> = _report

    fun clearReport() { _report.value = null }

    private var job: kotlinx.coroutines.Job? = null

    /** 手动触发一次；已经在跑就忽略 */
    fun start() {
        if (_running.value) return
        job = scope.launch { run() }
    }

    /** 停：已查过的结果不写库，用户重新点会从头来 */
    fun cancel() {
        job?.cancel()
        job = null
        _running.value = false
        _progress.value = null
    }

    suspend fun run() {
        if (_running.value) return
        _running.value = true
        _report.value = null
        _progress.value = 0 to 0

        var alive = 0
        var skipped = 0
        val dead = mutableListOf<String>()
        val needProxy = mutableListOf<String>()
        val toDelete = mutableListOf<String>()

        try {
            // 这一轮有没有走代理 —— 决定「连不上」该不该算账
            val proxied = ProxyState.isProxyActive(context)
            // 内置源不参与判定：它们删了就没了，而这个站多半只是需要代理或今天抽风。
            // 用户想删可以自己去列表里删，自动检测不许碰。
            val builtin = runCatching { BuiltinSources.builtinUrls(context) }.getOrDefault(emptySet())
            val all = repo.sources.first().filter { it.enabled && it.id !in builtin }
            _progress.value = 0 to all.size

            all.forEachIndexed { i, src ->
                when (val v = lightCheck.check(src.id)) {
                    is SourceLightCheck.Verdict.Dead -> {
                        dead.add(src.name + "（" + v.reason + "）")
                        toDelete.add(src.id)
                    }
                    is SourceLightCheck.Verdict.NeedProxy -> {
                        val fails = counter.bump(src.id)
                        val tail = if (proxied) "，连续 " + fails + " 次" else "，当前无代理"
                        needProxy.add(src.name + "（" + v.reason + tail + "）")
                        // 只有挂了代理还连不上才算真有问题；没代理时这一档
                        // 混着「被墙」和「网络差」，删了用户换网络也找不回来
                        if (proxied && fails >= SuspiciousCounter.MAX_SUSPICIOUS) {
                            toDelete.add(src.id)
                        }
                    }
                    SourceLightCheck.Verdict.Alive -> {
                        alive++
                        counter.clear(src.id)
                    }
                    SourceLightCheck.Verdict.Unknown -> skipped++
                }
                _progress.value = (i + 1) to all.size
            }

            // 删之前先存隔离区：删错了还能从「已移除」里恢复
            if (toDelete.isNotEmpty()) {
                val victims = all.filter { it.id in toDelete }
                quarantine.addAll(
                    victims.map { v ->
                        SourceQuarantine.Entry(
                            id = v.id,
                            name = v.name,
                            reason = "自动检测失效",
                            json = v.sourceJson.ifBlank { v.json },
                            removedAt = System.currentTimeMillis(),
                            login = false,
                        )
                    },
                )
                repo.delete(toDelete)
            }
            _report.value = ScanReport(
                total = all.size,
                alive = alive,
                dead = dead,
                needProxy = needProxy,
                removed = toDelete.size,
                skipped = skipped,
            )
        } finally {
            _running.value = false
            _progress.value = null
        }
    }
}
