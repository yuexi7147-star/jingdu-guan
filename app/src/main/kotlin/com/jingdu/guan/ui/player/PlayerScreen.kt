package com.jingdu.guan.ui.player

import android.view.ViewGroup
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.jingdu.guan.ui.components.AppIconButton
import com.jingdu.guan.ui.components.Dimens
import com.jingdu.guan.ui.components.PrimaryButton
import com.jingdu.guan.ui.components.SecondaryButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 播放页：视频与音频共用。
 *
 * 播放器实例绑在本页的 Composition 上（DisposableEffect），不放进 ViewModel —— ViewModel
 * 活得比页面久，把 ExoPlayer 放进去，退出页面后解码器和 Surface 都还在，是泄漏。
 *
 * 竖屏视频（9:16）不铺满整屏：按内容比例排，左右留白；横屏铺满。
 * 比例从 onVideoSizeChanged 拿到真实值，不靠猜。
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = viewModel.setPlaying(isPlaying)
            override fun onPlaybackStateChanged(playbackState: Int) {
                viewModel.setBuffering(playbackState == Player.STATE_BUFFERING)
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                viewModel.setError(error.message ?: "播放失败")
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    viewModel.setPortrait(videoSize.height > videoSize.width)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(state.url) {
        if (state.url.isNotBlank()) {
            player.setMediaItem(MediaItem.fromUri(state.url))
            player.prepare()
        }
    }

    // 进度：每 500ms 读一次
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.setProgress(player.currentPosition, player.duration.coerceAtLeast(0))
            kotlinx.coroutines.delay(500)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // 播放区
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (state.portrait) Modifier.aspectRatio(9f / 16f) else Modifier.aspectRatio(16f / 9f))
                    .background(Color.Black),
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
                            resizeMode = if (state.portrait) AspectRatioFrameLayout.RESIZE_MODE_FIT
                            else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
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
            }

            // 标题区
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

                // 进度
                val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
                Text(
                    text = formatTime(state.positionMs) + " / " + formatTime(state.durationMs),
                    style = MiuixTheme.textStyles.footnote1,
                    color = Color.White.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(Dimens.gapXS))
                com.jingdu.guan.ui.components.DeterminateProgressBar(progress = progress)

                Spacer(Modifier.height(Dimens.gapM))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.gapM),
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
