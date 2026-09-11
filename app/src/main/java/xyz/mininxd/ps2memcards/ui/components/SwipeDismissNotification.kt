package xyz.mininxd.ps2memcards.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A notification / toast wrapper supporting swipe-to-dismiss in three directions:
 * - Swipe left
 * - Swipe right
 * - Swipe down (bottom)
 */
@Composable
fun SwipeDismissNotification(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var isSettling by remember { mutableStateOf(false) }
    var isDismissed by remember { mutableStateOf(false) }

    val animOffsetX = remember { Animatable(0f) }
    val animOffsetY = remember { Animatable(0f) }
    val animAlpha = remember { Animatable(1f) }

    val coroutineScope = rememberCoroutineScope()
    val velocityTracker = remember { VelocityTracker() }

    val width = size.width.toFloat().coerceAtLeast(1f)
    val height = size.height.toFloat().coerceAtLeast(1f)

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .offset {
                val currentX = if (!isSettling) dragX else animOffsetX.value
                val currentY = if (!isSettling) dragY else animOffsetY.value
                IntOffset(currentX.roundToInt(), currentY.roundToInt())
            }
            .graphicsLayer {
                val currentAlpha = if (!isSettling) {
                    val progressX = (abs(dragX) / width).coerceIn(0f, 1f)
                    val progressY = (dragY.coerceAtLeast(0f) / height).coerceIn(0f, 1f)
                    (1f - maxOf(progressX, progressY) * 0.7f).coerceIn(0.2f, 1f)
                } else {
                    animAlpha.value
                }
                alpha = currentAlpha
            }
            .pointerInput(isDismissed) {
                if (isDismissed) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        isSettling = false
                        velocityTracker.resetTracking()
                    },
                    onDragEnd = {
                        val velocity = velocityTracker.calculateVelocity()
                        val vx = velocity.x
                        val vy = velocity.y

                        val isRight = dragX > width * 0.30f || (vx > 800f && dragX > 20f)
                        val isLeft = dragX < -width * 0.30f || (vx < -800f && dragX < -20f)
                        val isBottom = dragY > height * 0.35f || (vy > 800f && dragY > 20f)

                        if (isRight || isLeft || isBottom) {
                            isDismissed = true
                            val normX = abs(dragX) / width
                            val normY = dragY.coerceAtLeast(0f) / height
                            val normVx = abs(vx) / 1000f
                            val normVy = vy.coerceAtLeast(0f) / 1000f
                            val scoreX = maxOf(normX, normVx)
                            val scoreY = maxOf(normY, normVy)

                            val dismissRight = (isRight && dragX >= 0f) && (scoreX >= scoreY || !isBottom)
                            val dismissLeft = (isLeft && dragX < 0f) && (scoreX >= scoreY || !isBottom)

                            val targetX: Float
                            val targetY: Float

                            if (dismissRight) {
                                targetX = width * 1.5f
                                targetY = dragY
                            } else if (dismissLeft) {
                                targetX = -width * 1.5f
                                targetY = dragY
                            } else {
                                targetX = dragX
                                targetY = height * 2.5f + 100f
                            }

                            coroutineScope.launch {
                                val startAlpha = (1f - maxOf(abs(dragX) / width, dragY.coerceAtLeast(0f) / height) * 0.7f).coerceIn(0.2f, 1f)
                                animAlpha.snapTo(startAlpha)
                                animOffsetX.snapTo(dragX)
                                animOffsetY.snapTo(dragY)
                                isSettling = true

                                val animJobX = launch { animOffsetX.animateTo(targetX, tween(180, easing = FastOutLinearInEasing)) }
                                val animJobY = launch { animOffsetY.animateTo(targetY, tween(180, easing = FastOutLinearInEasing)) }
                                val animJobAlpha = launch { animAlpha.animateTo(0f, tween(180, easing = FastOutLinearInEasing)) }

                                animJobX.join()
                                animJobY.join()
                                animJobAlpha.join()

                                onDismiss()
                            }
                        } else {
                            coroutineScope.launch {
                                val startAlpha = (1f - maxOf(abs(dragX) / width, dragY.coerceAtLeast(0f) / height) * 0.7f).coerceIn(0.2f, 1f)
                                animAlpha.snapTo(startAlpha)
                                animOffsetX.snapTo(dragX)
                                animOffsetY.snapTo(dragY)
                                isSettling = true

                                val animJobX = launch { animOffsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                                val animJobY = launch { animOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                                val animJobAlpha = launch { animAlpha.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow)) }

                                animJobX.join()
                                animJobY.join()
                                animJobAlpha.join()

                                dragX = 0f
                                dragY = 0f
                                isSettling = false
                            }
                        }
                    },
                    onDragCancel = {
                        coroutineScope.launch {
                            val startAlpha = (1f - maxOf(abs(dragX) / width, dragY.coerceAtLeast(0f) / height) * 0.7f).coerceIn(0.2f, 1f)
                            animAlpha.snapTo(startAlpha)
                            animOffsetX.snapTo(dragX)
                            animOffsetY.snapTo(dragY)
                            isSettling = true

                            val animJobX = launch { animOffsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                            val animJobY = launch { animOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                            val animJobAlpha = launch { animAlpha.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow)) }

                            animJobX.join()
                            animJobY.join()
                            animJobAlpha.join()

                            dragX = 0f
                            dragY = 0f
                            isSettling = false
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        dragX += dragAmount.x
                        val newY = dragY + dragAmount.y
                        dragY = if (newY < 0f) newY * 0.2f else newY
                    }
                )
            }
    ) {
        content()
    }
}
