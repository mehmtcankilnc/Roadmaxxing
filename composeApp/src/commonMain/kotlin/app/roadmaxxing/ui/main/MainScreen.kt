package app.roadmaxxing.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.app_name
import app.roadmaxxing.resources.main_phase_label
import app.roadmaxxing.resources.main_safe_drive
import app.roadmaxxing.resources.main_status_first_launch
import app.roadmaxxing.resources.main_status_loading
import app.roadmaxxing.resources.main_status_ready
import app.roadmaxxing.resources.main_status_schema_mismatch
import app.roadmaxxing.resources.main_status_title
import app.roadmaxxing.resources.main_tagline
import app.roadmaxxing.shared.domain.SetupStatus
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.ScreenClass
import app.roadmaxxing.ui.currentScreenClass
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun MainScreen(viewModel: MainViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MainContent(state = state, screenClass = currentScreenClass())
}

@Composable
internal fun MainContent(state: MainUiState, screenClass: ScreenClass) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        when (screenClass) {
            ScreenClass.Compact -> SinglePane(state, horizontalPadding = 24.dp)
            ScreenClass.Medium -> SinglePane(state, horizontalPadding = 48.dp)
            ScreenClass.Expanded -> TwoPane(state)
        }
    }
}

/** Compact and medium widths: one column, width capped so lines stay readable. */
@Composable
private fun SinglePane(state: MainUiState, horizontalPadding: Dp) {
    Column(
        modifier = Modifier
            .widthIn(max = ReadableContentWidth)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Header()
        StatusCard(state)
        SafeDriveNote()
    }
}

/** Expanded width: header on the left, status on the right. */
@Composable
private fun TwoPane(state: MainUiState) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Header()
            SafeDriveNote()
        }
        Box(modifier = Modifier.weight(1f)) {
            StatusCard(state)
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.main_phase_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(Res.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(Res.string.main_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = RoadmaxxingTheme.extraColors.textSecondary,
        )
    }
}

@Composable
private fun StatusCard(state: MainUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(Res.string.main_status_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(state.statusText()),
                style = MaterialTheme.typography.bodyLarge,
                color = RoadmaxxingTheme.extraColors.textSecondary,
            )
        }
    }
}

@Composable
private fun SafeDriveNote() {
    Text(
        text = stringResource(Res.string.main_safe_drive),
        style = MaterialTheme.typography.labelLarge,
        color = RoadmaxxingTheme.extraColors.textSecondary,
    )
}

private fun MainUiState.statusText(): StringResource = when (if (isLoading) null else setupStatus) {
    null -> Res.string.main_status_loading
    SetupStatus.Ready -> Res.string.main_status_ready
    SetupStatus.FirstLaunch -> Res.string.main_status_first_launch
    SetupStatus.SchemaMismatch -> Res.string.main_status_schema_mismatch
}

private val ReadableContentWidth = 640.dp
