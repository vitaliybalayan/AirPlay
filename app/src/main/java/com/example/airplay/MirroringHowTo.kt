package com.example.airplay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private const val STEP_DURATION_MS = 2800L
private const val STEP_COUNT = 4

private val PhoneBackground = Color(0xFF101418)
private val Accent = Color(0xFF7AA7FF)
private val Muted = Color(0xFF8A96A6)

/**
 * Full-screen looping walkthrough of starting Screen Mirroring on an iPhone.
 * Drawn with Compose so it needs no bundled video and stays crisp at any TV resolution.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MirroringHowToOverlay(isRussian: Boolean, onClose: () -> Unit) {
    BackHandler(onBack = onClose)

    var step by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(STEP_DURATION_MS)
            step = (step + 1) % STEP_COUNT
        }
    }
    LaunchedEffect(step) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(STEP_DURATION_MS.toInt(), easing = LinearEasing))
    }

    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeFocus.requestFocus() }

    val steps = if (isRussian) {
        listOf(
            "Проведите вниз от правого верхнего угла экрана iPhone",
            "Нажмите «Повтор экрана»",
            "Выберите «Android TV»",
            "Готово — экран iPhone на телевизоре"
        )
    } else {
        listOf(
            "Swipe down from the top-right corner of the iPhone",
            "Tap Screen Mirroring",
            "Choose \"Android TV\"",
            "Done — the iPhone screen is on the TV"
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06080B))
            .padding(horizontal = 64.dp, vertical = 32.dp)
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // TVs lay out at ~960x540dp; the mockup is drawn at phone proportions and scaled to fit.
            Box(modifier = Modifier.size(width = 224.dp, height = 464.dp), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.requiredSize(width = 280.dp, height = 580.dp).scale(0.8f)) {
                    PhoneMockup(step = step, progress = progress.value, isRussian = isRussian)
                }
            }
            Spacer(modifier = Modifier.width(56.dp))
            Column(modifier = Modifier.widthIn(max = 520.dp)) {
                Text(
                    text = if (isRussian) "Как смотреть видео на ТВ" else "Watch video on the TV",
                    color = Color.White,
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(24.dp))
                steps.forEachIndexed { index, text ->
                    StepRow(number = index + 1, text = text, active = index == step)
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isRussian) {
                        "Затем откройте видео, разверните его на весь экран и поверните iPhone горизонтально."
                    } else {
                        "Then open the video, make it full screen and turn the iPhone sideways."
                    },
                    color = Muted,
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                AirPlayButton(
                    onClick = onClose,
                    modifier = Modifier.focusRequester(closeFocus)
                ) {
                    Text(text = if (isRussian) "Понятно" else "Got it", fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, text: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (active) Accent else Color(0xFF1F2630)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                color = if (active) Color(0xFF06080B) else Muted,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = text,
            color = if (active) Color.White else Color(0xFF5F6B7A),
            fontSize = 18.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 24.sp
        )
    }
}

@Composable
private fun PhoneMockup(step: Int, progress: Float, isRussian: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 280.dp, height = 580.dp)
            .clip(RoundedCornerShape(44.dp))
            .background(Color(0xFF2A313B))
            .padding(10.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(PhoneBackground)
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
            label = "howto-step"
        ) { shownStep ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (shownStep) {
                    0 -> HomeScreenStep(progress)
                    1 -> ControlCenterStep(progress, isRussian)
                    2 -> DeviceListStep(progress, isRussian)
                    else -> DoneStep(progress, isRussian)
                }
            }
        }
        // Dynamic Island
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .size(width = 90.dp, height = 26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color.Black)
        )
    }
}

@Composable
private fun BoxScope.HomeScreenStep(progress: Float) {
    val iconColors = listOf(
        Color(0xFF3B6FD8), Color(0xFF2FA866), Color(0xFFE0A030), Color(0xFFD9534F),
        Color(0xFF8E5BD9), Color(0xFF2CA6B8), Color(0xFF5A6575), Color(0xFFE07A3A)
    )
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 80.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        repeat(4) { rowIndex ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(4) { col ->
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(iconColors[(rowIndex * 4 + col) % iconColors.size].copy(alpha = 0.55f))
                    )
                }
            }
        }
    }
    // Finger swiping down from the top-right corner.
    Finger(x = 210.dp, y = (16 + 220 * progress).dp, pressed = progress < 0.85f)
}

@Composable
private fun BoxScope.ControlCenterStep(progress: Float, isRussian: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A2129))
            .padding(top = 64.dp, start = 18.dp, end = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile(width = 112.dp, height = 112.dp) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Dot(Color(0xFFE0A030)); Dot(Color(0xFF2FA866)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Dot(Color(0xFF3B6FD8)); Dot(Color(0xFF3B6FD8)) }
                }
            }
            Tile(width = 112.dp, height = 112.dp) {}
        }
        val highlight = progress > 0.35f
        val pressed = progress > 0.6f
        Tile(
            width = 112.dp,
            height = 54.dp,
            borderColor = if (highlight) Accent else null,
            modifier = Modifier.scale(if (pressed) 0.94f else 1f)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MirrorIcon(size = 20.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isRussian) "Повтор\nэкрана" else "Screen\nMirroring",
                    color = Color.White,
                    fontSize = 10.sp,
                    lineHeight = 12.sp
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(4) { Tile(width = 49.dp, height = 49.dp) {} }
        }
    }
    // Finger moves onto the Screen Mirroring tile and taps it.
    val fingerY = (40 + 170 * progress.coerceAtMost(0.55f) / 0.55f).dp
    Finger(x = 70.dp, y = fingerY, pressed = progress > 0.6f)
}

@Composable
private fun BoxScope.DeviceListStep(progress: Float, isRussian: Boolean) {
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF232B35))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MirrorIcon(size = 22.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isRussian) "Повтор экрана" else "Screen Mirroring",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        val chosen = progress > 0.55f
        DeviceRow(name = "Android TV", highlighted = progress > 0.3f, checked = chosen)
        Spacer(modifier = Modifier.height(8.dp))
        DeviceRow(name = "MacBook", highlighted = false, checked = false, dim = true)
    }
    Finger(x = 120.dp, y = (330 - 60 * progress.coerceAtMost(0.5f) * 2).dp, pressed = progress > 0.55f)
}

@Composable
private fun BoxScope.DoneStep(progress: Float, isRussian: Boolean) {
    Column(
        modifier = Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // TV showing the mirrored phone screen.
        Box(
            modifier = Modifier
                .size(width = 200.dp, height = 116.dp)
                .border(4.dp, Color(0xFF5A6575), RoundedCornerShape(8.dp))
                .padding(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Accent.copy(alpha = 0.25f + 0.35f * progress)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 84.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A2129))
            )
        }
        Box(modifier = Modifier.size(width = 60.dp, height = 6.dp).background(Color(0xFF5A6575)))
        Spacer(modifier = Modifier.height(28.dp))
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color(0xFF37D67A).copy(alpha = progress.coerceIn(0.2f, 1f))),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "✓", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (isRussian) "Готово" else "Done",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DeviceRow(name: String, highlighted: Boolean, checked: Boolean, dim: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) Accent.copy(alpha = 0.25f) else Color(0xFF2C3540))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TvIcon(size = 20.dp, color = if (dim) Muted else Color.White)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = name,
            color = if (dim) Muted else Color.White,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        if (checked) {
            Text(text = "✓", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Tile(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    content: @Composable BoxScope.() -> Unit
) {
    var tileModifier = modifier
        .size(width = width, height = height)
        .clip(RoundedCornerShape(18.dp))
        .background(Color(0xFF2C3540))
    if (borderColor != null) {
        tileModifier = tileModifier.border(3.dp, borderColor, RoundedCornerShape(18.dp))
    }
    Box(modifier = tileModifier, content = content)
}

@Composable
private fun Dot(color: Color) {
    Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(color))
}

@Composable
private fun MirrorIcon(size: Dp) {
    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size * 0.75f)
                .border(2.dp, Color.White, RoundedCornerShape(3.dp))
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(size * 0.75f)
                .background(Color(0xFF2C3540))
                .border(2.dp, Color.White, RoundedCornerShape(3.dp))
        )
    }
}

@Composable
private fun TvIcon(size: Dp, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = size, height = size * 0.65f)
                .border(2.dp, color, RoundedCornerShape(3.dp))
        )
        Box(modifier = Modifier.size(width = size * 0.5f, height = 2.dp).background(color))
    }
}

@Composable
private fun BoxScope.Finger(x: Dp, y: Dp, pressed: Boolean) {
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = x, y = y)
            .size(if (pressed) 30.dp else 38.dp)
            .alpha(0.85f)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.55f))
            .border(2.dp, Color.White, CircleShape)
    )
}
