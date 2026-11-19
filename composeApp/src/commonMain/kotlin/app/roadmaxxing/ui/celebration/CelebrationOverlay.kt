package app.roadmaxxing.ui.celebration

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.roadmaxxing.platform.rememberReduceMotion
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.card_percent_leading
import app.roadmaxxing.resources.card_percent_symbol
import app.roadmaxxing.resources.celebration_close
import app.roadmaxxing.resources.celebration_district_done
import app.roadmaxxing.resources.celebration_districts_many
import app.roadmaxxing.resources.celebration_drive_safe
import app.roadmaxxing.resources.celebration_label
import app.roadmaxxing.resources.celebration_province_title
import app.roadmaxxing.resources.summary_render_error
import app.roadmaxxing.resources.summary_save
import app.roadmaxxing.resources.summary_save_done
import app.roadmaxxing.resources.summary_save_error
import app.roadmaxxing.resources.summary_save_permission
import app.roadmaxxing.resources.summary_share
import app.roadmaxxing.resources.summary_working
import app.roadmaxxing.share.CardSharer
import app.roadmaxxing.share.SaveFailure
import app.roadmaxxing.share.rememberCardSharer
import app.roadmaxxing.shared.milestones.Celebration
import app.roadmaxxing.theme.RoadmaxxingPalette
import app.roadmaxxing.ui.card.CardFormat
import app.roadmaxxing.ui.card.MilestoneCard
import app.roadmaxxing.ui.card.MilestoneCardData
import app.roadmaxxing.ui.card.rememberCardRenderer
import app.roadmaxxing.ui.components.PanelCorner
import app.roadmaxxing.ui.shell.PlatformBackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The conditions must hold this long before the celebration appears, so it never flashes in between states. */
private const val SETTLE_MS = 600L

/**
 * Hosts the milestone celebration above the shell. [canShow] is true only while the map itself is what the user
 * sees and no burn animation is playing. One celebration is shown per span of [canShow]: after it is closed the
 * next one (if any) waits until the conditions have been lost and regained.
 */
@Composable
fun CelebrationHost(canShow: Boolean, viewModel: CelebrationViewModel = koinViewModel()) {
    val card by viewModel.card.collectAsStateWithLifecycle()
    var settled by remember { mutableStateOf(false) }
    var closedThisSpan by remember { mutableStateOf(false) }
    LaunchedEffect(canShow) {
        if (canShow) {
            delay(SETTLE_MS)
            settled = true
        } else {
            settled = false
            closedThisSpan = false
        }
    }
    val data = card
    if (canShow && settled && !closedThisSpan && data != null) {
        CelebrationOverlay(data = data, onClose = {
            closedThisSpan = true
            viewModel.dismiss()
        })
    }
}

@Composable
private fun CelebrationOverlay(data: MilestoneCardData, onClose: () -> Unit) {
    PlatformBackHandler(enabled = true, onBack = onClose)
    val reduceMotion = rememberReduceMotion()
    val progress = remember(data.celebration, reduceMotion) { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(progress) {
        if (progress.value < 1f) progress.animateTo(1f, tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }
    val p = progress.value

    val actions = rememberCardActions(data)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f * (p * 3f).coerceAtMost(1f)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= WideMinWidth
            val availableHeight = maxHeight
            Column(
                Modifier
                    .widthIn(max = if (wide) 760.dp else 440.dp)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = p; scaleX = 0.96f + 0.04f * p; scaleY = 0.96f + 0.04f * p }
                    // Taps on the panel must not reach the scrim behind it.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(PanelCorner))
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            CardPreview(data, availableHeight - 72.dp)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Headline(data.celebration, p, ringSize = 150.dp)
                            Buttons(actions, onClose)
                        }
                    }
                } else {
                    Headline(data.celebration, p, ringSize = 132.dp)
                    CardPreview(data, availableHeight * 0.36f)
                    Buttons(actions, onClose)
                }
            }
        }
    }
}

private val WideMinWidth = 600.dp

@Composable
private fun CardPreview(data: MilestoneCardData, maxHeight: Dp) {
    Box(Modifier.fillMaxWidth().heightIn(max = maxHeight), contentAlignment = Alignment.Center) {
        MilestoneCard(data, CardFormat.Story)
    }
}

/** Ring + percent for a province threshold, then the title and the completed districts. */
@Composable
private fun Headline(celebration: Celebration, p: Float, ringSize: Dp) {
    val province = celebration.province
    val districts = celebration.districts
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(Res.string.celebration_label).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = RoadmaxxingPalette.TextSecondary,
        )
        if (province != null) {
            val symbol = stringResource(Res.string.card_percent_symbol)
            val leading = stringResource(Res.string.card_percent_leading) == "1"
            val pct = if (leading) "$symbol${province.percent}" else "${province.percent}$symbol"
            Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(ringSize)) {
                    val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                    val inset = stroke.width / 2f
                    val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
                    drawArc(RoadmaxxingPalette.RoadUnexplored, 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
                    drawArc(
                        RoadmaxxingPalette.Primary, -90f, 360f * (province.percent / 100f).coerceIn(0f, 1f) * p,
                        false, Offset(inset, inset), arcSize, style = stroke,
                    )
                }
                Text(
                    pct,
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold),
                    color = RoadmaxxingPalette.Primary,
                    modifier = Modifier.graphicsLayer { alpha = p; scaleX = 0.85f + 0.15f * p; scaleY = 0.85f + 0.15f * p },
                )
            }
            Text(
                stringResource(Res.string.celebration_province_title, pct, province.name),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        } else if (districts.size == 1) {
            Text(
                stringResource(Res.string.celebration_district_done, districts.first().name),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = RoadmaxxingPalette.Primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = p },
            )
        } else if (districts.size > 1) {
            Text(
                stringResource(Res.string.celebration_districts_many, districts.size),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = RoadmaxxingPalette.Primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = p },
            )
        }
        // Districts beside a province threshold, or the list under a multi-district title.
        val listed = if (province != null) districts else if (districts.size > 1) districts else emptyList()
        listed.forEach { d ->
            Text(
                if (province != null || districts.size > 1) "✓  " + stringResource(Res.string.celebration_district_done, d.name) else d.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Buttons(actions: CardActions, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = { actions.run(save = false) },
            enabled = !actions.busy,
            shape = RoundedCornerShape(PanelCorner),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (actions.busy) Res.string.summary_working else Res.string.summary_share)) }
        OutlinedButton(
            onClick = { actions.run(save = true) },
            enabled = !actions.busy,
            shape = RoundedCornerShape(PanelCorner),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (actions.busy) Res.string.summary_working else Res.string.summary_save)) }
        actions.message?.let { m ->
            Text(
                stringResource(m),
                style = MaterialTheme.typography.bodyMedium,
                color = if (m == Res.string.summary_save_done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        TextButton(onClick = onClose) { Text(stringResource(Res.string.celebration_close)) }
        Text(
            stringResource(Res.string.celebration_drive_safe),
            style = MaterialTheme.typography.bodySmall,
            color = RoadmaxxingPalette.TextSecondary,
        )
    }
}

/** Share / save of the milestone card (Story format). Rendering is off screen; the sharer needs UI context. */
private class CardActions(
    private val data: MilestoneCardData,
    private val renderer: app.roadmaxxing.ui.card.CardRenderer,
    private val sharer: CardSharer,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<StringResource?>(null)
        private set

    fun run(save: Boolean) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            val png = runCatching { renderer.renderMilestoneCard(data, CardFormat.Story) }.getOrNull()
            if (png == null) {
                message = Res.string.summary_render_error
            } else {
                val name = fileName(data.celebration)
                if (save) {
                    val result = sharer.saveToGallery(png, name)
                    message = when {
                        result.isSuccess -> Res.string.summary_save_done
                        result.exceptionOrNull() is SaveFailure.PermissionDenied -> Res.string.summary_save_permission
                        else -> Res.string.summary_save_error
                    }
                } else {
                    runCatching { sharer.share(png, name) }.onFailure { message = Res.string.summary_render_error }
                }
            }
            busy = false
        }
    }
}

@Composable
private fun rememberCardActions(data: MilestoneCardData): CardActions {
    val renderer = rememberCardRenderer()
    val sharer = rememberCardSharer()
    val scope = rememberCoroutineScope()
    return remember(data, renderer, sharer, scope) { CardActions(data, renderer, sharer, scope) }
}

private fun fileName(c: Celebration): String {
    val key = c.province?.key ?: c.districts.firstOrNull()?.key ?: "milestone"
    return "roadmaxxing-milestone-" + key.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("") + "-story.png"
}
