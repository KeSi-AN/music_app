package com.riyaz.app.songbook

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SbScreen {
    data object Library : SbScreen
    /** Viewing a sheet; [setlistId] when opened from a setlist (gives previous / next). */
    data class View(val id: String, val setlistId: String? = null) : SbScreen
    /** Editing [draft]; [returnTo] is where Save / Cancel go. */
    data class Edit(val draft: Sheet, val isNew: Boolean, val returnTo: SbScreen) : SbScreen
    data class SetlistView(val id: String) : SbScreen
}

data class SbState(
    val sheets: List<Sheet> = emptyList(),
    val setlists: List<Setlist> = emptyList(),
    val screen: SbScreen = SbScreen.Library,
    val tab: Int = 0,                 // 0 = sheets, 1 = setlists
    val query: String = "",
    val message: String? = null,
) {
    fun sheet(id: String) = sheets.firstOrNull { it.id == id }
    fun setlist(id: String) = setlists.firstOrNull { it.id == id }
}

class SongbookViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SongbookStore(app.filesDir)
    private val _state = MutableStateFlow(SbState(store.sheets(), store.setlists()))
    val state: StateFlow<SbState> = _state

    private fun io(block: () -> Unit) = viewModelScope.launch(Dispatchers.IO) { runCatching(block).onFailure { Log.e("Riyaz", "songbook", it) } }

    // ------------------------------------------------------------------ navigation

    /** After a restore: re-read sheets and setlists from disk. */
    fun reload() = _state.update { it.copy(sheets = store.sheets(), setlists = store.setlists(), screen = SbScreen.Library) }

    fun go(screen: SbScreen) = _state.update { it.copy(screen = screen, message = null) }
    fun setTab(t: Int) = _state.update { it.copy(tab = t) }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Back inside the songbook; false when already at the library (the caller closes the songbook). */
    fun back(): Boolean {
        val s = _state.value.screen
        go(when (s) {
            SbScreen.Library -> return false
            is SbScreen.View -> s.setlistId?.let { SbScreen.SetlistView(it) } ?: SbScreen.Library
            is SbScreen.Edit -> s.returnTo
            is SbScreen.SetlistView -> SbScreen.Library.also { setTab(1) }
        })
        return true
    }

    fun open(id: String, setlistId: String? = null) = go(SbScreen.View(id, setlistId))

    // ------------------------------------------------------------------ sheets

    fun newSheet() = go(SbScreen.Edit(Sheet(Sheet.newId(), "", text = ""), true, _state.value.screen))

    fun edit(id: String) {
        val sheet = _state.value.sheet(id) ?: return
        go(SbScreen.Edit(sheet, false, _state.value.screen))
    }

    /** Pasted or shared text: opens the editor with title / artist / capo filled in from the sheet if it has them. */
    fun importText(text: String, returnTo: SbScreen = SbScreen.Library) {
        val p = ChordSheetParser.parse(text)
        // a first line like "Perfect - Ed Sheeran" becomes the title (and artist) instead of staying in the sheet
        val guessed = if (p.title == null) ChordSheetParser.guessTitle(text) else null
        val body = if (guessed != null) text.trim().lines().drop(1).joinToString("\n").trim() else text.trim()
        val (gTitle, gArtist) = guessed?.split(" - ", limit = 2)?.let { it[0].trim() to it.getOrNull(1)?.trim() } ?: (null to null)
        val draft = Sheet(Sheet.newId(), p.title ?: gTitle ?: "", p.artist ?: gArtist ?: "", body, capo = p.capo ?: 0)
        go(SbScreen.Edit(draft, true, returnTo))
    }

    fun importFile(uri: Uri) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching { getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }
        if (text == null || text.size > 512 * 1024) {
            _state.update { it.copy(message = "Couldn't read that file as a chord sheet.") }; return@launch
        }
        importText(String(text, Charsets.UTF_8), _state.value.screen)
    }

    /** Saves the editor's sheet and shows it. */
    fun save(draft: Sheet) {
        val sheet = draft.copy(title = draft.title.trim().ifEmpty { "Untitled" }, artist = draft.artist.trim(),
            updated = System.currentTimeMillis())
        _state.update { s -> s.copy(sheets = listOf(sheet) + s.sheets.filter { it.id != sheet.id }) }
        io { store.save(sheet) }
        val back = (_state.value.screen as? SbScreen.Edit)?.returnTo
        go(if (back is SbScreen.View && back.id == sheet.id) back else SbScreen.View(sheet.id))
    }

    fun delete(id: String) {
        _state.update { s -> s.copy(sheets = s.sheets.filter { it.id != id },
            setlists = s.setlists.map { it.copy(sheetIds = it.sheetIds - id) }) }
        io { store.delete(id) }
        go(SbScreen.Library)
    }

    /** Settings that change as you view (key, capo…): saved without moving the sheet to the top of the list. */
    fun change(id: String, f: (Sheet) -> Sheet) {
        val old = _state.value.sheet(id) ?: return
        val new = f(old)
        _state.update { s -> s.copy(sheets = s.sheets.map { if (it.id == id) new else it }) }
        io { store.save(new) }
    }

    /** A sheet made from a song in the library: opens the one already made, or saves this new one. */
    fun openForSong(songId: String, make: () -> Sheet?) {
        _state.value.sheets.firstOrNull { it.songId == songId }?.let { open(it.id); return }
        val sheet = make() ?: return
        _state.update { s -> s.copy(sheets = listOf(sheet) + s.sheets) }
        io { store.save(sheet) }
        open(sheet.id)
    }

    // ------------------------------------------------------------------ setlists

    private fun saveSetlists(l: List<Setlist>) {
        _state.update { it.copy(setlists = l) }
        io { store.saveSetlists(l) }
    }

    fun newSetlist(name: String): String {
        val l = Setlist("l" + System.currentTimeMillis().toString(36), name.trim().ifEmpty { "Setlist" })
        saveSetlists(_state.value.setlists + l)
        return l.id
    }

    fun renameSetlist(id: String, name: String) =
        saveSetlists(_state.value.setlists.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })

    fun deleteSetlist(id: String) {
        saveSetlists(_state.value.setlists.filter { it.id != id })
        if ((_state.value.screen as? SbScreen.SetlistView)?.id == id) go(SbScreen.Library)
    }

    fun addToSetlist(setlistId: String, sheetId: String) = saveSetlists(_state.value.setlists.map {
        if (it.id == setlistId && sheetId !in it.sheetIds) it.copy(sheetIds = it.sheetIds + sheetId) else it
    })

    fun removeFromSetlist(setlistId: String, sheetId: String) =
        saveSetlists(_state.value.setlists.map { if (it.id == setlistId) it.copy(sheetIds = it.sheetIds - sheetId) else it })

    fun move(setlistId: String, index: Int, by: Int) = saveSetlists(_state.value.setlists.map { l ->
        if (l.id != setlistId) return@map l
        val to = index + by
        if (index !in l.sheetIds.indices || to !in l.sheetIds.indices) return@map l
        val ids = l.sheetIds.toMutableList()
        ids.add(to, ids.removeAt(index))
        l.copy(sheetIds = ids)
    })
}
