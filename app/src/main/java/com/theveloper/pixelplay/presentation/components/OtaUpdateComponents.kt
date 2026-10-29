package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.data.update.AppUpdateManager
import com.theveloper.pixelplay.data.update.AppUpdateState
import kotlinx.coroutines.launch

@Composable
fun OtaUpdateCard(
    currentVersion: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateState by AppUpdateManager.updateState.collectAsStateWithLifecycle()

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = when (updateState) {
                        is AppUpdateState.UpToDate -> Icons.Rounded.CheckCircle
                        else -> Icons.Rounded.SystemUpdate
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Actualizaciones OTA (Darlopc Mod)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Versión instalada: v$currentVersion • GitHub: ${AppUpdateManager.GITHUB_OWNER}/${AppUpdateManager.GITHUB_REPO}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when (val state = updateState) {
                is AppUpdateState.Idle -> {
                    Text(
                        text = "Comprueba si hay una nueva versión publicada en tu repositorio y actualízala directamente sin desinstalar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                AppUpdateManager.checkForUpdates(context, force = true)
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Buscar actualizaciones")
                    }
                }

                is AppUpdateState.Checking -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp
                        )
                        Text(
                            text = "Buscando nueva versión en GitHub...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is AppUpdateState.UpToDate -> {
                    Text(
                        text = "¡Tienes la versión más reciente instalada (v${state.currentVersion})!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                AppUpdateManager.checkForUpdates(context, force = true)
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Volver a comprobar")
                    }
                }

                is AppUpdateState.UpdateAvailable -> {
                    Text(
                        text = "Nueva versión disponible: ${state.info.releaseName} (${state.info.tagName})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    AnimatedVisibility(visible = state.info.releaseNotes.isNotBlank()) {
                        Text(
                            text = state.info.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                AppUpdateManager.downloadAndInstallUpdate(context, state.info)
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Descargar y actualizar")
                    }
                }

                is AppUpdateState.Downloading -> {
                    Text(
                        text = "Descargando ${state.info.apkFileName}... ${state.progressPercent}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    LinearProgressIndicator(
                        progress = { state.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                    )
                }

                is AppUpdateState.ReadyToInstall -> {
                    Text(
                        text = "Descarga completada (${state.info.tagName}). Toca para instalar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Button(
                        onClick = {
                            AppUpdateManager.launchApkInstaller(context, state.apkFile)
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Instalar ahora")
                    }
                }

                is AppUpdateState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                if (state.info != null) {
                                    AppUpdateManager.downloadAndInstallUpdate(context, state.info)
                                } else {
                                    AppUpdateManager.checkForUpdates(context, force = true)
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Reintentar")
                    }
                }
            }
        }
    }
}

@Composable
fun StartupOtaUpdateDialog() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateState by AppUpdateManager.updateState.collectAsStateWithLifecycle()

    when (val state = updateState) {
        is AppUpdateState.UpdateAvailable -> {
            AlertDialog(
                onDismissRequest = {
                    AppUpdateManager.dismissUpdateDialog(context, state.info.tagName)
                },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = {
                    Text(
                        text = "Actualización disponible (${state.info.tagName})",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Hay una nueva versión de tu PixelPlayer (${state.info.releaseName}) lista para instalar.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (state.info.releaseNotes.isNotBlank()) {
                            Text(
                                text = state.info.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                AppUpdateManager.downloadAndInstallUpdate(context, state.info)
                            }
                        }
                    ) {
                        Text("Actualizar ahora")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            AppUpdateManager.dismissUpdateDialog(context, state.info.tagName)
                        }
                    ) {
                        Text("Más tarde")
                    }
                }
            )
        }

        is AppUpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = {
                    Text("Descargando actualización...")
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${state.info.apkFileName} (${state.progressPercent}%)")
                        LinearProgressIndicator(
                            progress = { state.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {}
            )
        }

        is AppUpdateState.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = {
                    AppUpdateManager.dismissUpdateDialog(context, state.info.tagName)
                },
                title = {
                    Text("Actualización lista")
                },
                text = {
                    Text("El APK de ${state.info.tagName} se descargó y verificó correctamente. Toca Instalar para completar la actualización.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            AppUpdateManager.launchApkInstaller(context, state.apkFile)
                        }
                    ) {
                        Text("Instalar")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            AppUpdateManager.dismissUpdateDialog(context, state.info.tagName)
                        }
                    ) {
                        Text("Cerrar")
                    }
                }
            )
        }

        else -> Unit
    }
}
