package com.jingdu.guan.ui.player

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.jingdu.guan.ui.components.SecondaryButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 播放页：视频与音频共用。
 *
 * 播放器实例绑在本页 Composition 上（DisposableEffect），不放 ViewModel —— ViewModel 比页面活得久，
 * 把 ExoPlayer 放进去，退出后解码器与 Surface 都还在，是泄漏。
 *
 * 手势照常见播放器的约定：左半屏上下滑调亮度、右半屏调音量、横划调进度；
 * 双击左/右半屏后退/前进 10 秒。手势区只在播放画面上，下面的信息区不拦。
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    val player = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    var hud by remember { mutableStateOf<String?>(null) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    var startPosMs by remember { mutableLongStateOf(0L) }

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

    // 准备并恢复上次进度
    LaunchedEffect(state.url) {
        if (state.url.isNotBlank()) {
            player.setMediaItem(MediaItem.fromUri(state.url))
            player.prepare()
            val last = viewModel.lastPosition(context, state.url)
            if (last > 5_000) player.seekTo(last)
        }
    }

    // 倍速跟状态
    LaunchedEffect(state.speed) { player.setPlaybackSpeed(state.speed) }

    // 进度轮询
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.setProgress(player.currentPosition, player.duration.coerceAtLeast(0))
            kotlinx.coroutines.delay(500)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // ---- 画面 + 手势 ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (state.portrait) Modifier.aspectRatio(9f / 16f) else Modifier.aspectRatio(16f / 9f))
                    .background(Color.Black)
                    .pointerInput(state.url) {
                        detectTapGestures(
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
                            onTap = { if (state.playing) player.pause() else player.play() },
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
                                // 横向为主 → 调进度；否则按落点在左/右调亮度/音量
                                if (kotlin.math.abs(dragAccum) > kotlin.math.abs(drag.y) * 3) {
                                    val dur = player.duration.coerceAtLeast(0)
                                    if (dur > 0) {
                                        // 从拖动起点算总位移：在当前位置上反复加，每帧都会跳得更远
                                        val delta = (dragAccum / w * dur * 0.5).toLong()
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
                contentAlignment = Alignment.Center,
            ) {
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
                    modifier = Modifier.fillMaxSize(),
                )

                if (state.buffering) {
                    Text("缓冲中…", color = Color.White, style = MiuixTheme.textStyles.body2)
                }
                state.error?.let {
                    Text(it, color = Color.White, style = MiuixTheme.textStyles.body2)
                }
                // 手势反馈
                hud?.let {
                    Box(
                        Modifier.background(Color.Black.copy(alpha = 0.65f)).padding(Dimens.gapM),
                    ) {
                        Text(it, color = Color.White, style = MiuixTheme.textStyles.body2)
                    }
                }
            }

            // ---- 信息与控制 ----
            Column(Modifier.padding(Dimens.screenPadding)) {
                Text(
                    state.title.ifBlank { "未命名" },
                    style = MiuixTheme.textStyles.title3,
                    color = Color.White,
                    maxLines = 2,
                )
                if (state.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(Dimens.gapXS))
                    Text(
                        state.subtitle,
                        style = MiuixTheme.textStyles.footnote1,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.height(Dimens.gapM))

                val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
                Text(
                    text = formatTime(state.positionMs) + " / " + formatTime(state.durationMs),
                    style = MiuixTheme.textStyles.footnote1,
                    color = Color.White.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(Dimens.gapXS))
                DeterminateProgressBar(progress = progress)

                Spacer(Modifier.height(Dimens.gapM))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
                ) {
                    SecondaryButton(text = "返回", onClick = onBack)
                    SecondaryButton(
                        text = if (state.playing) "暂停" else "播放",
                        onClick = { if (state.playing) player.pause() else player.play() },
                    )
                    SecondaryButton(
                        text = "后退10",
                        onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) },
                    )
                    SecondaryButton(
                        text = "前进10",
                        onClick = { player.seekTo(player.currentPosition + 10_000) },
                    )
                }

                Spacer(Modifier.height(Dimens.gapS))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
                ) {
                    PlayerViewModel.SPEEDS.forEach { sp ->
                        val on = kotlin.math.abs(state.speed - sp) < 0.01f
                        SecondaryButton(
                            text = if (sp == 1f) "1x" else sp.toString().trimEnd('0').trimEnd('.') + "x",
                            onClick = { viewModel.setSpeed(sp) },
                            enabled = !on,
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
