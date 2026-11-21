package com.jingdu.guan.data.repo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置订阅源：装机自带几个能用的源，省得用户第一次打开面对空列表。
 *
 * 三条约束：
 * 1. **只导一次**：用 SharedPreferences 记标记。每次启动都导会把用户删掉的源又加回来，
 *    那就是「我明明删了它怎么又出现了」—— 比不带内置源更烦人。
 * 2. **失败不拦启动**：源文件坏了、库写不进去，都只记日志，App 照常开。
 * 3. **不覆盖用户已有**：导入走 [RssRepository.import]，它自己会判重（同地址算一个）。
 */
object BuiltinSources {

    private const val PREF = "builtin_sources"
    private const val KEY_DONE = "imported_v1"

    /** 放在 assets/rss_builtin 下的源文件，按名字排 */
    private val FILES = listOf("missav.json")

    suspend fun importOnce(context: Context, repo: RssRepository) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return@withContext

        var ok = 0
        for (name in FILES) {
            val text = runCatching {
                context.assets.open("rss_builtin/" + name).bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue

            runCatching { repo.import(text) }
                .onSuccess { if (it.added > 0) ok++ }
                .onFailure { /* 单个源坏了不影响别的 */ }
        }

        // 一个都没导成也记标记：文件是装机自带的，重试的结果不会变，别每次启动都白跑一遍
        prefs.edit().putBoolean(KEY_DONE, true).apply()
        ok
    }
}
