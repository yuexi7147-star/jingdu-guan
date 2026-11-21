package com.jingdu.guan.data.repo

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 订阅源自动检查：启动时看距上次多久，到点就跑一轮。
 *
 * 照鲸读的 SourceAutoCheck 做（那套没上 WorkManager：WorkManager 依赖旧版 Room，
 * 和本项目的 Room 3 在启动初始化阶段冲突，装上直接闪退）。
 *
 * 代价是「不打开 App 就不检查」，但探活只要几分钟，天天都会开一次，够用。
 */
class SourceAutoCheck(
    private val context: Context,
    private val repo: RssRepository,
    private val lightCheck: SourceLightCheck,
    private val counter: SuspiciousCounter,
    private val scan: RssSourceScan,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("rss_auto_check", Context.MODE_PRIVATE)

    /** 自动检查开关默认关：删源是不可逆操作，先让用户自己点一次看看效果 */
    val enabled: StateFlow<Boolean> = MutableStateFlow(prefs.getBoolean("enabled", false))

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        (enabled as MutableStateFlow).value = on
    }

    /** 间隔天数（默认 3 天） */
    fun intervalDays(): Int = prefs.getInt("interval_days", 3)
    fun setIntervalDays(d: Int) = prefs.edit().putInt("interval_days", d.coerceIn(1, 30)).apply()

    fun lastRunAt(): Long = prefs.getLong("last_run_at", 0)

    /** 启动后调一次：内部自己判断该不该跑 */
    fun onAppStart() {
        if (!enabled.value) return
        scope.launch {
            // 让开启动时的正事（书架、数据库迁移都抢这几秒）
            delay(8_000)
            val since = System.currentTimeMillis() - lastRunAt()
            if (since < intervalDays() * 24L * 3600_000L) return@launch
            scan.run()
            prefs.edit().putLong("last_run_at", System.currentTimeMillis()).apply()
        }
    }
}
