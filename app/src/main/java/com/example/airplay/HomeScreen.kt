package com.example.airplay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.cos
import kotlin.math.sin

private val Ink = Color(0xFF05070A)
private val Accent = Color(0xFF7AA7FF)
private val AccentTeal = Color(0xFF5EE6C8)
private val Ready = Color(0xFF37D67A)
private val Secondary = Color(0xFFB9C7D6)
private val Tertiary = Color(0xFF6B7684)

/** Slowly drifting colour glows over near-black; cheap enough to run behind everything. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing)),
        label = "aurora-phase"
    )
    Canvas(modifier = modifier.fillMaxSize().background(Ink)) {
        val w = size.width
        val h = size.height
        fun glow(color: Color, cx: Float, cy: Float, radius: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color, Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius
                ),
                radius = radius,
                center = Offset(cx, cy)
            )
        }
        glow(Accent.copy(alpha = 0.22f), w * (0.22f + 0.06f * cos(phase)), h * (0.30f + 0.08f * sin(phase)), w * 0.45f)
        glow(AccentTeal.copy(alpha = 0.12f), w * (0.80f + 0.05f * sin(phase * 2)), h * (0.75f + 0.06f * cos(phase)), w * 0.40f)
        glow(Color(0xFF9B7BFF).copy(alpha = 0.10f), w * (0.60f + 0.08f * cos(phase + 2f)), h * (0.15f + 0.05f * sin(phase + 1f)), w * 0.35f)
    }
}

/** Screen-with-triangle mark drawn as vectors, so it stays sharp at any TV resolution. */
@Composable
fun AirPlayGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.07f
        val screenHeight = size.height * 0.62f
        val gap = size.width * 0.30f
        val radius = CornerRadius(size.width * 0.10f)
        // Screen outline, left open at the bottom for the triangle.
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, screenHeight),
            cornerRadius = radius,
            style = Stroke(width = stroke)
        )
        drawRect(
            color = Color.Transparent,
            topLeft = Offset((size.width - gap) / 2, screenHeight - stroke),
            size = Size(gap, stroke * 2),
            blendMode = androidx.compose.ui.graphics.BlendMode.Clear
        )
        val triangle = Path().apply {
            moveTo(size.width / 2, size.height * 0.52f)
            lineTo(size.width * 0.78f, size.height)
            lineTo(size.width * 0.22f, size.height)
            close()
        }
        drawPath(triangle, color)
    }
}

/** Rings expanding out of the glyph: "waiting for a sender" without any text. */
@Composable
fun BroadcastRings(modifier: Modifier = Modifier, color: Color = Accent) {
    val transition = rememberInfiniteTransition(label = "rings")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "rings-t"
    )
    Canvas(modifier = modifier) {
        val maxRadius = size.minDimension / 2
        for (i in 0 until 3) {
            val p = (t + i / 3f) % 1f
            drawCircle(
                color = color.copy(alpha = (1f - p) * 0.45f),
                radius = maxRadius * (0.35f + 0.65f * p),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

/** Glyph centred in its rings; shared by the splash and the home screen. */
@Composable
fun AirPlayEmblem(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        BroadcastRings(modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .size(size * 0.46f)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF2B4C8F), Color(0xFF15233F)))),
            contentAlignment = Alignment.Center
        ) {
            AirPlayGlyph(
                modifier = Modifier.size(size * 0.22f).graphicsLayer { alpha = 0.99f },
                color = Color.White
            )
        }
    }
}

fun localNetworkAddress(): String? = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address }
        ?.hostAddress
} catch (e: Exception) {
    null
}

@Composable
fun IdleHomeScreen(
    deviceName: String,
    isRussian: Boolean,
    autostartEnabled: Boolean,
    onEnableAutostart: () -> Unit,
    animateIn: Boolean
) {
    val address = remember { localNetworkAddress() }
    // Elements rise in one after another once the splash hands over.
    val enter = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    fun Modifier.stagger(index: Int) = graphicsLayer {
        val local = ((enter.value - index * 0.12f) / 0.64f).coerceIn(0f, 1f)
        alpha = local
        translationY = (1f - local) * 24.dp.toPx()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackground()
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 72.dp, vertical = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AirPlayEmblem(size = 200.dp, modifier = Modifier.stagger(0).padding(start = 0.dp))
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "AIRPLAY",
                    color = Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    modifier = Modifier.stagger(1)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = deviceName,
                    color = Color.White,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.stagger(1)
                )
                Spacer(modifier = Modifier.height(14.dp))
                ReadyChip(
                    text = if (isRussian) "Готов к трансляции" else "Ready to stream",
                    modifier = Modifier.stagger(2)
                )
            }
            Spacer(modifier = Modifier.width(48.dp))
            StepsCard(
                deviceName = deviceName,
                isRussian = isRussian,
                autostartEnabled = autostartEnabled,
                onEnableAutostart = onEnableAutostart,
                modifier = Modifier.width(420.dp).stagger(3)
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 72.dp, vertical = 24.dp)
                .stagger(4),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (address != null) {
                Text(
                    text = (if (isRussian) "Сеть · " else "Network · ") + address,
                    color = Tertiary,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "© vitaliybalayan", color = Color(0xFF3E4752), fontSize = 13.sp)
        }
    }
}

@Composable
private fun ReadyChip(text: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ready")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "ready-pulse"
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Ready.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(pulse)
                .clip(CircleShape)
                .background(Ready)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = text, color = Color(0xFF7EE2A8), fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StepsCard(
    deviceName: String,
    isRussian: Boolean,
    autostartEnabled: Boolean,
    onEnableAutostart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = if (isRussian) {
        listOf(
            "Откройте Пункт управления на iPhone, iPad или Mac",
            "Нажмите «Повтор экрана» или значок AirPlay",
            "Выберите «$deviceName»"
        )
    } else {
        listOf(
            "Open Control Center on your iPhone, iPad, or Mac",
            "Tap Screen Mirroring or the AirPlay icon",
            "Choose \"$deviceName\""
        )
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(28.dp)
    ) {
        Text(
            text = if (isRussian) "Как подключиться" else "How to connect",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(20.dp))
        steps.forEachIndexed { index, text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (index + 1).toString(),
                        color = Accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Text(text = text, color = Secondary, fontSize = 16.sp, lineHeight = 21.sp)
            }
            if (index < steps.lastIndex) Spacer(modifier = Modifier.height(14.dp))
        }
        Spacer(modifier = Modifier.height(22.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.08f))
        )
        Spacer(modifier = Modifier.height(18.dp))
        if (autostartEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "✓", color = Ready, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isRussian) {
                        "Автозапуск включён — экран откроется сам"
                    } else {
                        "Autostart is on — the screen opens by itself"
                    },
                    color = Tertiary,
                    fontSize = 14.sp
                )
            }
        } else {
            val focus = remember { FocusRequester() }
            // The only action on the home screen: focus it so a single OK press works.
            LaunchedEffect(Unit) { focus.requestFocus() }
            AirPlayButton(onClick = onEnableAutostart, modifier = Modifier.focusRequester(focus)) {
                Text(
                    text = if (isRussian) "Включить автозапуск AirPlay" else "Turn on AirPlay autostart",
                    fontSize = 15.sp
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (isRussian) {
                    "В списке «Поверх других приложений» найдите AirPlay и включите"
                } else {
                    "In \"Display over other apps\", find AirPlay and turn it on"
                },
                color = Tertiary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

/** Short branded intro on a cold start; the home screen fades in underneath. */
@Composable
fun SplashIntro(isRussian: Boolean, onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(1500, easing = FastOutSlowInEasing))
        onFinished()
    }
    val p = progress.value
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AuroraBackground(modifier = Modifier.alpha((p * 2f).coerceAtMost(1f)))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AirPlayEmblem(
                size = 240.dp,
                modifier = Modifier.graphicsLayer {
                    val s = 0.7f + 0.3f * (p / 0.6f).coerceAtMost(1f)
                    scaleX = s
                    scaleY = s
                    alpha = (p / 0.4f).coerceAtMost(1f)
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "AirPlay",
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayer {
                    val local = ((p - 0.35f) / 0.4f).coerceIn(0f, 1f)
                    alpha = local
                    translationY = (1f - local) * 16.dp.toPx()
                }
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isRussian) "для Android TV" else "for Android TV",
                color = Secondary,
                fontSize = 16.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = ((p - 0.55f) / 0.35f).coerceIn(0f, 1f)
                }
            )
        }
    }
}
