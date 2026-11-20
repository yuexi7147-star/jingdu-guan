package com.jingdu.guan.ui.player

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放页的状态。
 *
 * 只做播放器要做的事：记住当前地址、播放与否、进度。
 * 真正的解码交给 Media3 —— 别在 ViewModel 里持有 ExoPlayer，它跟 Lifecycle 走，
 * ViewModel 活得比页面久，放进去会泄漏。
 */
class PlayerViewModel : ViewModel() {

    data class UiState(
        val url: String = "",
        val title: String = "",
        val subtitle: String = "",
        /** 竖屏视频：容器按 9:16 排，不铺满整屏留黑边 */
        val portrait: Boolean = false,
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val error: String? = null,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val speed: Float = 1f,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun prepare(url: String, title: String, subtitle: String = "") {
        _state.value = _state.value.copy(url = url, title = title, subtitle = subtitle, error = null)
    }

    fun setPlaying(v: Boolean) {
        _state.value = _state.value.copy(playing = v)
    }

    fun setBuffering(v: Boolean) {
        _state.value = _state.value.copy(buffering = v)
    }

    fun setProgress(pos: Long, dur: Long) {
        _state.value = _state.value.copy(positionMs = pos, durationMs = dur)
    }

    fun setError(msg: String?) {
        _state.value = _state.value.copy(error = msg)
    }

    fun setPortrait(v: Boolean) {
        _state.value = _state.value.copy(portrait = v)
    }

    fun setSpeed(v: Float) {
        _state.value = _state.value.copy(speed = v)
    }

    /** 进度记忆存本地：按地址存，同一集下次接着看 */
    fun rememberPosition(context: android.content.Context, url: String, pos: Long) {
        if (url.isBlank() || pos <= 0) return
        context.getSharedPreferences("player_pos", android.content.Context.MODE_PRIVATE)
            .edit().putLong(url.hashCode().toString(), pos).apply()
    }

    fun lastPosition(context: android.content.Context, url: String): Long {
        if (url.isBlank()) return 0
        return context.getSharedPreferences("player_pos", android.content.Context.MODE_PRIVATE)
            .getLong(url.hashCode().toString(), 0)
    }

    companion object {
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}
