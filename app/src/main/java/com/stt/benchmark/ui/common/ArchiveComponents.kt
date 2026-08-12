package com.stt.benchmark.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stt.benchmark.R
import com.stt.benchmark.ui.theme.ArchiveCopper
import com.stt.benchmark.ui.theme.ArchiveFog
import com.stt.benchmark.ui.theme.ArchiveHairline
import com.stt.benchmark.ui.theme.ArchiveInk
import com.stt.benchmark.ui.theme.ArchiveMoss
import com.stt.benchmark.ui.theme.ArchivePaper
import kotlinx.coroutines.delay

enum class ArchiveStatusTone { NEUTRAL, ACTIVE, COMPLETE, ERROR }

/** Applies the Android accessibility minimum interactive size without changing a component's style. */
fun Modifier.archiveTouchTarget(): Modifier = sizeIn(minWidth = 48.dp, minHeight = 48.dp)

@Composable
fun SoundThread(
    amplitude: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    val latestAmplitude by rememberUpdatedState(amplitude.coerceIn(0f, 1f))
    val inactiveColor = MaterialTheme.colorScheme.outline
    val samples = remember { mutableStateListOf<Float>().apply { repeat(48) { add(0f) } } }
    LaunchedEffect(active, reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        while (active) {
            samples.removeAt(0)
            samples.add(latestAmplitude)
            delay(120)
        }
        samples.indices.forEach { samples[it] = 0f }
    }
    val levelDescription = when {
        !active -> "음성 입력 대기"
        latestAmplitude < 0.2f -> "입력 음량 낮음"
        latestAmplitude < 0.65f -> "입력 음량 보통"
        else -> "입력 음량 높음"
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics { contentDescription = levelDescription },
    ) {
        val center = size.height / 2f
        if (reducedMotion) {
            val half = latestAmplitude * size.height * 0.38f
            drawLine(
                color = if (active) ArchiveCopper else ArchiveHairline,
                start = Offset(0f, center - half),
                end = Offset(size.width, center + half),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            return@Canvas
        }
        if (samples.size < 2) return@Canvas
        val step = size.width / (samples.size - 1)
        val path = Path().apply { moveTo(0f, center) }
        samples.forEachIndexed { index, sample ->
            val direction = if (index % 2 == 0) -1 else 1
            path.lineTo(index * step, center + direction * sample * size.height * 0.42f)
        }
        drawPath(
            path = path,
            color = if (active) ArchiveCopper else inactiveColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
fun RecordControl(
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    disabledReason: String = "녹음 기능을 준비하고 있습니다",
    actionLabel: String? = null,
    stateLabel: String? = null,
    reducedMotion: Boolean = false,
) {
    val targetScale = if (active) 1f else 0.94f
    val innerScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (reducedMotion) snap() else tween(260),
        label = "record-control",
    )
    val description = actionLabel ?: when {
        !enabled -> "녹음 기능 준비 중"
        active -> "녹음 정지"
        else -> "녹음 시작"
    }
    Box(
        modifier = modifier
            .size(184.dp)
            .clip(CircleShape)
            .background(if (active) ArchiveCopper else MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = if (active) ArchiveCopper else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description
                stateDescription = stateLabel ?: when {
                    !enabled -> disabledReason
                    active -> "녹음 중"
                    else -> "녹음 대기"
                }
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size((142 * innerScale).dp)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (active) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = null,
                tint = when {
                    active && enabled -> ArchiveInk
                    enabled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(46.dp),
            )
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: ArchiveStatusTone = ArchiveStatusTone.NEUTRAL,
) {
    val background = when (tone) {
        ArchiveStatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
        ArchiveStatusTone.ACTIVE -> ArchiveCopper
        ArchiveStatusTone.COMPLETE -> ArchiveMoss
        ArchiveStatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val foreground = when (tone) {
        ArchiveStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        ArchiveStatusTone.ACTIVE -> ArchiveInk
        ArchiveStatusTone.COMPLETE -> ArchiveInk
        ArchiveStatusTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(100))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(foreground),
        )
        Text(text, style = MaterialTheme.typography.labelLarge, color = foreground)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

@Composable
fun ArchiveEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    @DrawableRes imageRes: Int = R.drawable.art_library_empty,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 30.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArchiveArtwork(
            imageRes = imageRes,
            contentDescription = "첫 기록을 기다리는 빈 보관함",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(18.dp)),
        )
        Spacer(Modifier.height(22.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.sizeIn(minHeight = 48.dp)) { action() }
        }
    }
}

@Composable
fun ArchiveArtwork(
    @DrawableRes imageRes: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Image(
        painter = painterResource(imageRes),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.background(ArchiveInk),
    )
}

fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
