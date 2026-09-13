package com.deepseekbuddy.app.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.deepseekbuddy.app.util.MediaFiles
import kotlin.math.max

/**
 * 圆形头像裁剪器：圆形取景框内拖动 + 双指缩放，
 * 确认后按取景框内容裁出正方形图片保存，返回新文件路径。
 */
@Composable
fun AvatarCropperScreen(
    imagePath: String,
    onConfirm: (croppedPath: String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap = remember(imagePath) { MediaFiles.readBitmap(imagePath, 2048) }
    val viewportSizeDp = 260.dp
    val viewportPx = with(LocalDensity.current) { viewportSizeDp.roundToPx().toFloat() }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    if (bitmap == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("图片加载失败", color = Color.White)
        }
        return
    }

    // 初始铺满圆形取景框的基准缩放
    val baseScale = max(
        viewportPx / bitmap.width.toFloat(),
        viewportPx / bitmap.height.toFloat(),
    )

    fun clampOffset() {
        val fullScale = baseScale * scale
        val scaledW = bitmap.width * fullScale
        val scaledH = bitmap.height * fullScale
        val maxX = (scaledW - viewportPx) / 2f
        val maxY = (scaledH - viewportPx) / 2f
        offset = Offset(
            offset.x.coerceIn(-maxX, maxX),
            offset.y.coerceIn(-maxY, maxY),
        )
    }

    fun finish() {
        val fullScale = baseScale * scale
        // 视口中心对应的位图坐标：位图中心 - 平移量/fullScale
        // （注意不能用视口尺寸换算，否则图片大于视口时结果会偏移）
        val centerX = bitmap.width / 2f - offset.x / fullScale
        val centerY = bitmap.height / 2f - offset.y / fullScale
        val side = (viewportPx / fullScale)
            .coerceAtMost(bitmap.width.toFloat())
            .coerceAtMost(bitmap.height.toFloat())
        val left = (centerX - side / 2f).coerceIn(0f, bitmap.width - side)
        val top = (centerY - side / 2f).coerceIn(0f, bitmap.height - side)
        val cropped = Bitmap.createBitmap(bitmap, left.toInt(), top.toInt(), side.toInt(), side.toInt())
        val path = MediaFiles.saveBitmap(context, "avatar_crop_${System.currentTimeMillis()}.jpg", cropped)
        if (path != null) onConfirm(path)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "拖动调整位置 · 双指缩放",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            // 圆形取景框（可拖动/缩放）
            Box(
                modifier = Modifier
                    .padding(vertical = 24.dp)
                    .size(viewportSizeDp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .graphicsLayer {
                        translationX = offset.x
                        translationY = offset.y
                        scaleX = scale
                        scaleY = scale
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 4f)
                            offset += pan
                            clampOffset()
                        }
                    },
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onCancel) { Text("取消") }
                Button(onClick = ::finish) { Text("完成") }
            }
        }
    }
}
