package com.blacksingularities.open360.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Czarno-biały interfejs jak w OpenPDF: akcent = biel, czerwień dla nagrywania i usuwania. */
object Palette {
    val Ink = Color(0xFF000000)
    val Panel = Color(0xFF000000)
    val PanelHigh = Color(0xFF1C1C1C)
    val Line = Color(0x33FFFFFF)
    val Record = Color(0xFFFF3B47)
    val TextDim = Color(0xB3FFFFFF)
    val TextFaint = Color(0x66FFFFFF)
    val Coffee = Color(0xFFF2C14E)
    val OnCoffee = Color(0xFF241A00)
    val Scrim = Color(0x8C000000)
}

private val scheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Palette.Ink,
    secondary = Color(0xFFE0E0E0),
    onSecondary = Palette.Ink,
    background = Palette.Ink,
    onBackground = Color.White,
    surface = Palette.Panel,
    onSurface = Color.White,
    surfaceVariant = Palette.PanelHigh,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainerLow = Palette.Panel,
    surfaceContainer = Palette.Panel,
    surfaceContainerHigh = Palette.PanelHigh,
    surfaceContainerHighest = Palette.PanelHigh,
    outline = Palette.Line,
    error = Palette.Record,
)

val MonoDigits = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 13.sp)

@Composable
fun Open360Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography()) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White, content = content)
    }
}

/** Sprężyste wciśnięcie z haptyką – wspólne dla wszystkich przycisków. */
@Composable
fun Modifier.pressable(label: String?, enabled: Boolean = true, haptics: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "press")
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = if (enabled) 1f else 0.38f
        }
        .clickable(source, null, enabled) {
            if (haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
}

/** Okrągły przycisk z ikoną; aktywny – biały blok z czarną ikoną. */
@Composable
fun RoundButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    active: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
    background: Color = Palette.Scrim,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (active) Color.White else background, tween(160), label = "bg")
    Box(
        modifier
            .pressable(label, enabled, onClick = onClick)
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint ?: if (active) Palette.Ink else Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** Pigułka z tekstem (i ikoną) – wybór opcji, przełączniki trybów. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    background: Color = Palette.Scrim,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (active) Color.White else background, tween(160), label = "pill")
    val fg = if (active) Palette.Ink else Color.White
    Row(
        modifier
            .pressable(text, enabled, onClick = onClick)
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Duży biały przycisk (główne działanie). */
@Composable
fun PrimaryButton(text: String, icon: ImageVector?, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier
            .pressable(text, enabled, onClick = onClick)
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Palette.Ink, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Palette.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** Obrysowany przycisk (działanie drugorzędne). */
@Composable
fun OutlineButton(text: String, icon: ImageVector?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .pressable(text, onClick = onClick)
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(50))
            .border(1.dp, Palette.Line, RoundedCornerShape(50))
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Wiersze ustawień ────────────────────────────────────────────────────────

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = Palette.TextFaint,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
    )
}

@Composable
fun ToggleRow(title: String, detail: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (enabled) Color.White else Palette.TextFaint)
            if (detail != null) Text(detail, fontSize = 13.sp, color = Palette.TextDim)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Ink,
                checkedTrackColor = Color.White,
                uncheckedThumbColor = Palette.TextDim,
                uncheckedTrackColor = Palette.PanelHigh,
                uncheckedBorderColor = Palette.Line,
            ),
        )
    }
}

@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    detail: String? = null,
    steps: Int = 0,
    enabled: Boolean = true,
    onDone: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), color = if (enabled) Color.White else Palette.TextFaint)
            Text(format(value), style = MonoDigits, color = Palette.TextDim)
        }
        if (detail != null) Text(detail, fontSize = 13.sp, color = Palette.TextDim)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Palette.PanelHigh,
                activeTickColor = Palette.Ink,
                inactiveTickColor = Palette.TextFaint,
            ),
        )
    }
}

/** Wybór jednej z opcji: pigułki w kilku rzędach, opis wybranej pod spodem. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, detail: ((T) -> String)? = null, onPick: (T) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                Pill(label(o), active = o == selected, background = Palette.PanelHigh) { onPick(o) }
            }
        }
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(detail(selected), fontSize = 13.sp, color = Palette.TextDim)
        }
    }
}
