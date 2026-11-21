package com.jingdu.guan.ui.player

import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.jingdu.guan.ui.components.Dimens
import com.jingdu.guan.ui.components.DeterminateProgressBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 播放页。
 *
 * 布局照常见播放器：**画面占满整屏，控制条浮在画面上**。
 * 之前把控制区放在视频下方，横屏时被挤出屏幕 —— 那是个结构性错误，不是样式问题。
 *
 * 全屏是真全屏：切横屏 + 隐藏系统栏，不是靠用户自己开方向锁定。
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity

    val player = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    var hud by remember { mutableStateOf<String?>(null) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    var startPosMs by remember { mutableLongStateOf(0L) }
    var controlsShown by remember { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }

    // 全屏：切横屏 + 隐藏系统栏；退出时恢复
    DisposableEffect(fullscreen) {
        val w = activity?.window
        val controller = w?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (fullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = viewModel.setPlaying(isPlaying)
            override fun onPlaybackStateChanged(playbackState: Int) {
                viewModel.setBuffering(playbackState == Player.STATE_BUFFERING)
            }
            override fun onPlayerError(error: PlaybackException) {
                viewModel.setError(error.message ?: "播放失败")
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    viewModel.setPortrait(videoSize.height > videoSize.width)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            viewModel.rememberPosition(context, state.url, player.currentPosition)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(state.url, state.referer) {
        if (state.url.isNotBlank()) {
            val ref = state.referer
            val headers: Map<String, String> =
                if (!ref.isNullOrBlank()) mapOf("Referer" to ref) else emptyMap()
            val ds = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 14)")
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
            val item = MediaItem.fromUri(state.url)
            val isHls = state.url.contains(".m3u8", ignoreCase = true)
            val source = if (isHls) {
                androidx.media3.exoplayer.hls.HlsMediaSource.Factory(ds).createMediaSource(item)
            } else {
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(ds).createMediaSource(item)
            }
            player.setMediaSource(source)
            player.prepare()
            val last = viewModel.lastPosition(context, state.url)
            if (last > 5_000) player.seekTo(last)
        }
    }

    LaunchedEffect(state.speed) { player.setPlaybackSpeed(state.speed) }

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.setProgress(player.currentPosition, player.duration.coerceAtLeast(0))
            kotlinx.coroutines.delay(500)
        }
    }

    // 播放中 3 秒无操作收起控制条
    LaunchedEffect(controlsShown, state.playing) {
        if (controlsShown && state.playing) {
            kotlinx.coroutines.delay(3000)
            controlsShown = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                // 音量键调播放音量：视频里最顺手的调音量方式，别让人去划屏幕
                if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                    when (ev.key) {
                        androidx.compose.ui.input.key.Key.VolumeUp -> {
                            player.volume = (player.volume + 0.1f).coerceIn(0f, 1f)
                            hud = "音量 " + (player.volume * 100).toInt() + "%"
                            true
                        }
                        androidx.compose.ui.input.key.Key.VolumeDown -> {
                            player.volume = (player.volume - 0.1f).coerceIn(0f, 1f)
                            hud = "音量 " + (player.volume * 100).toInt() + "%"
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .focusable(),
    ) {
        // ---- 画面：铺满整屏 ----
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.url) {
                    detectTapGestures(
                        onTap = { controlsShown = !controlsShown },
                        onDoubleTap = { offset ->
                            val half = size.width / 2f
                            if (offset.x < half) {
                                player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                                hud = "« 10 秒"
                            } else {
                                player.seekTo(player.currentPosition + 10_000)
                                hud = "10 秒 »"
                            }
                        },
                    )
                }
                .pointerInput(state.url) {
                    detectDragGestures(
                        onDragStart = { dragAccum = 0f; startPosMs = player.currentPosition },
                        onDragEnd = { hud = null },
                        onDrag = { change, drag ->
                            change.consume()
                            dragAccum += drag.x
                            val w = size.width.coerceAtLeast(1)
                            val h = size.height.coerceAtLeast(1)
                            if (kotlin.math.abs(dragAccum) > SEEK_START_SLOP && kotlin.math.abs(dragAccum) > kotlin.math.abs(drag.y) * 2.5) {
                                val dur = player.duration.coerceAtLeast(0)
                                if (dur > 0) {
                                    val delta = (dragAccum / w * dur * SEEK_SENSITIVITY).toLong()
                                    val target = (startPosMs + delta).coerceIn(0, dur)
                                    player.seekTo(target)
                                    hud = formatTime(target)
                                }
                            } else {
                                val left = change.position.x < w / 2
                                val d = -drag.y / h
                                if (left) {
                                    val cur = activity?.window?.attributes?.screenBrightness ?: 0.5f
                                    val next = (cur + d).coerceIn(0.05f, 1f)
                                    activity?.window?.attributes = activity?.window?.attributes?.apply {
                                        screenBrightness = next
                                    }
                                    hud = "亮度 " + (next * 100).toInt() + "%"
                                } else {
                                    val am = context.getSystemService(android.content.Context.AUDIO_SERVICE)
                                        as android.media.AudioManager
                                    val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                                    val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                                    val next = (cur + (d * max).toInt()).coerceIn(0, max)
                                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, next, 0)
                                    hud = "音量 " + (next * 100 / max) + "%"
                                }
                            }
                        },
                    )
                },
        )

        if (state.buffering) {
            Text(
                "缓冲中…",
                color = Color.White,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        state.error?.let {
            Text(
                it,
                color = Color.White,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.align(Alignment.Center).padding(Dimens.screenPadding),
            )
        }

        // ---- 控制条：浮在画面上 ----
        AnimatedVisibility(
            visible = controlsShown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(Dimens.gapM),
            ) {
                // 标题
                Text(
                    state.title.ifBlank { "视频" },
                    style = MiuixTheme.textStyles.body1,
                    color = Color.White,
                    maxLines = 1,
                )
                Spacer(Modifier.height(Dimens.gapS))

                // 进度
                DeterminateProgressBar(
                    progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
                )
                Spacer(Modifier.height(Dimens.gapXS))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        formatTime(state.positionMs) + " / " + formatTime(state.durationMs),
                        style = MiuixTheme.textStyles.footnote1,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Text(
                        if (state.speed == 1f) "1x" else formatSpeed(state.speed),
                        style = MiuixTheme.textStyles.footnote1,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }

                Spacer(Modifier.height(Dimens.gapS))

                // 主控：一行的图标
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CtrlButton("返回") { onBack() }
                    CtrlButton("-10") {
                        player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                    }
                    CtrlButton(if (state.playing) "暂停" else "播放") {
                        if (state.playing) player.pause() else player.play()
                    }
                    CtrlButton("+10") { player.seekTo(player.currentPosition + 10_000) }
                    CtrlButton(if (fullscreen) "退出" else "全屏") { fullscreen = !fullscreen }
                }

                Spacer(Modifier.height(Dimens.gapS))

                // 倍速：一排小字
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    PlayerViewModel.SPEEDS.forEach { sp ->
                        val on = kotlin.math.abs(state.speed - sp) < 0.01f
                        Text(
                            text = formatSpeed(sp),
                            style = MiuixTheme.textStyles.footnote2,
                            color = if (on) MiuixTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier
                                .clickable { viewModel.setSpeed(sp) }
                                .padding(horizontal = Dimens.gapS, vertical = Dimens.gapXS),
                        )
                    }
                }
            }
        }

        // 手势 HUD
        hud?.let {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = Dimens.gapXXL)
                    .background(Color.Black.copy(alpha = 0.7f), androidx.compose.foundation.shape.RoundedCornerShape(Dimens.gapM))
                    .padding(horizontal = Dimens.gapL, vertical = Dimens.gapS),
            ) {
                Text(it, color = Color.White, style = MiuixTheme.textStyles.body2)
            }
        }
    }
}

@Composable
private fun CtrlButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MiuixTheme.textStyles.footnote1,
        color = Color.White,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.gapS, vertical = Dimens.gapS),
    )
}


/** 横划调进度的灵敏度：拖满整屏 = 半个视频长度。再高就一碰就飞，太低又拖不动 */
private const val SEEK_SENSITIVITY = 0.32f

/** 横划多少像素才开始调进度：太小会把竖划的抖动误判成横划 */
private const val SEEK_START_SLOP = 24f

private fun formatSpeed(sp: Float): String {
    val s = sp.toString().trimEnd('0').trimEnd('.')
    return s + "x"
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
