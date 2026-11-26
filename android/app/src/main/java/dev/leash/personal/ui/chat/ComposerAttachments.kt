package dev.leash.personal.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.FileLimits
import dev.leash.personal.core.PendingFile
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.Spinner
import dev.leash.personal.ui.design.medium
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.files.FileKinds
import dev.leash.personal.ui.files.icon

/**
 * What waits in the composer: photo thumbnails (56 dp) and picked files (ios ComposerFileChip, 168 x 56), each with a remove
 * cross; files grey out with a spinner while they upload.
 */
@Composable
fun ComposerAttachments(
    images: List<ByteArray>,
    files: List<PendingFile>,
    uploading: Boolean,
    onRemoveImage: (Int) -> Unit,
    onRemoveFile: (PendingFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp, end = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEachIndexed { i, data ->
            val bmp = remember(data) { decodeImage(data, 256) }
            if (bmp != null) Box(Modifier.size(56.dp)) {
                Image(bmp.asImageBitmap(), null, Modifier.size(56.dp).clip(LeashShapes.row), contentScale = ContentScale.Crop)
                RemoveCross(stringResource(R.string.remove_photo), uploading = false) { onRemoveImage(i) }
            }
        }
        files.forEach { f -> FileTile(f, uploading) { onRemoveFile(f) } }
    }
}

@Composable
private fun FileTile(file: PendingFile, uploading: Boolean, remove: () -> Unit) {
    val c = Leash.colors
    Box(Modifier.width(168.dp).height(56.dp)) {
        Row(
            Modifier.width(168.dp).height(56.dp).clip(LeashShapes.row).background(c.controlFill).alpha(if (uploading) 0.5f else 1f).padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(FileKinds.glyph(file.name).icon(), null, Modifier.size(26.dp), tint = c.accent)
            Column(Modifier.weight(1f)) {
                Text(file.name, style = Leash.type.footnote.medium(), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(FileLimits.sizeText(file.size.toLong()), style = Leash.type.caption2, color = c.textSecondary)
            }
        }
        if (uploading) Box(Modifier.width(168.dp).height(56.dp), contentAlignment = Alignment.Center) { Spinner(size = 20.dp) }
        else RemoveCross(stringResource(R.string.remove_file, file.name), uploading = false, onClick = remove)
    }
}

/** The × on a thumbnail's corner: a 20 dp disc in a 44 dp touch target that leans into the corner. */
@Composable
private fun BoxScope.RemoveCross(label: String, uploading: Boolean, onClick: () -> Unit) {
    if (uploading) return
    Box(
        Modifier.align(Alignment.TopEnd).size(44.dp)
            .pressable(onClickLabel = label, onClick = onClick).padding(start = 22.dp, bottom = 22.dp).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Icon(LeashIcons.close, null, Modifier.size(12.dp), tint = Color.White)
        }
    }
}
