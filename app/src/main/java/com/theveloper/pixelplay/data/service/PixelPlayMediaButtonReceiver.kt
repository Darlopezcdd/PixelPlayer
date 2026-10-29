package com.theveloper.pixelplay.data.service

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import timber.log.Timber

@OptIn(UnstableApi::class)
class PixelPlayMediaButtonReceiver : MediaButtonReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) {
            super.onReceive(context, intent)
            return
        }

        MusicService.markPendingMediaButtonForegroundStart()
        try {
            super.onReceive(context, intent)
        } catch (e: IllegalStateException) {
            MusicService.unmarkPendingMediaButtonForegroundStart()
            Timber.tag(TAG).w(e, "Media button start denied while app cached")
        } catch (throwable: Throwable) {
            MusicService.unmarkPendingMediaButtonForegroundStart()
            Timber.tag(TAG).w(throwable, "Media button dispatch failed before MusicService start")
            throw throwable
        }
    }

    companion object {
        private const val TAG = "MediaButtonReceiver"
    }
}
