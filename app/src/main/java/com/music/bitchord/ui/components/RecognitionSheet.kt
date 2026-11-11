package com.music.bitchord.ui.components

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.recognition.MusicRecognitionService
import com.music.bitchord.recognition.RecognitionHistory
import com.music.bitchord.recognition.RecognitionResult
import com.music.bitchord.recognition.RecognitionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionSheet(
    onDismiss: () -> Unit,
    onPlaySong: (RecognitionResult) -> Unit,
    onQueueSong: ((RecognitionResult) -> Unit)? = null,
) {
    val context = LocalContext.current
    val status by MusicRecognitionService.recognitionStatus.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(MusicRecognitionService.hasRecordPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            MusicRecognitionService.startRecognizing(context)
        }
    }

    // Once, as the sheet opens. A grant starts the listening from the
    // launcher's callback above, so this must not run again when it lands.
    LaunchedEffect(Unit) {
        if (hasPermission) {
            MusicRecognitionService.startRecognizing(context)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Back from the system settings with the permission now granted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!hasPermission && MusicRecognitionService.hasRecordPermission(context)) {
            hasPermission = true
            MusicRecognitionService.startRecognizing(context)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            MusicRecognitionService.stopRecognizing()
        }
    }

    // Every match goes into the history, whether or not it is then played.
    // Keyed on the result, so one match is written once however often this
    // recomposes; a second match of the same song replaces its row.
    val matched = (status as? RecognitionStatus.Success)?.result
    LaunchedEffect(matched) {
        if (matched != null) RecognitionHistory.save(context, matched)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.recognition_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                }
            }

            Spacer(Modifier.height(20.dp))

            if (!hasPermission) {
                PermissionView(
                    onAllow = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onOpenSettings = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            } else AnimatedContent(
                targetState = status,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                label = "recognitionStatusAnim",
            ) { currentStatus ->
                when (currentStatus) {
                    is RecognitionStatus.Listening -> {
                        ListeningView(onCancel = onDismiss)
                    }
                    is RecognitionStatus.Processing -> {
                        ProcessingView()
                    }
                    is RecognitionStatus.Success -> {
                        SuccessView(
                            result = currentStatus.result,
                            onPlay = {
                                onPlaySong(currentStatus.result)
                                onDismiss()
                            },
                            onQueue = onQueueSong?.let { queueAction ->
                                {
                                    queueAction(currentStatus.result)
                                    onDismiss()
                                }
                            },
                        )
                    }
                    is RecognitionStatus.NoMatch -> {
                        ErrorView(
                            message = stringResource(R.string.recognition_no_match),
                            onRetry = { MusicRecognitionService.startRecognizing(context) },
                        )
                    }
                    is RecognitionStatus.Error -> {
                        ErrorView(
                            message = stringResource(R.string.recognition_failed),
                            onRetry = { MusicRecognitionService.startRecognizing(context) },
                        )
                    }
                    is RecognitionStatus.Ready -> {
                        ListeningView(onCancel = onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeningView(onCancel: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "rippleAnim")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ringAlpha",
    )

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(150.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Ripple 1
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = ringAlpha
                    }
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            )
            // Ripple 2
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .graphicsLayer {
                        scaleX = pulseScale * 0.9f
                        scaleY = pulseScale * 0.9f
                        alpha = ringAlpha * 1.3f
                    }
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            )
            // Center circle
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.recognition_listening),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.recognition_hold_near),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProcessingView() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
            modifier = Modifier.size(54.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.recognition_identifying),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SuccessView(
    result: RecognitionResult,
    onPlay: () -> Unit,
    onQueue: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Album art
        val artUrl = result.coverArtHqUrl ?: result.coverArtUrl
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (artUrl != null) {
                AsyncImage(
                    model = artUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(160.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = result.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = result.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (!result.album.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = result.album,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onPlay,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(percent = 50),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.recognition_play_now))
            }

            if (onQueue != null) {
                OutlinedButton(
                    onClick = onQueue,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(percent = 50),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.recognition_add_to_queue))
                }
            }
        }
    }
}

/** Shown instead of the listening pulse while the microphone is not allowed. */
@Composable
private fun PermissionView(
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.recognition_permission_needed),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAllow,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.height(48.dp),
        ) {
            Text(stringResource(R.string.recognition_allow_microphone))
        }
        // The system stops showing its dialog after a second refusal; the
        // app's settings page is then the only place left to allow it.
        TextButton(onClick = onOpenSettings, modifier = Modifier.height(48.dp)) {
            Text(stringResource(R.string.recognition_open_settings))
        }
    }
}

@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.height(44.dp),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.try_again))
        }
    }
}
