package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.model.Playlist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistSelectionStateHolder @Inject constructor() {

    private val mutationLock = Any()

    private val _selectedPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val selectedPlaylists: StateFlow<List<Playlist>> = _selectedPlaylists.asStateFlow()

    private val _selectedPlaylistIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedPlaylistIds: StateFlow<Set<String>> = _selectedPlaylistIds.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedCount = MutableStateFlow(0)
    val selectedCount: StateFlow<Int> = _selectedCount.asStateFlow()

    fun toggleSelection(playlist: Playlist) {
        synchronized(mutationLock) {
            val currentList = _selectedPlaylists.value
            val currentIds = _selectedPlaylistIds.value
            val (newList, newIds) = if (playlist.id in currentIds) {
                currentList.filter { it.id != playlist.id } to (currentIds - playlist.id)
            } else {
                (currentList + playlist) to (currentIds + playlist.id)
            }
            updateStateLocked(newList, newIds)
        }
    }

    fun selectAll(playlists: List<Playlist>) {
        synchronized(mutationLock) {
            val currentIds = _selectedPlaylistIds.value
            val currentList = _selectedPlaylists.value.toMutableList()

            playlists.forEach { playlist ->
                if (!currentIds.contains(playlist.id)) {
                    currentList.add(playlist)
                }
            }

            val newIds = currentList.map { it.id }.toSet()
            updateStateLocked(currentList, newIds)
        }
    }

    fun clearSelection() {
        synchronized(mutationLock) {
            updateStateLocked(emptyList(), emptySet())
        }
    }

    fun isSelected(playlistId: String): Boolean {
        return _selectedPlaylistIds.value.contains(playlistId)
    }

    fun getSelectionIndex(playlistId: String): Int? {
        val index = _selectedPlaylists.value.indexOfFirst { it.id == playlistId }
        return if (index >= 0) index + 1 else null
    }

    fun removeFromSelection(playlistId: String) {
        synchronized(mutationLock) {
            val currentIds = _selectedPlaylistIds.value
            if (playlistId !in currentIds) return
            val newList = _selectedPlaylists.value.filter { it.id != playlistId }
            updateStateLocked(newList, currentIds - playlistId)
        }
    }

    private fun updateStateLocked(playlists: List<Playlist>, ids: Set<String>) {
        _selectedPlaylists.value = playlists
        _selectedPlaylistIds.value = ids
        _selectedCount.value = playlists.size
        _isSelectionMode.value = playlists.isNotEmpty()
    }
}
