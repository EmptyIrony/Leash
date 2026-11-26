package dev.leash.personal.ui.files

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.Problem
import dev.leash.personal.core.toProblem
import dev.leash.personal.text.problemText
import dev.leash.personal.text.res
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.BrowseResult
import dev.leash.personal.ui.chat.LocalOpenFile
import dev.leash.personal.ui.components.LazyScreen
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.components.EmptyState
import dev.leash.personal.ui.design.components.LoadingState
import dev.leash.personal.ui.newsession.browseRows

/** A conversation's folder on the agent's host, Files-style: each folder is a screen on the stack (ios ProjectBrowser). A file opens in the viewer. */
@Composable
fun ProjectFolderScreen(model: AppModel, agent: String, path: String, onBack: () -> Unit, onOpenFolder: (String) -> Unit) {
    var result by remember(path) { mutableStateOf<BrowseResult?>(null) }
    var error by remember(path) { mutableStateOf<Problem?>(null) }
    LaunchedEffect(path) {
        try { result = model.browse(path, agent) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.toProblem() }
    }
    val r = result
    ProvideFiles(model, agent, null) {
        val open = LocalOpenFile.current
        ProjectRows(path, r, error, onBack, onOpenFolder, open)
    }
}

@Composable
private fun ProjectRows(path: String, r: BrowseResult?, error: Problem?, onBack: () -> Unit, onOpenFolder: (String) -> Unit, onOpenFile: (String) -> Unit) {
    LazyScreen(path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }, onBack) {
        when {
            error != null -> item("err") { EmptyState(LeashIcons.folder, stringResource(R.string.cant_open_folder), Modifier.fillMaxWidth().padding(top = 96.dp), message = res().problemText(error)) }
            r == null -> item("spin") { LoadingState(stringResource(R.string.loading), Modifier.fillMaxWidth().padding(top = 96.dp)) }
            r.dirs.isEmpty() && r.files.isEmpty() -> item("empty") { EmptyState(LeashIcons.folder, stringResource(R.string.empty_folder), Modifier.fillMaxWidth().padding(top = 96.dp)) }
            else -> browseRows(r.copy(path = path), onOpenFolder, filesHeader = false, onOpenFile = onOpenFile)
        }
    }
}
