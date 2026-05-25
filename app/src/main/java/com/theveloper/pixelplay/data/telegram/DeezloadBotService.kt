package com.theveloper.pixelplay.data.telegram

import android.content.Context
import android.media.MediaScannerConnection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class DeezloadBotService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientManager: TelegramClientManager
) {
    companion object {
        const val BOT_USERNAME = "deezload2bot"
        private const val INLINE_QUERY_TIMEOUT_MS = 15_000L
        private const val BOT_RESPONSE_TIMEOUT_MS = 60_000L
        private const val FILE_DOWNLOAD_TIMEOUT_MS = 300_000L // 5 min for large FLAC files
        private const val DOWNLOAD_DIR_NAME = "PixelPlayer Downloads"
    }

    // Cached bot user ID (resolved once)
    @Volatile
    private var cachedBotUserId: Long = 0L

    // Cached Saved Messages chat ID
    @Volatile
    private var cachedSavedMessagesChatId: Long = 0L

    /**
     * Resolves the @deezload2bot user ID. Caches the result for subsequent calls.
     */
    suspend fun resolveBotUserId(): Long {
        if (cachedBotUserId != 0L) return cachedBotUserId
        val chat = clientManager.sendRequest<TdApi.Chat>(TdApi.SearchPublicChat(BOT_USERNAME))
        val chatType = chat.type
        if (chatType is TdApi.ChatTypePrivate) {
            cachedBotUserId = chatType.userId
        } else {
            throw IllegalStateException("@$BOT_USERNAME is not a private bot chat")
        }
        return cachedBotUserId
    }

    /**
     * Gets the bot's private chat ID.
     */
    private suspend fun getBotChatId(): Long {
        val botUserId = resolveBotUserId()
        val chat = clientManager.sendRequest<TdApi.Chat>(TdApi.CreatePrivateChat(botUserId, false))
        return chat.id
    }

    /**
     * Searches for songs using the @deezload2bot inline query.
     * Returns a list of results with title, artist, album, and thumbnail info.
     */
    suspend fun searchSongs(query: String): List<DeezloadSearchResult> {
        if (query.isBlank()) return emptyList()

        val botUserId = resolveBotUserId()
        val botChatId = getBotChatId()

        val request = TdApi.GetInlineQueryResults(
            botUserId,
            botChatId,
            null, // user location — not needed
            query,
            "" // offset for pagination — empty for first page
        )

        val response = withTimeoutOrNull(INLINE_QUERY_TIMEOUT_MS) {
            clientManager.sendRequest<TdApi.InlineQueryResults>(request)
        } ?: run {
            Timber.w("Inline query timed out for query: $query")
            return emptyList()
        }

        return response.results.mapNotNull { result ->
            mapInlineResult(result, response.inlineQueryId)
        }
    }

    /**
     * Maps a TDLib InlineQueryResult to our DeezloadSearchResult model.
     * The bot returns results as InlineQueryResultArticle or InlineQueryResultAudio.
     */
    private fun mapInlineResult(
        result: TdApi.InlineQueryResult,
        queryId: Long
    ): DeezloadSearchResult? {
        return when (result) {
            is TdApi.InlineQueryResultAudio -> {
                val audio = result.audio
                DeezloadSearchResult(
                    inlineResultId = result.id,
                    inlineQueryId = queryId,
                    title = audio.title.ifEmpty { "Unknown Title" },
                    artist = audio.performer.ifEmpty { "Unknown Artist" },
                    album = "", // Audio results don't always include album
                    thumbnailUrl = audio.albumCoverThumbnail?.let {
                        // If it's a remote photo, use the file ID as reference
                        "telegram_thumbnail://${it.file.id}"
                    },
                    duration = formatDuration(audio.duration)
                )
            }
            is TdApi.InlineQueryResultArticle -> {
                // The bot may return article-type results with title/description
                DeezloadSearchResult(
                    inlineResultId = result.id,
                    inlineQueryId = queryId,
                    title = result.title,
                    artist = extractArtistFromDescription(result.description),
                    album = extractAlbumFromDescription(result.description),
                    thumbnailUrl = result.thumbnail?.let { thumb ->
                        when (thumb.format) {
                            is TdApi.ThumbnailFormatJpeg,
                            is TdApi.ThumbnailFormatPng,
                            is TdApi.ThumbnailFormatWebp -> "telegram_thumbnail://${thumb.file.id}"
                            else -> null
                        }
                    },
                    duration = null
                )
            }
            else -> {
                Timber.d("Unhandled inline result type: ${result.javaClass.simpleName}")
                null
            }
        }
    }

    /**
     * Downloads a song by sending the inline result to Saved Messages,
     * waiting for the bot response, downloading the audio file, and saving it
     * to the public music directory.
     *
     * @return A Flow emitting progress updates throughout the download lifecycle.
     */
    fun downloadSong(result: DeezloadSearchResult): Flow<DeezloadDownloadProgress> = flow {
        emit(DeezloadDownloadProgress.SendingRequest)

        try {
            val botChatId = getBotChatId()

            // Send the inline query result to the bot's chat
            val sendRequest = TdApi.SendInlineQueryResultMessage().apply {
                chatId = botChatId
                queryId = result.inlineQueryId
                resultId = result.inlineResultId
            }

            // Subscribe to updates BEFORE sending the request to avoid missing fast replies
            val deferredContent = kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext).async {
                clientManager.updates
                    .filter { update ->
                        when (update) {
                            is TdApi.UpdateNewMessage -> {
                                update.message.chatId == botChatId &&
                                (update.message.content is TdApi.MessageAudio ||
                                 update.message.content is TdApi.MessageDocument)
                            }
                            is TdApi.UpdateMessageContent -> {
                                update.chatId == botChatId &&
                                (update.newContent is TdApi.MessageAudio ||
                                 update.newContent is TdApi.MessageDocument)
                            }
                            else -> false
                        }
                    }
                    .map { update ->
                        when (update) {
                            is TdApi.UpdateNewMessage -> update.message.content
                            is TdApi.UpdateMessageContent -> update.newContent
                            else -> throw IllegalStateException("Unexpected update type")
                        }
                    }
                    .first()
            }

            val sentMessage = clientManager.sendRequest<TdApi.Message>(sendRequest)
            Timber.d("Sent inline result to bot chat, messageId=${sentMessage.id}")

            emit(DeezloadDownloadProgress.WaitingForBot)

            // The sent message itself might contain the audio (cached inline bot results)
            val audioContent = if (sentMessage.content is TdApi.MessageAudio || sentMessage.content is TdApi.MessageDocument) {
                deferredContent.cancel()
                sentMessage.content
            } else {
                withTimeoutOrNull(BOT_RESPONSE_TIMEOUT_MS) {
                    deferredContent.await()
                }
            }

            if (audioContent == null) {
                emit(DeezloadDownloadProgress.Error("El bot no respondió. Intenta de nuevo."))
                return@flow
            }

            // Extract the file ID from the audio message
            val audioFileId = when (audioContent) {
                is TdApi.MessageAudio -> audioContent.audio.audio.id
                is TdApi.MessageDocument -> audioContent.document.document.id
                else -> {
                    emit(DeezloadDownloadProgress.Error("Respuesta inesperada del bot."))
                    return@flow
                }
            }

            // Extract metadata for file naming
            val (finalTitle, finalArtist, mimeType) = when (audioContent) {
                is TdApi.MessageAudio -> Triple(
                    audioContent.audio.title.ifEmpty { result.title },
                    audioContent.audio.performer.ifEmpty { result.artist },
                    audioContent.audio.mimeType
                )
                is TdApi.MessageDocument -> Triple(
                    result.title,
                    result.artist,
                    audioContent.document.mimeType
                )
                else -> Triple(result.title, result.artist, "audio/flac")
            }

            emit(DeezloadDownloadProgress.Downloading(0))

            // Download the file with high priority
            // First, start the download
            val downloadRequest = TdApi.DownloadFile(audioFileId, 32, 0, 0, false)
            try {
                clientManager.sendRequest<TdApi.File>(downloadRequest)
            } catch (e: Exception) {
                Timber.w(e, "Initial download request failed")
            }

            // Monitor download progress
            val downloadedPath = withTimeoutOrNull(FILE_DOWNLOAD_TIMEOUT_MS) {
                clientManager.updates
                    .filterIsInstance<TdApi.UpdateFile>()
                    .filter { it.file.id == audioFileId }
                    .first { update ->
                        val file = update.file
                        if (file.local.isDownloadingActive) {
                            val totalSize = file.expectedSize
                            if (totalSize > 0) {
                                val percent = ((file.local.downloadedSize * 100) / totalSize).toInt()
                                    .coerceIn(0, 99)
                                emit(DeezloadDownloadProgress.Downloading(percent))
                            }
                        }
                        when {
                            file.local.isDownloadingCompleted && file.local.path.isNotEmpty() -> true
                            !file.local.canBeDownloaded -> throw Exception("El archivo no se puede descargar")
                            else -> false
                        }
                    }
                    .file.local.path
            }

            if (downloadedPath.isNullOrEmpty()) {
                // Try getting the file directly in case we missed the update
                val finalFile = clientManager.sendRequest<TdApi.File>(TdApi.GetFile(audioFileId))
                if (finalFile.local.isDownloadingCompleted && finalFile.local.path.isNotEmpty()) {
                    emit(DeezloadDownloadProgress.Downloading(100))
                    emit(DeezloadDownloadProgress.SavingToLibrary)
                    val savedPath = saveToMusicLibrary(finalFile.local.path, finalTitle, finalArtist, mimeType)
                    if (savedPath != null) {
                        scanFile(savedPath, mimeType)
                        emit(DeezloadDownloadProgress.Completed(savedPath = savedPath, song = null))
                    } else {
                        emit(DeezloadDownloadProgress.Error("No se pudo guardar el archivo."))
                    }
                } else {
                    emit(DeezloadDownloadProgress.Error("La descarga expiró. Intenta de nuevo."))
                }
                return@flow
            }

            emit(DeezloadDownloadProgress.Downloading(100))
            emit(DeezloadDownloadProgress.SavingToLibrary)
            val savedPath = saveToMusicLibrary(downloadedPath, finalTitle, finalArtist, mimeType)
            if (savedPath != null) {
                scanFile(savedPath, mimeType)
                emit(DeezloadDownloadProgress.Completed(savedPath = savedPath, song = null))
            } else {
                emit(DeezloadDownloadProgress.Error("No se pudo guardar el archivo."))
            }

        } catch (e: TdlibRequestException) {
            Timber.e(e, "TDLib error during download")
            emit(DeezloadDownloadProgress.Error("Error de Telegram: ${e.message}"))
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error during download")
            emit(DeezloadDownloadProgress.Error("Error: ${e.message ?: "desconocido"}"))
        }
    }

    /**
     * Copies the downloaded file from TDLib's internal cache to the public
     * Music directory under "PixelPlayer Downloads".
     */
    private suspend fun saveToMusicLibrary(
        tdlibPath: String,
        title: String,
        artist: String,
        mimeType: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(tdlibPath)
            if (!sourceFile.exists()) {
                Timber.e("Source file does not exist: $tdlibPath")
                return@withContext null
            }

            // Determine file extension from mime type
            val extension = when {
                mimeType.contains("flac") -> "flac"
                mimeType.contains("mp3") || mimeType.contains("mpeg") -> "mp3"
                mimeType.contains("m4a") || mimeType.contains("mp4") -> "m4a"
                mimeType.contains("wav") -> "wav"
                mimeType.contains("ogg") -> "ogg"
                mimeType.contains("aac") -> "aac"
                else -> sourceFile.extension.ifEmpty { "flac" }
            }

            // Create destination directory
            val musicDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_MUSIC
            )
            val downloadDir = File(musicDir, DOWNLOAD_DIR_NAME)
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }

            // Sanitize filename
            val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            val safeArtist = artist.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            val fileName = "$safeArtist - $safeTitle.$extension"

            var destFile = File(downloadDir, fileName)

            // Handle duplicate filenames
            var counter = 1
            while (destFile.exists()) {
                destFile = File(downloadDir, "$safeArtist - $safeTitle ($counter).$extension")
                counter++
            }

            // Copy the file
            sourceFile.copyTo(destFile, overwrite = false)
            Timber.d("Saved to: ${destFile.absolutePath}")

            destFile.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "Failed to save file to music library")
            null
        }
    }

    /**
     * Triggers Android's MediaScanner on the given file so it appears in
     * MediaStore and gets picked up by the next library sync.
     */
    private suspend fun scanFile(path: String, mimeType: String) {
        suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(
                context,
                arrayOf(path),
                arrayOf(mimeType)
            ) { _, _ ->
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    private fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val mins = seconds / 60
        val secs = seconds % 60
        return "$mins:${secs.toString().padStart(2, '0')}"
    }

    /**
     * Extracts the artist name from an inline result description.
     * Typical format: "Artist: Beyoncé\nAlbum: I AM...SASHA FIERCE"
     */
    private fun extractArtistFromDescription(description: String): String {
        // Try "Artist: X" pattern
        val artistMatch = Regex("(?i)artist:\\s*(.+?)(?:\\n|$)").find(description)
        if (artistMatch != null) return artistMatch.groupValues[1].trim()
        // Fallback: first line
        return description.lines().firstOrNull()?.trim() ?: "Unknown Artist"
    }

    /**
     * Extracts the album name from an inline result description.
     * Typical format: "Artist: Beyoncé\nAlbum: I AM...SASHA FIERCE"
     */
    private fun extractAlbumFromDescription(description: String): String {
        val albumMatch = Regex("(?i)album:\\s*(.+?)(?:\\n|$)").find(description)
        return albumMatch?.groupValues?.get(1)?.trim() ?: ""
    }
}
