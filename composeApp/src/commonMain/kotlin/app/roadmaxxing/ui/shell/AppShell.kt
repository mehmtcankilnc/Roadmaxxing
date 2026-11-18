package app.roadmaxxing.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.roadmaxxing.notify.PendingDriveNavigation
import app.roadmaxxing.platform.AppPermission
import app.roadmaxxing.platform.rememberPermissionRequester
import app.roadmaxxing.ui.ScreenClass
import app.roadmaxxing.ui.components.FloatingPanel
import app.roadmaxxing.ui.currentScreenClass
import app.roadmaxxing.ui.drives.DrivesTab
import app.roadmaxxing.ui.explore.ExploreOverlay
import app.roadmaxxing.ui.explore.ExploreUiState
import app.roadmaxxing.ui.explore.ExploreViewModel
import app.roadmaxxing.ui.main.AppTab
import app.roadmaxxing.ui.main.DetailTarget
import app.roadmaxxing.ui.summary.CardLoad
import app.roadmaxxing.ui.summary.SummaryScreen
import app.roadmaxxing.ui.summary.SummaryViewModel
import app.roadmaxxing.ui.main.MainIntent
import app.roadmaxxing.ui.main.MainUiState
import app.roadmaxxing.ui.main.MainViewModel
import app.roadmaxxing.ui.map.ExplorationMap
import app.roadmaxxing.ui.profile.ProfileTab
import app.roadmaxxing.ui.stats.StatsTab
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * App shell: the map is always drawn full screen behind; each tab puts its content in floating panels.
 * Compact widths get a floating bottom pill bar, wider ones a side rail with the panel next to the map.
 */
@Composable
fun AppShell(
    viewModel: MainViewModel = koinViewModel(),
    exploreViewModel: ExploreViewModel = koinViewModel(),
    summaryViewModel: SummaryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val explore by exploreViewModel.state.collectAsStateWithLifecycle()
    val requestPermission = rememberPermissionRequester { viewModel.onIntent(MainIntent.PermissionsChanged) }
    // Permissions can change in system settings while the app is in the background.
    LifecycleResumeEffect(Unit) {
        viewModel.onIntent(MainIntent.PermissionsChanged)
        onPauseOrDispose { }
    }
    // Back from any other tab returns to the map; on the map the system handles back as usual.
    // An open detail screen closes first.
    PlatformBackHandler(enabled = state.detail != null || state.selectedTab != AppTab.Map) {
        if (state.detail != null) viewModel.onIntent(MainIntent.CloseDetail) else viewModel.onIntent(MainIntent.SelectTab(AppTab.Map))
    }
    // A tapped end-of-drive notification asks for that drive's summary: same path as tapping it in the Drives list.
    val pendingNavigation = koinInject<PendingDriveNavigation>()
    val pendingDriveId by pendingNavigation.driveId.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDriveId) {
        val id = pendingDriveId ?: return@LaunchedEffect
        viewModel.onIntent(MainIntent.OpenDetail(DetailTarget.Drive(id)))
        pendingNavigation.consume(id)
    }
    val detail = state.detail
    // The new-road burn plays only while the map itself is what the user sees.
    val mapVisible = state.selectedTab == AppTab.Map && detail == null
    LaunchedEffect(mapVisible) { exploreViewModel.onMapVisible(mapVisible) }
    LaunchedEffect(detail) { if (detail != null) summaryViewModel.show(detail) else summaryViewModel.clear() }
    val cardLoad by summaryViewModel.state.collectAsStateWithLifecycle()
    ShellContent(
        state = state,
        explore = explore,
        screenClass = currentScreenClass(),
        onIntent = viewModel::onIntent,
        onRequestPermission = requestPermission,
        onMapTouched = exploreViewModel::onUserTouchedMap,
        detailPane = { modifier ->
            if (detail != null) {
                SummaryScreen(
                    target = detail,
                    drive = (detail as? DetailTarget.Drive)?.let { t -> state.drives.firstOrNull { it.id == t.driveId } },
                    load = cardLoad,
                    onBack = { viewModel.onIntent(MainIntent.CloseDetail) },
                    modifier = modifier,
                )
            }
        },
    )
}

@Composable
internal fun ShellContent(
    state: MainUiState,
    explore: ExploreUiState,
    screenClass: ScreenClass,
    onIntent: (MainIntent) -> Unit,
    onRequestPermission: (AppPermission) -> Unit,
    onMapTouched: () -> Unit,
    detailPane: @Composable (Modifier) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (screenClass) {
            ScreenClass.Compact -> CompactShell(state, explore, onIntent, onRequestPermission, onMapTouched, detailPane)
            ScreenClass.Medium -> WideShell(state, explore, onIntent, onRequestPermission, panelWidth = MediumPanelWidth, onMapTouched = onMapTouched, detailPane = detailPane)
            ScreenClass.Expanded -> WideShell(state, explore, onIntent, onRequestPermission, panelWidth = ExpandedPanelWidth, onMapTouched = onMapTouched, detailPane = detailPane)
        }
    }
}

@Composable
private fun CompactShell(
    state: MainUiState,
    explore: ExploreUiState,
    onIntent: (MainIntent) -> Unit,
    onRequestPermission: (AppPermission) -> Unit,
    onMapTouched: () -> Unit,
    detailPane: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    // Heights of the overlays, so the map keeps its fit and attribution clear of them. The top value is only
    // updated on the map tab, so switching tabs does not move the camera.
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    val onMapTab = state.selectedTab == AppTab.Map
    val bottomDp = with(density) { bottomPx.toDp() }
    val topDp = with(density) { topPx.toDp() }

    Box(Modifier.fillMaxSize()) {
        ExplorationMap(
            drivenRoadsGeoJson = explore.drivenRoadsGeoJson,
            focus = explore.focus,
            burn = explore.burn,
            modifier = Modifier.fillMaxSize().reportMapTouch(onMapTouched),
            contentPadding = PaddingValues(top = topDp, bottom = bottomDp),
        )
        if (onMapTab && state.detail == null) {
            ExploreOverlay(
                state = state,
                explore = explore,
                onOpenPermissions = { onIntent(MainIntent.SelectTab(AppTab.Profile)) },
                onOpenCity = { onIntent(MainIntent.OpenDetail(DetailTarget.City)) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { topPx = it.height + with(density) { PanelGap.roundToPx() } }
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = PanelGap, vertical = PanelGap),
            )
        } else {
            FloatingPanel(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = insets.calculateTopPadding() + PanelGap,
                        bottom = bottomDp + AttributionClearance,
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = PanelGap),
            ) {
                if (state.detail != null) detailPane(Modifier.fillMaxSize()) else TabScroll(state, onIntent, onRequestPermission)
            }
        }
        BottomTabBar(
            selected = state.selectedTab,
            onSelect = { onIntent(MainIntent.SelectTab(it)) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { bottomPx = it.height + with(density) { (PanelGap * 2).roundToPx() } }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = PanelGap, top = PanelGap),
        )
    }
}

@Composable
private fun WideShell(
    state: MainUiState,
    explore: ExploreUiState,
    onIntent: (MainIntent) -> Unit,
    onRequestPermission: (AppPermission) -> Unit,
    panelWidth: Dp,
    onMapTouched: () -> Unit,
    detailPane: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val startInset = insets.calculateStartPadding(layoutDirection)
    // Only what really sits on top of the map counts: the rail (start side) and, on the map tab, the city card
    // (top). The wide content panel is NOT included, otherwise the fit is squeezed into the free strip beside it.
    // Like in the compact layout, the top value is only updated on the map tab so tab switches keep the camera.
    var overlayPx by remember { mutableIntStateOf(0) }
    val coveredStart = startInset + PanelGap + RailWidth + PanelGap
    val coveredTop = insets.calculateTopPadding() + PanelGap + with(density) { overlayPx.toDp() } + PanelGap

    Box(Modifier.fillMaxSize()) {
        ExplorationMap(
            drivenRoadsGeoJson = explore.drivenRoadsGeoJson,
            focus = explore.focus,
            burn = explore.burn,
            modifier = Modifier.fillMaxSize().reportMapTouch(onMapTouched),
            contentPadding = if (layoutDirection == LayoutDirection.Ltr) {
                PaddingValues(start = coveredStart, top = coveredTop, bottom = insets.calculateBottomPadding())
            } else {
                PaddingValues(end = coveredStart, top = coveredTop, bottom = insets.calculateBottomPadding())
            },
        )
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(PanelGap)) {
        // When list and detail do not fit side by side (e.g. an unfolded foldable), the detail takes the list's place.
        val detailOnly = state.detail != null && maxWidth < DetailSideBySideMinWidth
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(PanelGap),
        ) {
            SideRail(
                selected = state.selectedTab,
                onSelect = { onIntent(MainIntent.SelectTab(it)) },
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            if (!detailOnly) Box(Modifier.width(panelWidth).fillMaxHeight()) {
                if (state.selectedTab == AppTab.Map) {
                    ExploreOverlay(
                        state = state,
                        explore = explore,
                        onOpenPermissions = { onIntent(MainIntent.SelectTab(AppTab.Profile)) },
                        onOpenCity = { onIntent(MainIntent.OpenDetail(DetailTarget.City)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { overlayPx = it.height }
                            .verticalScroll(rememberScrollState()),
                    )
                } else {
                    FloatingPanel(Modifier.fillMaxSize().padding(bottom = AttributionClearance - PanelGap)) {
                        TabScroll(state, onIntent, onRequestPermission)
                    }
                }
            }
            if (state.detail != null) {
                // Detail pane beside the list / city card.
                FloatingPanel(
                    (if (detailOnly) Modifier.weight(1f) else Modifier.width(panelWidth + 60.dp))
                        .fillMaxHeight()
                        .padding(bottom = AttributionClearance - PanelGap),
                ) {
                    detailPane(Modifier.fillMaxSize())
                }
            }
        }
        }
    }
}

/** Scrollable tab content inside a panel; scroll position resets when the tab changes. */
@Composable
private fun TabScroll(state: MainUiState, onIntent: (MainIntent) -> Unit, onRequestPermission: (AppPermission) -> Unit) {
    key(state.selectedTab) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            when (state.selectedTab) {
                AppTab.Map -> Unit
                AppTab.Drives -> DrivesTab(state) { onIntent(MainIntent.OpenDetail(DetailTarget.Drive(it))) }
                AppTab.Stats -> StatsTab(state)
                AppTab.Profile -> ProfileTab(state, onIntent, onRequestPermission)
            }
        }
    }
}

private val PanelGap = 12.dp

/** Below this width the detail pane replaces the list instead of sitting beside it. */
private val DetailSideBySideMinWidth = 900.dp

/** Free strip under a full-height panel so the map attribution stays readable. */
private val AttributionClearance = 32.dp
private val RailWidth = 88.dp
private val MediumPanelWidth = 380.dp
private val ExpandedPanelWidth = 440.dp

/**
 * Reports the first finger on the map without consuming it. The map has no camera callback, so a touch is the
 * shell's signal that the user may have moved the camera and new drives must not re-fit it.
 */
private fun Modifier.reportMapTouch(onTouch: () -> Unit): Modifier = pointerInput(onTouch) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.pressed }) onTouch()
        }
    }
}
