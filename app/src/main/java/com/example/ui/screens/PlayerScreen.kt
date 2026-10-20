package com.example.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.model.LiveMatch
import com.example.data.model.StreamSource
import com.example.player.ExoPlayerManager
import com.example.ui.components.MatchCard
import com.example.ui.components.MultipleStreamsModalDialog
import com.example.ui.components.PulsingLiveBadge
import com.example.ui.components.QualityBadge
import com.example.ui.components.TeamFlagBadge
import com.example.ui.theme.*

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    match: LiveMatch?,
    channelTitle: String? = null,
    streams: List<StreamSource>,
    playerManager: ExoPlayerManager,
    onBackClick: () -> Unit,
    otherMatches: List<LiveMatch> = emptyList(),
    onSwitchMatch: (LiveMatch) -> Unit = {},
    onEnterPiP: () -> Unit = {}
) {
    val context = LocalContext.current
    val isPlaying by playerManager.isPlaying.collectAsState()
    val isBuffering by playerManager.isBuffering.collectAsState()
    val currentStream by playerManager.currentStream.collectAsState()
    val errorMessage by playerManager.errorMessage.collectAsState()

    var isFullscreen by remember { mutableStateOf(false) }
    var isControlsLocked by remember { mutableStateOf(false) }
    var showStreamPickerModal by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // Start playback when stream is set
    LaunchedEffect(streams) {
        if (streams.isNotEmpty() && currentStream == null) {
            val defaultStream = streams.find { it.isDefault } ?: streams.first()
            playerManager.playStream(defaultStream)
        }
    }

    // Handle back button
    BackHandler {
        if (isFullscreen) {
            isFullscreen = false
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            playerManager.pause()
            onBackClick()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    val titleText = when {
        match != null -> "${match.team1Code} vs ${match.team2Code}"
        channelTitle != null -> channelTitle
        else -> "Live Sports Stream"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyDark)
    ) {
        if (isFullscreen) {
            // Fullscreen Player
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = playerManager.getPlayer()
                            useController = false
                            this.resizeMode = resizeMode
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Overlay Controls
                FullscreenPlayerOverlay(
                    title = titleText,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    isLocked = isControlsLocked,
                    currentStream = currentStream,
                    onToggleLock = { isControlsLocked = !isControlsLocked },
                    onPlayPause = { if (isPlaying) playerManager.pause() else playerManager.resume() },
                    onRewind = { playerManager.seekRelative(-10_000) },
                    onForward = { playerManager.seekRelative(10_000) },
                    onToggleFullscreen = {
                        isFullscreen = false
                        (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    },
                    onToggleAspectRatio = {
                        resizeMode = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    onOpenStreams = { showStreamPickerModal = true },
                    onBack = {
                        isFullscreen = false
                        (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                )
            }
        } else {
            // Portrait View: Video on top + horizontal streams + match info + other matches
            Column(modifier = Modifier.fillMaxSize()) {
                // Video Container (16:9 Aspect Ratio)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = playerManager.getPlayer()
                                useController = false
                                this.resizeMode = resizeMode
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Video Overlay Controls
                    PortraitVideoControlsOverlay(
                        title = titleText,
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
                        errorMessage = errorMessage,
                        onBack = onBackClick,
                        onPlayPause = { if (isPlaying) playerManager.pause() else playerManager.resume() },
                        onRewind = { playerManager.seekRelative(-10_000) },
                        onForward = { playerManager.seekRelative(10_000) },
                        onFullscreen = {
                            isFullscreen = true
                            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        },
                        onPiP = onEnterPiP,
                        onRetry = {
                            currentStream?.let { playerManager.playStream(it) }
                        },
                        onPickAnotherStream = { showStreamPickerModal = true }
                    )
                }

                // Horizontal Streams Available Buttons (matching screenshots 6, 7 & 9)
                if (streams.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NavySurface)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        streams.forEach { stream ->
                            val isSelected = currentStream?.id == stream.id
                            StreamChipButton(
                                stream = stream,
                                isSelected = isSelected,
                                onClick = { playerManager.playStream(stream) }
                            )
                        }

                        // More streams dialog button
                        OutlinedButton(
                            onClick = { showStreamPickerModal = true },
                            modifier = Modifier.height(38.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(NavyBorder)),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = NavySurfaceVariant)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("All Servers", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Match details card or channel banner
                if (match != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .border(1.dp, NavyBorder, RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = NavySurface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TeamFlagBadge(flag = match.team1FlagUrl, code = match.team1Code)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(match.team1Name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary))
                                    Text(match.team1Code, style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary))
                                }
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PulsingLiveBadge()
                                Text(
                                    text = match.timeOrScore,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = CyanAccent,
                                        fontSize = 12.sp
                                    )
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(match.team2Name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary), textAlign = TextAlign.End)
                                    Text(match.team2Code, style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary), textAlign = TextAlign.End)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                TeamFlagBadge(flag = match.team2FlagUrl, code = match.team2Code)
                            }
                        }
                    }
                }

                // Other Live Matches List (matching screenshot 6)
                if (otherMatches.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = "OTHER LIVE & UPCOMING MATCHES",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary,
                                fontSize = 11.sp,
                                letterSpacing = 1.sp
                            ),
                            modifier = Modifier.padding(vertical = 6.dp)
                        )

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(otherMatches, key = { it.id }) { other ->
                                MatchCard(
                                    match = other,
                                    onClick = { onSwitchMatch(other) },
                                    onFavoriteToggle = { /* handled */ },
                                    onStreamSelectClick = { onSwitchMatch(other) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Multiple Streams Selection Modal Dialog (matching screenshot 7)
        if (showStreamPickerModal && streams.isNotEmpty()) {
            MultipleStreamsModalDialog(
                streams = streams,
                currentSelectedStream = currentStream,
                onStreamSelected = { selected ->
                    playerManager.playStream(selected)
                },
                onDismiss = { showStreamPickerModal = false }
            )
        }
    }
}

@Composable
fun StreamChipButton(
    stream: StreamSource,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) CyanAccent else NavyBorder
    val containerBg = if (isSelected) CyanAccent.copy(alpha = 0.15f) else NavySurfaceVariant

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag("stream_chip_${stream.name.lowercase().replace(" ", "_")}"),
        color = containerBg,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
                tint = if (isSelected) CyanAccent else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stream.name,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) CyanAccent else TextPrimary,
                    fontSize = 12.sp
                )
            )
            Spacer(modifier = Modifier.width(6.dp))
            QualityBadge(quality = stream.quality)
        }
    }
}

@Composable
fun PortraitVideoControlsOverlay(
    title: String,
    isPlaying: Boolean,
    isBuffering: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onFullscreen: () -> Unit,
    onPiP: () -> Unit,
    onRetry: () -> Unit,
    onPickAnotherStream: () -> Unit
) {
    var showControls by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { showControls = !showControls }
    ) {
        if (errorMessage != null) {
            // Error overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = LiveRed, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                        ) {
                            Text("Retry", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onPickAnotherStream,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                        ) {
                            Text("Switch Server")
                        }
                    }
                }
            }
        } else if (isBuffering) {
            CircularProgressIndicator(
                color = CyanAccent,
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.Center)
            )
        }

        if (showControls && errorMessage == null) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPiP, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
                    }
                    IconButton(onClick = onFullscreen, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen", tint = Color.White)
                    }
                }
            }

            // Center Play / Pause & Seek
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onRewind, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White, modifier = Modifier.size(32.dp))
                }

                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .border(1.5.dp, CyanAccent, CircleShape)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = CyanAccent,
                        modifier = Modifier.size(34.dp)
                    )
                }

                IconButton(onClick = onForward, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
fun FullscreenPlayerOverlay(
    title: String,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isLocked: Boolean,
    currentStream: StreamSource?,
    onToggleLock: () -> Unit,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onOpenStreams: () -> Unit,
    onBack: () -> Unit
) {
    var showControls by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { showControls = !showControls }
    ) {
        if (isBuffering) {
            CircularProgressIndicator(
                color = CyanAccent,
                modifier = Modifier
                    .size(54.dp)
                    .align(Alignment.Center)
            )
        }

        // Lock button is always visible on right
        IconButton(
            onClick = onToggleLock,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(42.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .border(1.dp, if (isLocked) GoldYellow else Color.White, CircleShape)
        ) {
            Icon(
                if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = "Lock",
                tint = if (isLocked) GoldYellow else Color.White,
                modifier = Modifier.size(22.dp)
            )
        }

        if (showControls && !isLocked) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    currentStream?.let { s ->
                        QualityBadge(quality = s.quality)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    IconButton(onClick = onOpenStreams) {
                        Icon(Icons.Default.Tune, contentDescription = "Streams", tint = CyanAccent)
                    }
                    IconButton(onClick = onToggleAspectRatio) {
                        Icon(Icons.Default.AspectRatio, contentDescription = "Aspect Ratio", tint = Color.White)
                    }
                    IconButton(onClick = onToggleFullscreen) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = "Exit Fullscreen", tint = Color.White)
                    }
                }
            }

            // Center Controls
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onRewind, modifier = Modifier.size(54.dp)) {
                    Icon(Icons.Default.Replay10, contentDescription = "Rewind", tint = Color.White, modifier = Modifier.size(40.dp))
                }

                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .border(2.dp, CyanAccent, CircleShape)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = CyanAccent,
                        modifier = Modifier.size(44.dp)
                    )
                }

                IconButton(onClick = onForward, modifier = Modifier.size(54.dp)) {
                    Icon(Icons.Default.Forward10, contentDescription = "Forward", tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
        }
    }
}
