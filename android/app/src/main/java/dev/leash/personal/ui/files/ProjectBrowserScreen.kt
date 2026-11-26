package dev.leash.personal.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.BrowseResult
import dev.leash.personal.ui.components.ActivitySpinner
import dev.leash.personal.ui.theme.LeashTheme

/** A conversation's folder on the agent's host, Files-style: each folder is a screen on the stack (ios ProjectBrowser). Files list only; the viewer arrives in phase 4. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectFolderScreen(model: AppModel, agent: String, path: String, onBack: () -> Unit, onOpenFolder: (String) -> Unit) {
    var result by remember(path) { mutableStateOf<BrowseResult?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        try { result = model.browse(path, agent) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: e.toString() }
    }
    val r = result
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (r != null) LazyColumn(Modifier.fillMaxSize()) {
                items(r.dirs, key = { "d:$it" }) { d ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenFolder(path.trimEnd('/') + "/" + d) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(14.dp))
                        Text(d, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LeashTheme.colors.textTertiary)
                    }
                }
                items(r.files, key = { "f:$it" }) { f ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.InsertDriveFile, null, tint = LeashTheme.colors.textTertiary)
                        Spacer(Modifier.width(14.dp))
                        Text(f, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            when {
                error != null -> Message(Icons.Filled.FolderOff, stringResource(R.string.cant_open_folder), error)
                r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ActivitySpinner(28.dp) }
                r.dirs.isEmpty() && r.files.isEmpty() -> Message(Icons.Filled.Folder, stringResource(R.string.empty_folder), null)
            }
        }
    }
}

@Composable
private fun Message(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String?) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.outline)
        Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
        if (detail != null) Text(detail, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
