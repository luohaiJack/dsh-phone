package com.dsh.phone.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.phone.MessageAttachment
import com.dsh.phone.shortFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Ready(val bitmap: ImageBitmap) : ImageLoadState
    data class Failed(val message: String) : ImageLoadState
}

/**
 * 按目标像素数采样解码。32 MB 的压缩图片可能展开成几百 MB，绝不能直接 decodeFile。
 */
private fun decodeSampled(path: String, maxDimension: Int): ImageBitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IllegalArgumentException("不是可识别的位图（SVG 等格式请用其他应用打开）")
    }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDimension || bounds.outHeight / (sample * 2) >= maxDimension) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return (BitmapFactory.decodeFile(path, options) ?: throw IllegalArgumentException("图片解码失败"))
        .asImageBitmap()
}

@Composable
private fun rememberAttachmentImage(
    attachment: MessageAttachment,
    loadPreview: suspend (MessageAttachment) -> String,
    maxDimension: Int,
    retry: Int = 0,
): ImageLoadState = produceState<ImageLoadState>(initialValue = ImageLoadState.Loading, attachment.stableId, maxDimension, retry) {
    value = try {
        val bitmap = withContext(Dispatchers.IO) {
            decodeSampled(loadPreview(attachment), maxDimension)
        }
        ImageLoadState.Ready(bitmap)
    } catch (e: Exception) {
        ImageLoadState.Failed(e.message ?: "图片加载失败")
    }
}.value

/** 会话消息里的一组附件：图片给缩略图，其余给可点击的文件卡。 */
@Composable
fun MessageAttachmentList(
    attachments: List<MessageAttachment>,
    onOpen: (MessageAttachment) -> Unit,
    loadPreview: suspend (MessageAttachment) -> String,
) {
    attachments.forEachIndexed { index, attachment ->
        if (index > 0) Spacer(Modifier.height(6.dp))
        if (attachment.isImage) {
            ImageAttachmentCard(attachment, onOpen, loadPreview)
        } else {
            FileAttachmentCard(attachment, onOpen)
        }
    }
}

@Composable
private fun ImageAttachmentCard(
    attachment: MessageAttachment,
    onOpen: (MessageAttachment) -> Unit,
    loadPreview: suspend (MessageAttachment) -> String,
) {
    var retry by remember(attachment.stableId) { mutableStateOf(0) }
    val state = rememberAttachmentImage(attachment, loadPreview, maxDimension = 1024, retry = retry)
    val ratio = if (attachment.width > 0 && attachment.height > 0) {
        (attachment.width.toFloat() / attachment.height.toFloat()).coerceIn(0.65f, 1.8f)
    } else 4f / 3f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpen(attachment) },
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                when (val s = state) {
                    ImageLoadState.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("图片加载中…", style = MaterialTheme.typography.labelSmall)
                    }
                    is ImageLoadState.Ready -> Image(
                        bitmap = s.bitmap,
                        contentDescription = attachment.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    is ImageLoadState.Failed -> Column(
                        Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("图片加载失败", style = MaterialTheme.typography.labelLarge)
                        Text(
                            s.message,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        FilledTonalButton(onClick = { retry += 1 }) { Text("重试") }
                    }
                }
            }
            AttachmentCaption(attachment)
        }
    }
}

@Composable
private fun AttachmentCaption(attachment: MessageAttachment) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
        Text(
            shortFileName(attachment.name),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val detail = buildList {
            if (attachment.sizeText.isNotBlank()) add(attachment.sizeText)
            if (attachment.width > 0 && attachment.height > 0) add("${attachment.width}×${attachment.height}")
            if (!attachment.description.isNullOrBlank()) add(attachment.description)
        }.joinToString(" · ")
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FileAttachmentCard(attachment: MessageAttachment, onOpen: (MessageAttachment) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpen(attachment) },
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📄", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    shortFileName(attachment.name),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = buildList {
                    if (attachment.sizeText.isNotBlank()) add(attachment.sizeText)
                    add(if (attachment.isText) "点击查看" else "点击预览 / 打开")
                    if (!attachment.description.isNullOrBlank()) add(attachment.description)
                }.joinToString(" · ")
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** 文档页的全屏图片：双指缩放 / 拖动，双击复位。 */
@Composable
fun FullScreenAttachmentImage(
    attachment: MessageAttachment,
    localPath: String,
) {
    var retry by remember(attachment.stableId) { mutableStateOf(0) }
    val state = produceState<ImageLoadState>(initialValue = ImageLoadState.Loading, localPath, retry) {
        value = try {
            ImageLoadState.Ready(withContext(Dispatchers.IO) { decodeSampled(localPath, 2048) })
        } catch (e: Exception) {
            ImageLoadState.Failed(e.message ?: "图片解码失败")
        }
    }.value
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        when (val s = state) {
            ImageLoadState.Loading -> CircularProgressIndicator()
            is ImageLoadState.Failed -> Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("无法显示这张图片", color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(s.message, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = { retry += 1 }) { Text("重试") }
            }
            is ImageLoadState.Ready -> {
                var scale by remember(localPath) { mutableFloatStateOf(1f) }
                var offset by remember(localPath) { mutableStateOf(Offset.Zero) }
                Image(
                    bitmap = s.bitmap,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(localPath) {
                            detectTapGestures(
                                onDoubleTap = {
                                    scale = 1f
                                    offset = Offset.Zero
                                },
                            )
                        }
                        .pointerInput(localPath) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offset = if (scale <= 1f) Offset.Zero else {
                                    Offset(
                                        (offset.x + pan.x).coerceIn(-4000f, 4000f),
                                        (offset.y + pan.y).coerceIn(-4000f, 4000f),
                                    )
                                }
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
                Text(
                    "双指缩放 · 双击复位",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(Color(0x88000000))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/** 非文本文件的已下载状态与「外部打开」入口。 */
@Composable
fun DownloadedFilePanel(
    name: String,
    sourcePath: String,
    description: String?,
    localPath: String,
    onOpenExternal: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📄", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text(shortFileName(name, 40), style = MaterialTheme.typography.titleMedium)
        if (sourcePath.isNotBlank() && sourcePath != name) {
            Spacer(Modifier.height(6.dp))
            Text(
                sourcePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "文件已取到手机私有缓存。此类型不能在 App 内直接显示，可交给系统里支持它的应用打开。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        FilledTonalButton(onClick = { onOpenExternal(localPath) }) {
            Text("用其他应用打开")
        }
    }
}
