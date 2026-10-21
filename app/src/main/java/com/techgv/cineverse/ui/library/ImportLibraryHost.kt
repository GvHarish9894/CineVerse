package com.techgv.cineverse.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.library.ImportOffer
import com.techgv.cineverse.data.library.ImportResult
import com.techgv.cineverse.data.library.ImportStep
import com.techgv.cineverse.data.library.LibraryImporter
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.ui.components.ImportDialogState
import com.techgv.cineverse.ui.components.ImportLibraryDialog
import com.techgv.cineverse.ui.components.LocalToaster
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * §4.1 step 6 / §8.3: when an account is signed in (fresh or at launch) and it was never asked, and this TV
 * has guest titles, offer the import once; an unfinished "Import" resumes by itself.
 */
@Composable
fun ImportLibraryHost(repository: LibraryRepository, importer: LibraryImporter) {
    val credentials by repository.credentials.collectAsStateWithLifecycle(initialValue = null)
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<ImportDialogState?>(null) }
    var unconfirmed by remember { mutableStateOf(emptySet<ImportStep>()) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun run(c: Credentials, retry: Set<ImportStep>) {
        job?.cancel()
        job = scope.launch {
            dialog = ImportDialogState.Running
            dialog = when (val result = importer.import(c, retry)) {
                is ImportResult.Aborted -> ImportDialogState.Failed
                is ImportResult.Done -> if (result.unconfirmed.isEmpty()) {
                    toaster.show("Guest library imported", "Synced to your TMDB account")
                    null
                } else {
                    unconfirmed = result.unconfirmed.toSet()
                    ImportDialogState.Unconfirmed(result.written, result.unconfirmedTitles)
                }
            }
        }
    }

    LaunchedEffect(credentials) {
        job?.cancel()
        dialog = null
        val c = credentials ?: return@LaunchedEffect
        when (val offer = importer.offer(c)) {
            is ImportOffer.Ask -> dialog = ImportDialogState.Ask(c.displayName, offer.items)
            ImportOffer.Resume -> run(c, emptySet())
            null -> Unit
        }
    }

    val c = credentials ?: return
    dialog?.let { state ->
        ImportLibraryDialog(
            state = state,
            onKeepSeparate = {
                dialog = null
                scope.launch { importer.keepSeparate(c) }
            },
            onImport = { run(c, emptySet()) },
            onRetry = { run(c, unconfirmed) },
            onClose = {
                dialog = null
                if (state is ImportDialogState.Unconfirmed) scope.launch { importer.finish(c) }
            },
        )
    }
}
