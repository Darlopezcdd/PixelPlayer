@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.theveloper.pixelplay.presentation.telegram.deezload

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.telegram.DeezloadDownloadProgress
import com.theveloper.pixelplay.data.telegram.DeezloadSearchResult
import com.theveloper.pixelplay.presentation.viewmodel.DeezloadViewModel
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import kotlinx.coroutines.delay
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeezloadSearchSheet(
    onDismissRequest: () -> Unit,
    viewModel: DeezloadViewModel = hiltViewModel()
) {
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val searchError by viewModel.searchError.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val activeDownloadId by viewModel.activeDownloadId.collectAsStateWithLifecycle()
    val isTelegramReady by viewModel.isTelegramReady.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(300)
        focusRequester.requestFocus()
    }

    val inputShape = AbsoluteSmoothCornerShape(
        cornerRadiusTR = 20.dp, cornerRadiusTL = 20.dp,
        cornerRadiusBR = 20.dp, cornerRadiusBL = 20.dp,
        smoothnessAsPercentTR = 60, smoothnessAsPercentTL = 60,
        smoothnessAsPercentBR = 60, smoothnessAsPercentBL = 60
    )

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.clearResults()
            onDismissRequest()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .width(48.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .heightIn(min = 500.dp)
        ) {
            // ─── Header ──────────────────────────────────────────────────

            Text(
                text = stringResource(R.string.deezload_sheet_title),
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.deezload_sheet_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ─── Search Input ────────────────────────────────────────────

            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                placeholder = {
                    Text(
                        stringResource(R.string.deezload_search_placeholder),
                        fontFamily = GoogleSansRounded
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearResults() }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.deezload_clear_search),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                shape = inputShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ─── Download Progress Banner ────────────────────────────────

            AnimatedVisibility(
                visible = downloadProgress != null,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                downloadProgress?.let { progress ->
                    DownloadProgressBanner(progress = progress)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // ─── Content Area ────────────────────────────────────────────

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                when {
                    !isTelegramReady -> {
                        // Telegram not connected
                        DeezloadStatusMessage(
                            icon = Icons.Rounded.ErrorOutline,
                            iconColor = MaterialTheme.colorScheme.error,
                            iconBgColor = MaterialTheme.colorScheme.errorContainer,
                            title = stringResource(R.string.deezload_telegram_not_connected),
                            subtitle = stringResource(R.string.deezload_telegram_connect_first)
                        )
                    }
                    isSearching -> {
                        // Loading state
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            LoadingIndicator(
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = stringResource(R.string.deezload_searching),
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = GoogleSansRounded,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    searchError != null -> {
                        DeezloadStatusMessage(
                            icon = Icons.Rounded.ErrorOutline,
                            iconColor = MaterialTheme.colorScheme.error,
                            iconBgColor = MaterialTheme.colorScheme.errorContainer,
                            title = stringResource(R.string.deezload_no_results),
                            subtitle = searchError!!
                        )
                    }
                    searchResults.isNotEmpty() -> {
                        // Results list
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(
                                items = searchResults,
                                key = { it.inlineResultId }
                            ) { result ->
                                DeezloadResultItem(
                                    result = result,
                                    isDownloading = activeDownloadId == result.inlineResultId,
                                    downloadProgress = if (activeDownloadId == result.inlineResultId) downloadProgress else null,
                                    onDownloadClick = { viewModel.downloadAndSave(result) }
                                )
                            }
                        }
                    }
                    else -> {
                        // Initial empty state
                        DeezloadStatusMessage(
                            icon = Icons.Rounded.CloudDownload,
                            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            iconBgColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            title = stringResource(R.string.deezload_initial_title),
                            subtitle = stringResource(R.string.deezload_initial_subtitle)
                        )
                    }
                }
            }
        }
    }
}

// ─── Result Item ─────────────────────────────────────────────────────────────
// Now uses shared DeezloadResultItem from DeezloadComponents.kt

// ─── Download Progress Banner ────────────────────────────────────────────────
// Now uses shared DownloadProgressBanner from DeezloadComponents.kt

// ─── Status Message (reusable empty/error state) ─────────────────────────────
// Now uses shared DeezloadStatusMessage from DeezloadComponents.kt

