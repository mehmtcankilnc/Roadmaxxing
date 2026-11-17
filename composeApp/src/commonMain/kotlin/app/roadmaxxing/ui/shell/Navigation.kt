package app.roadmaxxing.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.tab_drives
import app.roadmaxxing.resources.tab_map
import app.roadmaxxing.resources.tab_profile
import app.roadmaxxing.resources.tab_stats
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.main.AppTab
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun AppTab.label(): StringResource = when (this) {
    AppTab.Map -> Res.string.tab_map
    AppTab.Drives -> Res.string.tab_drives
    AppTab.Stats -> Res.string.tab_stats
    AppTab.Profile -> Res.string.tab_profile
}

/** Compact screens: floating pill at the bottom; only the active tab shows its label, inside a dark pill. */
@Composable
internal fun BottomTabBar(selected: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(percent = 50))
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surface)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppTab.entries.forEach { tab ->
            val active = tab == selected
            val label = stringResource(tab.label())
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (active) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) })
                    .defaultMinSize(minWidth = 52.dp, minHeight = 52.dp)
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The label doubles as the accessibility description for inactive tabs.
                Icon(
                    imageVector = tab.icon(),
                    contentDescription = label,
                    modifier = Modifier.size(24.dp),
                    tint = if (active) MaterialTheme.colorScheme.primary else RoadmaxxingTheme.extraColors.textSecondary,
                )
                if (active) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** Medium and expanded screens: floating vertical rail on the start side. */
@Composable
internal fun SideRail(selected: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(88.dp)
            .shadow(8.dp, RoundedCornerShape(PillCorner))
            .clip(RoundedCornerShape(PillCorner))
            .background(MaterialTheme.colorScheme.surface)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppTab.entries.forEach { tab ->
            val active = tab == selected
            val label = stringResource(tab.label())
            Column(
                modifier = Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(PillCorner - 6.dp))
                    .background(if (active) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) })
                    .padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val tint = if (active) MaterialTheme.colorScheme.primary else RoadmaxxingTheme.extraColors.textSecondary
                Icon(imageVector = tab.icon(), contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 11.sp,
                    color = tint,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val PillCorner = 32.dp
