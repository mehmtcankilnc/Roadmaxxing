package app.roadmaxxing.ui.card.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.card_debug_close
import app.roadmaxxing.resources.card_debug_hide_speed
import app.roadmaxxing.resources.card_debug_title
import app.roadmaxxing.resources.card_debug_write
import app.roadmaxxing.resources.card_debug_written
import app.roadmaxxing.theme.RoadmaxxingPalette
import app.roadmaxxing.ui.card.CardFormat
import app.roadmaxxing.ui.card.CityCard
import app.roadmaxxing.ui.card.DriveCard
import app.roadmaxxing.ui.card.MilestoneCard
import app.roadmaxxing.ui.card.rememberCardPngWriter
import app.roadmaxxing.ui.card.rememberCardRenderer
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/** Debug-only screen: four sample cards in both formats; can render them all to PNG files in the app cache. */
@Composable
fun CardPreviewDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(RoadmaxxingPalette.Background)) {
            CardPreviewContent(onClose)
        }
    }
}

@Composable
private fun CardPreviewContent(onClose: () -> Unit) {
    val renderer = rememberCardRenderer()
    val writer = rememberCardPngWriter()
    val scope = rememberCoroutineScope()
    var hideSpeed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        org.jetbrains.compose.resources.stringResource(Res.string.card_debug_title).let {
            androidx.compose.material3.Text(it, color = RoadmaxxingPalette.TextPrimary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = hideSpeed, onCheckedChange = { hideSpeed = it })
            Text(org.jetbrains.compose.resources.stringResource(Res.string.card_debug_hide_speed), color = RoadmaxxingPalette.TextPrimary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                scope.launch {
                    val paths = mutableListOf<String>()
                    for (format in CardFormat.entries) {
                        val tag = format.name.lowercase()
                        writer("drive_normal_$tag.png", renderer.renderDriveCard(sampleDriveNormal, format, hideSpeed))?.let(paths::add)
                        writer("drive_edge_$tag.png", renderer.renderDriveCard(sampleDriveEdge, format, hideSpeed))?.let(paths::add)
                        writer("city_normal_$tag.png", renderer.renderCityCard(sampleCityNormal, format))?.let(paths::add)
                        writer("city_edge_$tag.png", renderer.renderCityCard(sampleCityEdge, format))?.let(paths::add)
                        writer("milestone_province_$tag.png", renderer.renderMilestoneCard(sampleMilestoneProvince, format))?.let(paths::add)
                        writer("milestone_district_$tag.png", renderer.renderMilestoneCard(sampleMilestoneDistrict, format))?.let(paths::add)
                        writer("milestone_both_$tag.png", renderer.renderMilestoneCard(sampleMilestoneBoth, format))?.let(paths::add)
                    }
                    status = getString(Res.string.card_debug_written, paths.size.toString(), paths.firstOrNull()?.substringBeforeLast('/') ?: "-")
                }
            }) { Text(org.jetbrains.compose.resources.stringResource(Res.string.card_debug_write)) }
            OutlinedButton(onClick = onClose) { Text(org.jetbrains.compose.resources.stringResource(Res.string.card_debug_close)) }
        }
        if (status.isNotEmpty()) Text(status, color = RoadmaxxingPalette.Primary)
        for (format in CardFormat.entries) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DriveCard(sampleDriveNormal, format, hideSpeed, Modifier.weight(1f))
                DriveCard(sampleDriveEdge, format, hideSpeed, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CityCard(sampleCityNormal, format, Modifier.weight(1f))
                CityCard(sampleCityEdge, format, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MilestoneCard(sampleMilestoneProvince, format, Modifier.weight(1f))
                MilestoneCard(sampleMilestoneDistrict, format, Modifier.weight(1f))
                MilestoneCard(sampleMilestoneBoth, format, Modifier.weight(1f))
            }
        }
    }
}
