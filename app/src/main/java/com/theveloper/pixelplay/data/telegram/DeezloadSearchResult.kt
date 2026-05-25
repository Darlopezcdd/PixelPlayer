package com.theveloper.pixelplay.data.telegram

import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.data.model.Song

/**
 * Represents a single search result from the @deezload2bot inline query.
 * Each result maps to one track the user can select for download.
 */
@Immutable
data class DeezloadSearchResult(
    /** The inline result ID returned by TDLib (used in SendInlineQueryResultMessage) */
    val inlineResultId: String,
    /** The inline query ID from the GetInlineQueryResults response */
    val inlineQueryId: Long,
    val title: String,
    val artist: String,
    val album: String,
    /** URL for the album art thumbnail (may be null for results without artwork) */
    val thumbnailUrl: String?,
    /** Human-readable duration string from the bot, e.g. "4:21" */
    val duration: String?
)

/**
 * Represents the progress of a download operation from @deezload2bot.
 */
sealed class DeezloadDownloadProgress {
    /** Sending the inline result to Saved Messages */
    data object SendingRequest : DeezloadDownloadProgress()
    /** Waiting for the bot to respond with the audio file */
    data object WaitingForBot : DeezloadDownloadProgress()
    /** TDLib is downloading the file from Telegram servers */
    data class Downloading(val percent: Int) : DeezloadDownloadProgress()
    /** Copying the file from TDLib cache to public music folder */
    data object SavingToLibrary : DeezloadDownloadProgress()
    /** Download completed successfully */
    data class Completed(val savedPath: String, val song: Song?) : DeezloadDownloadProgress()
    /** Download failed */
    data class Error(val message: String) : DeezloadDownloadProgress()
}
