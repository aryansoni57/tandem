// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.findphone

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tandem.R
import kotlinx.coroutines.launch

/**
 * Full-bleed tertiary screen. The core pulses while ringing. Swiping the handle past half its
 * track, or tapping it, stops the ring.
 */
@Composable
fun FindMyPhoneScreen(
    deviceName: String,
    onStop: () -> Unit,
) {
    val pulse = rememberInfiniteTransition(label = "ring")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900), repeatMode = RepeatMode.Reverse),
        label = "ringScale",
    )
    val haloAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900), repeatMode = RepeatMode.Reverse),
        label = "ringAlpha",
    )
    val scope = rememberCoroutineScope()
    val knob = remember { Animatable(0f) }
    val density = LocalDensity.current
    val trackPx = with(density) { 240.dp.toPx() }
    val knobPx = with(density) { 56.dp.toPx() }
    val knobDp = with(density) { knob.value.toDp() }
    val stopThreshold = (trackPx - knobPx) * 0.5f

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.tertiary)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.85f),
            )
            Text(
                stringResource(R.string.find_title, deviceName),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
            Box(
                modifier =
                    Modifier
                        .size(200.dp)
                        .scale(scale)
                        .alpha(haloAlpha)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onTertiary),
            )
            Surface(
                modifier = Modifier.size(128.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onTertiary,
                contentColor = MaterialTheme.colorScheme.tertiary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(56.dp))
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.find_ringing_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.85f),
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.CenterStart,
            ) {
                Surface(
                    modifier =
                        Modifier
                            .size(56.dp)
                            .offset(x = knobDp)
                            .clickable(onClickLabel = stringResource(R.string.find_stop)) { onStop() }
                            .draggable(
                                orientation = Orientation.Horizontal,
                                state =
                                    rememberDraggableState { delta ->
                                        scope.launch {
                                            knob.snapTo(
                                                (knob.value + delta).coerceIn(0f, trackPx - knobPx),
                                            )
                                        }
                                    },
                                onDragStopped = {
                                    scope.launch {
                                        if (knob.value >= stopThreshold) onStop() else knob.animateTo(0f)
                                    }
                                },
                            ),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onTertiary,
                    contentColor = MaterialTheme.colorScheme.tertiary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.find_stop))
                    }
                }
            }
            Text(
                stringResource(R.string.find_swipe_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.85f),
            )
        }
    }
}
