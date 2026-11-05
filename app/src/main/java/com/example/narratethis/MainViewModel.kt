package com.example.narratethis

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { Docs, Player }

data class LastDoc(val id: String, val title: String, val progress: Float)

data class UiState(
    val screen: Screen = Screen.Docs,
    val authorized: Boolean = false,
    val search: String = "",
    val docs: List<DriveDoc> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Set when Google needs the user to approve Drive access; the UI launches it. */
    val consent: PendingIntent? = null,
    val lastDoc: LastDoc? = null,
    val account: String? = null,
    /** Why the document list couldn't be loaded, shown in place of the list. */
    val listError: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val narrator = Narrator.get(app)
    private val drive = GoogleDrive(app)

    private val _ui = MutableStateFlow(
        UiState(screen = if (narrator.state.value.doc != null) Screen.Player else Screen.Docs)
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        refreshLastDoc()
        // Connect silently if the user already granted access before.
        viewModelScope.launch {
            try {
                drive.authorize()
                _ui.update { it.copy(authorized = true) }
                refresh()
            } catch (_: Exception) {
                // Not connected yet; the user can tap "Connect Google Drive".
            }
        }
    }

    fun connect() = launchWithErrors {
        drive.authorize()
        _ui.update { it.copy(authorized = true) }
        loadDocs()
    }

    fun consentLaunched() = _ui.update { it.copy(consent = null) }

    fun onConsentResult(data: Intent?) = launchWithErrors {
        drive.completeAuthorization(data)
        _ui.update { it.copy(authorized = true) }
        loadDocs()
    }

    fun setSearch(text: String) = _ui.update { it.copy(search = text) }

    fun refresh() = launchWithErrors { loadDocs() }

    fun openDoc(doc: DriveDoc) = launchWithErrors {
        val text = try {
            drive.fetchText(doc)
        } catch (e: ConsentRequiredException) {
            throw e
        } catch (e: Exception) {
            // Offline? Fall back to the copy saved on the phone, if there is one.
            narrator.cachedText(doc.id) ?: throw e
        }
        narrator.load(doc.id, doc.name, text)
        _ui.update { it.copy(screen = Screen.Player) }
    }

    /** Re-opens the last document, refreshing it from Drive when possible. */
    fun continueLast() {
        val last = _ui.value.lastDoc ?: return
        if (narrator.state.value.docId == last.id) {
            _ui.update { it.copy(screen = Screen.Player) }
        } else if (_ui.value.authorized) {
            openDoc(DriveDoc(last.id, last.title, ""))
        } else if (narrator.restoreLast()) {
            _ui.update { it.copy(screen = Screen.Player) }
        }
    }

    fun showDocs() {
        refreshLastDoc()
        _ui.update { it.copy(screen = Screen.Docs) }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun savedProgress(docId: String): Float? = narrator.savedProgress(docId)

    private fun refreshLastDoc() {
        val last = narrator.lastDoc()
        _ui.update { it.copy(lastDoc = last?.let { (id, title, p) -> LastDoc(id, title, p) }) }
    }

    private suspend fun loadDocs() {
        val docs = try {
            drive.listDocs(_ui.value.search)
        } catch (e: Exception) {
            _ui.update { it.copy(docs = emptyList(), listError = describeError(e)) }
            throw e
        }
        _ui.update { it.copy(docs = docs, listError = null) }
        if (_ui.value.account == null) {
            val email = drive.accountEmail()
            _ui.update { it.copy(account = email) }
        }
    }

    private fun launchWithErrors(block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                block()
            } catch (e: ConsentRequiredException) {
                _ui.update { it.copy(consent = e.pendingIntent) }
            } catch (e: Exception) {
                _ui.update { it.copy(error = describeError(e)) }
            } finally {
                _ui.update { it.copy(loading = false) }
            }
        }
    }
}
