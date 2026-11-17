package app.roadmaxxing.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.decimal_separator
import app.roadmaxxing.resources.summary_avg_speed
import app.roadmaxxing.resources.summary_back
import app.roadmaxxing.resources.summary_city_title
import app.roadmaxxing.resources.summary_distance
import app.roadmaxxing.resources.summary_duration
import app.roadmaxxing.resources.summary_format_post
import app.roadmaxxing.resources.summary_format_story
import app.roadmaxxing.resources.summary_hide_speed
import app.roadmaxxing.resources.summary_km
import app.roadmaxxing.resources.summary_kmh
import app.roadmaxxing.resources.summary_loading
import app.roadmaxxing.resources.summary_max_speed
import app.roadmaxxing.resources.summary_min
import app.roadmaxxing.resources.summary_new_roads
import app.roadmaxxing.resources.summary_not_drive
import app.roadmaxxing.resources.summary_pending
import app.roadmaxxing.resources.summary_region
import app.roadmaxxing.resources.summary_render_error
import app.roadmaxxing.resources.summary_safe_note
import app.roadmaxxing.resources.summary_save
import app.roadmaxxing.resources.summary_save_done
import app.roadmaxxing.resources.summary_save_error
import app.roadmaxxing.resources.summary_save_permission
import app.roadmaxxing.resources.summary_share
import app.roadmaxxing.resources.summary_title
import app.roadmaxxing.resources.summary_unavailable
import app.roadmaxxing.resources.summary_working
import app.roadmaxxing.share.CardSharer
import app.roadmaxxing.share.SaveFailure
import app.roadmaxxing.share.rememberCardSharer
import app.roadmaxxing.shared.domain.DriveRecord
import app.roadmaxxing.shared.domain.DriveStatus
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.summary.CityCardData
import app.roadmaxxing.shared.summary.DriveCardData
import app.roadmaxxing.ui.card.CardFormat
import app.roadmaxxing.ui.card.CityCard
import app.roadmaxxing.ui.card.DriveCard
import app.roadmaxxing.ui.card.rememberCardRenderer
import app.roadmaxxing.ui.components.PanelCorner
import app.roadmaxxing.ui.components.SecondaryText
import app.roadmaxxing.ui.components.TabTitle
import app.roadmaxxing.ui.main.DetailTarget
import app.roadmaxxing.ui.main.formatDateTime
import app.roadmaxxing.ui.main.formatDecimal
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Detail pane: summary of one drive or the city card, with format choice, share and save. */
@Composable
fun SummaryScreen(
    target: DetailTarget,
    drive: DriveRecord?,
    load: CardLoad,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("< " + stringResource(Res.string.summary_back)) }
        TabTitle(stringResource(if (target is DetailTarget.City) Res.string.summary_city_title else Res.string.summary_title))
        if (drive != null) {
            SecondaryText(formatDateTime(drive.startMs))
            val summary = drive.summary
            when {
                drive.status != DriveStatus.Processed || summary == null -> SecondaryText(stringResource(Res.string.summary_pending))
                summary.verdict != DriveVerdict.Drive -> SecondaryText(stringResource(Res.string.summary_not_drive))
                else -> CardBody(load, isDrive = true)
            }
        } else {
            CardBody(load, isDrive = false)
        }
    }
}

@Composable
private fun CardBody(load: CardLoad, isDrive: Boolean) {
    when (load) {
        CardLoad.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
            SecondaryText(stringResource(Res.string.summary_loading))
        }
        CardLoad.Unavailable -> SecondaryText(stringResource(Res.string.summary_unavailable))
        is CardLoad.Drive -> if (isDrive) CardActions(load.data, null)
        is CardLoad.City -> if (!isDrive) CardActions(null, load.data)
    }
    SecondaryText(stringResource(Res.string.summary_safe_note))
}

@Composable
private fun CardActions(drive: DriveCardData?, city: CityCardData?) {
    val sep = stringResource(Res.string.decimal_separator)
    val renderer = rememberCardRenderer()
    val sharer: CardSharer = rememberCardSharer()
    val scope = rememberCoroutineScope()
    var story by rememberSaveable { mutableStateOf(true) }
    var hideSpeed by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<StringResource?>(null) }
    val format = if (story) CardFormat.Story else CardFormat.Post

    if (drive != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatLine(stringResource(Res.string.summary_distance), stringResource(Res.string.summary_km, formatDecimal(drive.distanceM / 1000.0, 2, sep)))
            StatLine(stringResource(Res.string.summary_duration), stringResource(Res.string.summary_min, formatDecimal(drive.durationMs / 60_000.0, 1, sep)))
            StatLine(stringResource(Res.string.summary_new_roads), stringResource(Res.string.summary_km, formatDecimal(drive.newLengthM / 1000.0, 2, sep)))
            drive.regionName?.let { StatLine(stringResource(Res.string.summary_region), it) }
            // Speed is shown but deliberately plain: no emphasis, no ranking.
            drive.avgSpeedMps?.let { StatLine(stringResource(Res.string.summary_avg_speed), stringResource(Res.string.summary_kmh, formatDecimal(it * 3.6, 0, sep))) }
            drive.maxSpeedMps?.let { StatLine(stringResource(Res.string.summary_max_speed), stringResource(Res.string.summary_kmh, formatDecimal(it * 3.6, 0, sep))) }
        }
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 320.dp).fillMaxWidth()) {
            if (drive != null) DriveCard(drive, format, hideSpeed = hideSpeed) else if (city != null) CityCard(city, format)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormatChip(story, stringResource(Res.string.summary_format_story)) { story = true; message = null }
        FormatChip(!story, stringResource(Res.string.summary_format_post)) { story = false; message = null }
    }
    if (drive != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = hideSpeed, onCheckedChange = { hideSpeed = it })
            Text(stringResource(Res.string.summary_hide_speed), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }

    fun act(save: Boolean) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            val png = runCatching {
                if (drive != null) renderer.renderDriveCard(drive, format, hideSpeed = hideSpeed) else renderer.renderCityCard(city!!, format)
            }.getOrNull()
            if (png == null) {
                message = Res.string.summary_render_error
            } else {
                val base = if (drive != null) "roadmaxxing-drive-${drive.driveId}" else "roadmaxxing-city-${city!!.regionId}"
                val name = base + (if (story) "-story" else "-post") + ".png"
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

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { act(save = false) },
            enabled = !busy,
            shape = RoundedCornerShape(PanelCorner),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(if (busy) Res.string.summary_working else Res.string.summary_share)) }
        OutlinedButton(
            onClick = { act(save = true) },
            enabled = !busy,
            shape = RoundedCornerShape(PanelCorner),
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(if (busy) Res.string.summary_working else Res.string.summary_save)) }
    }
    message?.let { m ->
        val isError = m != Res.string.summary_save_done
        Text(
            text = stringResource(m),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        SecondaryText(label, Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun FormatChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = RoundedCornerShape(percent = 50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}
