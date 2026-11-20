package com.jingdu.guan.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 可拖动的悬浮球。
 *
 * 为什么不用系统级悬浮窗：那要 SYSMET_ALERT_WINDOW 权限、会在所有 App 之上飘，
 * 这里只是网页页里一个顺手的手势入口，页内浮层就够，也不打扰别处。
 *
 * 手势照辅助触控的直觉：单击主操作、长按出菜单、可以拖着挪开挡住的文字。
 */
@Composable
fun FloatingBall(
    count: Int,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var offset by remember { mutableStateOf(Offset(0f, 0f)) }
    var dragAccum by remember { mutableStateOf(0f) }

    Box(
        modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    dragAccum += kotlin.math.abs(drag.x) + kotlin.math.abs(drag.y)
                    offset = Offset(offset.x + drag.x, offset.y + drag.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (count > 0) {
            Text(
                text = count.toString(),
                style = MiuixTheme.textStyles.body1,
                color = Color.White,
            )
        } else {
            Text(
                text = "嗅",
                style = MiuixTheme.textStyles.body1,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}
