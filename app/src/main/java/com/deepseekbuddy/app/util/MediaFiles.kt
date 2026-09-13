package com.deepseekbuddy.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 图片导入与读取（图库选图 → 拷贝到 App 私有目录，免权限困扰） */
object MediaFiles {

    private fun mediaDir(context: Context): File =
        File(context.filesDir, "media").apply { mkdirs() }

    /** 把图库 uri 拷贝到私有目录，返回绝对路径；失败返回 null */
    suspend fun importToInternal(context: Context, uri: Uri, fileName: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = File(mediaDir(context), fileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                target.absolutePath
            }.getOrNull()
        }

    /** 解码图片并采样压缩（限制最大边长，防 OOM） */
    fun readBitmap(path: String, maxDim: Int = 1600): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** "emoji:🐱" → emoji；否则 null */
    fun emojiFromRef(ref: String): String? =
        ref.takeIf { it.startsWith("emoji:") }?.removePrefix("emoji:")

    /** "file:/path" → 路径；否则 null */
    fun pathFromRef(ref: String): String? =
        ref.takeIf { it.startsWith("file:") }?.removePrefix("file:")

    /** 保存裁剪结果到私有目录 */
    fun saveBitmap(context: Context, fileName: String, bitmap: Bitmap): String? = runCatching {
        val target = File(mediaDir(context), fileName)
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        target.absolutePath
    }.getOrNull()

    fun deleteQuietly(path: String?) {
        runCatching { path?.let { File(it).delete() } }
    }
}
