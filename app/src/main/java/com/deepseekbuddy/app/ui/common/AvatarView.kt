package com.deepseekbuddy.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.deepseekbuddy.app.util.MediaFiles

/** 通用头像渲染：emoji（含旧版无前缀）或本地图片；可选点击 */
@Composable
fun AvatarView(
    ref: String,
    size: Dp,
    fontSize: TextUnit = 22.sp,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val isFile = ref.startsWith("file:")
        if (!isFile) {
            Text(ref.removePrefix("emoji:").ifEmpty { "🙂" }, fontSize = fontSize)
        } else {
            val path = MediaFiles.pathFromRef(ref)
            val bitmap = remember(path) { path?.let { MediaFiles.readBitmap(it, 512) } }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text("🙂", fontSize = fontSize)
            }
        }
    }
}
