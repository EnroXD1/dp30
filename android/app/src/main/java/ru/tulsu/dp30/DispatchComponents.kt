package ru.tulsu.dp30

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun Glyph(@DrawableRes icon: Int, modifier: Modifier = Modifier, description: String? = null) {
    Icon(painterResource(icon), description, modifier.size(22.dp))
}

@Composable
internal fun BrandMark(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier.size(48.dp).clip(shape).background(
            Brush.linearGradient(listOf(colors.secondaryContainer, colors.primary, colors.tertiary))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = CircleShape, color = colors.surface, contentColor = colors.primary) {
            Glyph(R.drawable.ic_board, modifier = Modifier.padding(6.dp))
        }
    }
}

@Composable
internal fun AmbientBackground(content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(colors.background).drawBehind {
            drawRect(Brush.radialGradient(
                colors = listOf(colors.primary.copy(alpha = .15f), Color.Transparent),
                center = Offset(size.width * .09f, size.height * .08f), radius = size.width * .95f,
            ))
            drawRect(Brush.radialGradient(
                colors = listOf(colors.secondary.copy(alpha = .12f), Color.Transparent),
                center = Offset(size.width * .97f, size.height * .48f), radius = size.width * .76f,
            ))
        },
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground) { content() }
    }
}

@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    bordered: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = color, contentColor = contentColor, shape = MaterialTheme.shapes.large,
        border = if (bordered) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

internal enum class Tone { NEUTRAL, PRIMARY, SECONDARY, TERTIARY, ERROR }

@Composable
private fun toneColors(tone: Tone): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (tone) {
        Tone.NEUTRAL -> colors.surfaceContainerHigh to colors.onSurfaceVariant
        Tone.PRIMARY -> colors.primaryContainer to colors.onPrimaryContainer
        Tone.SECONDARY -> colors.secondaryContainer to colors.onSecondaryContainer
        Tone.TERTIARY -> colors.tertiaryContainer to colors.onTertiaryContainer
        Tone.ERROR -> colors.errorContainer to colors.onErrorContainer
    }
}

@Composable
internal fun StatusPill(text: String, tone: Tone = Tone.NEUTRAL, @DrawableRes icon: Int? = null) {
    val (background, foreground) = toneColors(tone)
    Surface(shape = CircleShape, color = background, contentColor = foreground) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(15.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun requestTone(status: String) = when (status) {
    "NEW", "ASSIGNED" -> Tone.PRIMARY
    "IN_WORK", "ACCEPTED" -> Tone.TERTIARY
    "COMPLETED" -> Tone.SECONDARY
    "CANCELLED" -> Tone.ERROR
    else -> Tone.NEUTRAL
}

@Composable
internal fun Notice(text: String, tone: Tone = Tone.ERROR, @DrawableRes icon: Int = R.drawable.ic_alert) {
    val (background, foreground) = toneColors(tone)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = background, contentColor = foreground, shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(13.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Glyph(icon)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun HeroMotif(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.drawBehind {
        val dy = size.height * .78f
        val cx = size.width * .88f
        val r = size.width * .36f
        drawCircle(
            brush = Brush.radialGradient(
                listOf(colors.primary.copy(alpha = .30f), Color.Transparent),
                center = Offset(cx, dy), radius = r * 1.5f,
            ),
            radius = r * 1.5f, center = Offset(cx, dy),
        )
        repeat(3) { i ->
            drawCircle(
                colors.primary.copy(alpha = .19f - i * .036f),
                radius = r * (.58f + i * .26f), center = Offset(cx, dy),
                style = Stroke(1.3.dp.toPx()),
            )
        }
        drawCircle(colors.primary.copy(alpha = .6f), 4.dp.toPx(), Offset(cx - r * .47f, dy - r * .34f))
        drawCircle(colors.tertiary.copy(alpha = .5f), 3.dp.toPx(), Offset(cx - r * .72f, dy + r * .73f))
    })
}

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun QuietAction(
    text: String,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    click: () -> Unit,
) {
    OutlinedButton(
        onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (icon != null) {
            Glyph(icon)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, textAlign = TextAlign.Center)
    }
}
