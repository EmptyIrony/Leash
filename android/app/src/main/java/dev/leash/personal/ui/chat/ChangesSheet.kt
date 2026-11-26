package dev.leash.personal.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import dev.leash.personal.R
import dev.leash.personal.core.TurnChanges
import dev.leash.personal.ui.components.pluralText
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.Motion
import dev.leash.personal.ui.design.medium
import dev.leash.personal.ui.design.tabular
import dev.leash.personal.ui.design.components.ButtonSize
import dev.leash.personal.ui.design.components.ButtonStyle
import dev.leash.personal.ui.design.components.GroupCard
import dev.leash.personal.ui.design.components.GroupRow
import dev.leash.personal.ui.design.components.Hairline
import dev.leash.personal.ui.design.components.LeashButton
import dev.leash.personal.ui.design.components.LeashSheet

/**
 * A turn's changes file by file, each opening to its diffs (ios ChangesSheet). A sheet over the conversation, so an approval
 * arriving meanwhile still has its tray under it. Commit (only on the newest turn that changed files) asks the agent in the
 * conversation; its own approval still gates the real git commit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangesSheet(changes: TurnChanges, cwd: String?, onDismiss: () -> Unit, onCommit: (() -> Unit)?, commitEnabled: Boolean) {
    val c = Leash.colors
    var expanded by remember { mutableStateOf(if (changes.files.size <= 3) changes.files.map { it.path }.toSet() else emptySet()) }
    LeashSheet(
        onDismiss = onDismiss, title = stringResource(R.string.changes_title),
        doneLabel = stringResource(R.string.done), onDone = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralText(R.plurals.files_count, changes.files.size), style = Leash.type.footnote, color = c.textSecondary)
                Text("+" + changes.adds, style = Leash.type.monoSmall.tabular(), color = c.success)
                Text("−" + changes.dels, style = Leash.type.monoSmall.tabular(), color = c.danger, modifier = Modifier.weight(1f))
                if (onCommit != null) LeashButton(stringResource(R.string.commit), onCommit, style = ButtonStyle.Tinted, size = ButtonSize.Regular, enabled = commitEnabled)
            }
            GroupCard {
                changes.files.forEachIndexed { i, f ->
                    val open = f.path in expanded
                    val rot by animateFloatAsState(if (open) 90f else 0f, Motion.disclose(), label = "chevron")
                    val rel = f.relative(cwd)
                    val dir = rel.substringBeforeLast('/', "")
                    val name = rel.substringAfterLast('/')
                    val kind = when { f.deleted -> stringResource(R.string.change_deleted); f.created -> stringResource(R.string.change_new); else -> null }
                    GroupRow(
                        last = true, minHeight = 56.dp,
                        onClick = { expanded = if (open) expanded - f.path else expanded + f.path },
                        modifier = Modifier.semantics { if (kind != null) contentDescription = kind + " " + name },
                    ) {
                        if (f.deleted) Icon(LeashIcons.delete, null, Modifier.size(18.dp), tint = c.danger)
                        else if (f.created) Icon(LeashIcons.newFile, null, Modifier.size(18.dp), tint = c.success)
                        else Icon(LeashIcons.edit, null, Modifier.size(18.dp), tint = c.textTertiary)
                        Column(Modifier.weight(1f)) {
                            Text(name, style = Leash.type.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (dir.isNotEmpty()) Text(dir, style = Leash.type.footnote, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                        }
                        if (f.adds + f.dels > 0) ChangeCounts(f.adds, f.dels)
                        Icon(LeashIcons.chevronRight, null, Modifier.size(18.dp).rotate(rot), tint = c.textTertiary)
                    }
                    AnimatedVisibility(open) {
                        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val blocks = f.tools.flatMap { it.diffs }
                            if (blocks.isEmpty()) Text(
                                stringResource(if (f.deleted) R.string.change_deleted else R.string.change_no_diff),
                                Modifier.padding(horizontal = 4.dp), style = Leash.type.footnote, color = c.textSecondary,
                            )
                            blocks.forEach { d -> DiffBlock(d.path, d.old, d.new, maxLines = 400, wholeFile = true) }
                        }
                    }
                    if (i != changes.files.lastIndex) Hairline(Modifier.padding(start = 46.dp, end = 16.dp))
                }
            }
        }
    }
}
