package com.deepseekbuddy.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.deepseekbuddy.app.util.MediaFiles

/**
 * 群头像：圆形框内按 2x2 四宫格合成成员头像。
 * - 不足 4 人：右下角空白
 * - 超过 4 人：调用方已按姓名首字母排序取前 4
 */
@Composable
fun GroupAvatar(avatars: List<String>, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (avatars.isEmpty()) {
            Text("👥", fontSize = (size.value / 2.2f).sp)
        } else {
            val quadrants = avatars.take(4)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    QuadrantAvatar(quadrants.getOrNull(0), size)
                    QuadrantAvatar(quadrants.getOrNull(1), size)
                }
                Row(Modifier.weight(1f)) {
                    QuadrantAvatar(quadrants.getOrNull(2), size)
                    // 第 4 格：不足 4 人时留空
                    if (quadrants.size >= 4) QuadrantAvatar(quadrants[3], size) else Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RowScope.QuadrantAvatar(ref: String?, containerSize: Dp) {
    Box(
        modifier = Modifier.weight(1f).fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (ref.isNullOrEmpty()) return@Box
        val isFile = ref.startsWith("file:")
        if (isFile) {
            val path = MediaFiles.pathFromRef(ref)
            val bitmap = remember(path) { path?.let { MediaFiles.readBitmap(it, 256) } }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text("🙂", fontSize = (containerSize.value / 3.6f).sp)
            }
        } else {
            Text(
                ref.removePrefix("emoji:").ifEmpty { "🙂" },
                fontSize = (containerSize.value / 3.6f).sp,
            )
        }
    }
}
