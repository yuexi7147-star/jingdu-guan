package com.jingdu.guan.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 启动动画：「豆包偷偷看视频」。
 *
 * 三拍，总共 1.2 秒 —— 再长就变成等它了：
 * 1. 豆子弹性放大（0-350ms）：用 spring 而不是 tween，收尾时那一点点回弹是这个角色的性格
 * 2. 眼睛睁开 + 眼珠往右斜（350-600ms）：它在「偷看」，眼神必须是斜的，正视就成了吉祥物
 * 3. 屏幕亮起 + 整体淡出（600-1200ms）
 *
 * 全自绘，不加 Lottie 那几 MB —— 就三块形状，Canvas 足够。
 */
@Composable
fun SplashScreen(onFinish: () -> Unit) {
    val scale = remember { Animatable(0.55f) }
    val eyeOpen = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        // 1. 弹出
        scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f))
        // 2. 睁眼（偷看）
        eyeOpen.animateTo(1f, tween(230, easing = FastOutSlowInEasing))
        // 3. 屏幕亮起
        glow.animateTo(1f, tween(260))
        delay(120)
        // 4. 淡出
        fade.animateTo(0f, tween(320))
        onFinish()
    }

    val ink = MiuixTheme.colorScheme.onSurface
    val accent = MiuixTheme.colorScheme.primary

    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(120.dp).then(Modifier)) {
            val s = scale.value
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension * 0.36f * s
            val alpha = fade.value

            // 豆子本体
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = r,
                center = Offset(cx, cy),
            )

            // 右侧的「视频屏幕」：一块圆角矩形，亮起时变成强调色
            val screenW = r * 0.92f
            val screenH = r * 0.66f
            val sx = cx + r * 0.30f
            val sy = cy + r * 0.06f
            val screenColor = if (glow.value > 0f) {
                Color(0xFF4A9EFF).copy(alpha = alpha * (0.35f + 0.65f * glow.value))
            } else {
                Color(0xFFBFC6D1).copy(alpha = alpha)
            }
            drawRoundRect(
                color = screenColor,
                topLeft = Offset(sx - screenW / 2f, sy - screenH / 2f),
                size = Size(screenW, screenH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.12f),
            )

            // 屏幕中间的播放三角
            if (glow.value > 0.1f) {
                val t = glow.value
                val tri = Path().apply {
                    moveTo(sx - screenW * 0.10f, sy - screenH * 0.20f * t)
                    lineTo(sx - screenW * 0.10f, sy + screenH * 0.20f * t)
                    lineTo(sx + screenW * 0.16f * t, sy)
                    close()
                }
                drawPath(tri, Color.White.copy(alpha = alpha * t))
            }

            // 眼睛：两个点，睁开时从「一条线」变成「椭圆」，并往右斜（偷看）
            val eo = eyeOpen.value
            val eyeY = cy - r * 0.22f
            val lookX = r * 0.14f * eo
            listOf(cx - r * 0.26f, cx + r * 0.02f).forEach { ex ->
                if (eo < 0.05f) {
                    // 闭眼：一条短线
                    drawLine(
                        color = ink.copy(alpha = alpha * 0.75f),
                        start = Offset(ex - r * 0.09f, eyeY),
                        end = Offset(ex + r * 0.09f, eyeY),
                        strokeWidth = r * 0.055f,
                    )
                } else {
                    val eh = r * 0.16f * eo
                    val ew = r * 0.10f * eo
                    drawOval(
                        color = ink.copy(alpha = alpha * 0.85f),
                        topLeft = Offset(ex + lookX - ew / 2f, eyeY - eh / 2f),
                        size = Size(ew, eh),
                    )
                }
            }

            // 偷看时嘴角一点点上扬（一条弧）
            if (eo > 0.4f) {
                val arc = Path().apply {
                    moveTo(cx - r * 0.10f, cy + r * 0.30f)
                    quadraticBezierTo(
                        cx + r * 0.04f, cy + r * 0.38f,
                        cx + r * 0.18f, cy + r * 0.28f,
                    )
                }
                drawPath(arc, ink.copy(alpha = alpha * 0.55f), style = Stroke(width = r * 0.05f))
            }
        }
    }
}
