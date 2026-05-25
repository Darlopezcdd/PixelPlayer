package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.telegram.DeezloadBotService
import com.theveloper.pixelplay.data.telegram.DeezloadDownloadProgress
import com.theveloper.pixelplay.data.telegram.DeezloadSearchResult
import com.theveloper.pixelplay.data.telegram.TelegramRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class DeezloadViewModel @Inject constructor(
    private val deezloadBotService: DeezloadBotService,
    private val telegramRepository: TelegramRepository
) : ViewModel() {

    // ─── Search State ─────────────────────────────────────────────────────────

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<DeezloadSearchResult>>(emptyList())
    val searchResults: StateFlow<List<DeezloadSearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    // ─── Download State ───────────────────────────────────────────────────────

    private val _downloadProgress = MutableStateFlow<DeezloadDownloadProgress?>(null)
    val downloadProgress: StateFlow<DeezloadDownloadProgress?> = _downloadProgress.asStateFlow()

    /** The result currently being downloaded (to highlight in the UI) */
    private val _activeDownloadId = MutableStateFlow<String?>(null)
    val activeDownloadId: StateFlow<String?> = _activeDownloadId.asStateFlow()

    // ─── Telegram Connection ──────────────────────────────────────────────────

    private val _isTelegramReady = MutableStateFlow(false)
    val isTelegramReady: StateFlow<Boolean> = _isTelegramReady.asStateFlow()

    // ─── Toast/snackbar events ────────────────────────────────────────────────

    private val _toastEvent = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastEvent = _toastEvent.asSharedFlow()

    // ─── Internal ─────────────────────────────────────────────────────────────

    private var searchJob: Job? = null
    private var downloadJob: Job? = null

    init {
        checkTelegramConnection()
        observeSearchQuery()
    }

    private fun checkTelegramConnection() {
        viewModelScope.launch {
            _isTelegramReady.value = telegramRepository.isReady()
            if (!_isTelegramReady.value) {
                // Try waiting briefly for auth
                _isTelegramReady.value = telegramRepository.awaitReady(5_000L)
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeSearchQuery() {
        viewModelScope.launch {
            _searchQuery
                .debounce(500L)
                .collectLatest { query ->
                    if (query.length >= 2) {
                        performSearch(query)
                    } else {
                        _searchResults.value = emptyList()
                    }
                }
        }
    }

    // ─── Public Actions ───────────────────────────────────────────────────────

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _searchError.value = null
    }

    fun search(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            performSearch(query)
        }
    }

    fun downloadAndSave(result: DeezloadSearchResult) {
        if (downloadJob?.isActive == true) {
            _toastEvent.tryEmit("Ya hay una descarga en progreso")
            return
        }

        _activeDownloadId.value = result.inlineResultId
        downloadJob = viewModelScope.launch {
            deezloadBotService.downloadSong(result)
                .catch { e ->
                    Timber.e(e, "Download flow error")
                    _downloadProgress.value = DeezloadDownloadProgress.Error(
                        e.message ?: "Error desconocido"
                    )
                }
                .collect { progress ->
                    _downloadProgress.value = progress

                    when (progress) {
                        is DeezloadDownloadProgress.Completed -> {
                            _toastEvent.tryEmit("¡${result.title} descargada!")
                            _activeDownloadId.value = null
                            // Auto-clear progress after a short delay
                            kotlinx.coroutines.delay(2000)
                            _downloadProgress.value = null
                        }
                        is DeezloadDownloadProgress.Error -> {
                            _toastEvent.tryEmit(progress.message)
                            _activeDownloadId.value = null
                            kotlinx.coroutines.delay(3000)
                            _downloadProgress.value = null
                        }
                        else -> { /* progress updates handled by UI */ }
                    }
                }
        }
    }

    fun clearResults() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _searchError.value = null
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadProgress.value = null
        _activeDownloadId.value = null
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private suspend fun performSearch(query: String) {
        if (query.isBlank()) return

        _isSearching.value = true
        _searchError.value = null

        try {
            val results = deezloadBotService.searchSongs(query)
            _searchResults.value = results
            if (results.isEmpty()) {
                _searchError.value = "No se encontraron resultados para \"$query\""
            }
        } catch (e: Exception) {
            Timber.e(e, "Search failed for query: $query")
            _searchError.value = when {
                e.message?.contains("not authorized", ignoreCase = true) == true ->
                    "Telegram no está conectado. Inicia sesión primero."
                e.message?.contains("timeout", ignoreCase = true) == true ->
                    "La búsqueda tardó demasiado. Intenta de nuevo."
                else -> "Error al buscar: ${e.message}"
            }
        } finally {
            _isSearching.value = false
        }
    }
}
