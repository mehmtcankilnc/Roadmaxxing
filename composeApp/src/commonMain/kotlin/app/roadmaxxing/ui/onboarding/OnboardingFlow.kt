package app.roadmaxxing.ui.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.roadmaxxing.platform.AppPermission
import app.roadmaxxing.platform.PermissionState
import app.roadmaxxing.platform.TrackingPlatform
import app.roadmaxxing.platform.rememberPermissionRequester
import app.roadmaxxing.platform.rememberPermissionStates
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.onboarding_allow
import app.roadmaxxing.resources.onboarding_consent_body
import app.roadmaxxing.resources.onboarding_consent_check_safe
import app.roadmaxxing.resources.onboarding_consent_check_terms
import app.roadmaxxing.resources.onboarding_consent_point_phone
import app.roadmaxxing.resources.onboarding_consent_point_rules
import app.roadmaxxing.resources.onboarding_consent_title
import app.roadmaxxing.resources.onboarding_continue
import app.roadmaxxing.resources.onboarding_done_body
import app.roadmaxxing.resources.onboarding_done_button
import app.roadmaxxing.resources.onboarding_done_skipped
import app.roadmaxxing.resources.onboarding_done_title
import app.roadmaxxing.resources.onboarding_get_started
import app.roadmaxxing.resources.onboarding_intro1_body
import app.roadmaxxing.resources.onboarding_intro1_title
import app.roadmaxxing.resources.onboarding_intro2_body
import app.roadmaxxing.resources.onboarding_intro2_title
import app.roadmaxxing.resources.onboarding_not_now
import app.roadmaxxing.resources.onboarding_open_settings
import app.roadmaxxing.resources.onboarding_perm_activity_body
import app.roadmaxxing.resources.onboarding_perm_activity_title
import app.roadmaxxing.resources.onboarding_perm_approximate_body
import app.roadmaxxing.resources.onboarding_perm_approximate_title
import app.roadmaxxing.resources.onboarding_perm_background_body
import app.roadmaxxing.resources.onboarding_perm_background_hint
import app.roadmaxxing.resources.onboarding_perm_background_title
import app.roadmaxxing.resources.onboarding_perm_background_use
import app.roadmaxxing.resources.onboarding_perm_blocked_body
import app.roadmaxxing.resources.onboarding_perm_location_body
import app.roadmaxxing.resources.onboarding_perm_location_title
import app.roadmaxxing.resources.onboarding_perm_notifications_body
import app.roadmaxxing.resources.onboarding_perm_notifications_title
import app.roadmaxxing.resources.onboarding_privacy_link
import app.roadmaxxing.resources.onboarding_perm_counter
import app.roadmaxxing.resources.onboarding_perm_location_p1
import app.roadmaxxing.resources.onboarding_perm_location_p2
import app.roadmaxxing.resources.onboarding_perm_location_p3
import app.roadmaxxing.resources.onboarding_perm_background_p1
import app.roadmaxxing.resources.onboarding_perm_background_p2
import app.roadmaxxing.resources.onboarding_perm_activity_p1
import app.roadmaxxing.resources.onboarding_perm_activity_p2
import app.roadmaxxing.resources.onboarding_perm_notifications_p1
import app.roadmaxxing.resources.onboarding_perm_notifications_p2
import app.roadmaxxing.resources.onboarding_step_description
import app.roadmaxxing.resources.onboarding_terms_link
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.ScreenClass
import app.roadmaxxing.ui.currentScreenClass
import app.roadmaxxing.ui.legal.LegalDocument
import app.roadmaxxing.ui.legal.LegalScreen
import app.roadmaxxing.ui.shell.PlatformBackHandler
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Order in which permission cards are shown. */
private val PermissionOrder = listOf(
    AppPermission.Location,
    AppPermission.BackgroundLocation,
    AppPermission.ActivityRecognition,
    AppPermission.Notifications,
)

private val MediumColumnWidth = 560.dp
private val ExpandedContentWidth = 480.dp

/** Below this window height the layout drops the picture and scrolls everything, actions included. */
private val TightHeight = 560.dp

/** Total dots in the progress indicator: two intro screens, consent, permissions, done. */
private const val STEP_COUNT = 5

/** Onboarding: value intro, safe-driving consent, permission cards, done. Navigation lives in the ViewModel state. */
@Composable
fun OnboardingFlow(viewModel: OnboardingViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val screenClass = currentScreenClass()
    val progress = rememberRoadsProgress(state.introRoads)
    val onIntent = viewModel::onIntent

    PlatformBackHandler(enabled = viewModel.handlesBack(state)) { onIntent(OnboardingIntent.Back) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val illustration: @Composable (Modifier) -> Unit = { m ->
            RoadsIllustration(roads = state.introRoads, progress = { progress.value }, modifier = m)
        }
        Crossfade(targetState = state.step, modifier = Modifier.fillMaxSize()) { step ->
            when (step) {
                OnboardingStep.Intro1 -> StepLayout(
                    screenClass, step.ordinal, showIllustration = true, illustration = illustration,
                    actions = { PrimaryButton(stringResource(Res.string.onboarding_continue)) { onIntent(OnboardingIntent.Next) } },
                ) {
                    StepTitle(stringResource(Res.string.onboarding_intro1_title))
                    StepBody(stringResource(Res.string.onboarding_intro1_body))
                }
                OnboardingStep.Intro2 -> StepLayout(
                    screenClass, step.ordinal, showIllustration = true, illustration = illustration,
                    actions = { PrimaryButton(stringResource(Res.string.onboarding_get_started)) { onIntent(OnboardingIntent.Next) } },
                ) {
                    StepTitle(stringResource(Res.string.onboarding_intro2_title))
                    StepBody(stringResource(Res.string.onboarding_intro2_body))
                }
                OnboardingStep.Consent -> StepLayout(
                    screenClass, step.ordinal, showIllustration = false, illustration = illustration,
                    actions = {
                        PrimaryButton(stringResource(Res.string.onboarding_continue), enabled = state.canContinueFromConsent) {
                            onIntent(OnboardingIntent.Next)
                        }
                    },
                ) { ConsentContent(state, onIntent) }
                OnboardingStep.Permissions -> PermissionsStep(screenClass, illustration, state, onIntent)
                OnboardingStep.Done -> StepLayout(
                    screenClass, step.ordinal, showIllustration = false, illustration = illustration,
                    actions = { PrimaryButton(stringResource(Res.string.onboarding_done_button)) { onIntent(OnboardingIntent.Finish) } },
                ) {
                    StepTitle(stringResource(Res.string.onboarding_done_title))
                    StepBody(stringResource(Res.string.onboarding_done_body))
                    if (state.skippedPermissions.isNotEmpty()) {
                        StepBody(stringResource(Res.string.onboarding_done_skipped))
                    }
                }
            }
        }
        state.legal?.let { doc ->
            // Opaque and touch-blocking so the screen underneath never shows or reacts through the legal text.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
            ) {
                LegalScreen(document = doc, onBack = { onIntent(OnboardingIntent.CloseLegal) }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------------------------------------------------

/**
 * One onboarding screen. Compact: single column. Medium: the same column centred with a capped width. Expanded: two
 * panes, the road illustration on the left and the content on the right. [actions] stay pinned at the bottom.
 */
@Composable
private fun StepLayout(
    screenClass: ScreenClass,
    stepIndex: Int,
    showIllustration: Boolean,
    illustration: @Composable (Modifier) -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // Short windows (phone in landscape, folded outer display) have no room for the picture, and the actions
        // scroll together with the content so nothing is squeezed into a sliver.
        val tight = this.maxHeight < TightHeight
        if (screenClass == ScreenClass.Expanded) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp)) { illustration(Modifier.fillMaxSize()) }
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    ContentColumn(stepIndex, null, tight, actions, content, Modifier.widthIn(max = ExpandedContentWidth))
                }
            }
        } else {
            val maxWidth = if (screenClass == ScreenClass.Medium) MediumColumnWidth else Dp.Infinity
            ContentColumn(
                stepIndex = stepIndex,
                illustration = if (showIllustration && !tight) illustration else null,
                tight = tight,
                actions = actions,
                content = content,
                modifier = Modifier.widthIn(max = maxWidth),
            )
        }
    }
}

@Composable
private fun ContentColumn(
    stepIndex: Int,
    illustration: (@Composable (Modifier) -> Unit)?,
    tight: Boolean,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tight) {
        Column(
            modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            StepDots(stepIndex)
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = actions)
        }
        return
    }
    Column(modifier = modifier.fillMaxHeight().padding(horizontal = 24.dp, vertical = 16.dp)) {
        StepDots(stepIndex)
        Spacer(Modifier.height(16.dp))
        if (illustration != null) {
            illustration(Modifier.weight(1f).fillMaxWidth().heightIn(min = 160.dp))
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        } else {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = actions)
    }
}

@Composable
private fun StepDots(index: Int) {
    val description = stringResource(Res.string.onboarding_step_description, index + 1, STEP_COUNT)
    Row(
        modifier = Modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(STEP_COUNT) { i ->
            Box(
                modifier = Modifier
                    .size(width = if (i == index) 24.dp else 6.dp, height = 6.dp)
                    .background(
                        if (i <= index) MaterialTheme.colorScheme.primary else RoadmaxxingTheme.extraColors.roadUnexplored,
                        CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun StepBody(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = RoadmaxxingTheme.extraColors.textSecondary)
}

/** Primary action: Volt green with dark text. */
@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = RoadmaxxingTheme.extraColors.textSecondary)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Consent
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.ConsentContent(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
    StepTitle(stringResource(Res.string.onboarding_consent_title))
    StepBody(stringResource(Res.string.onboarding_consent_body))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = RoadmaxxingTheme.extraColors.surfaceRaised,
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Bullet(stringResource(Res.string.onboarding_consent_point_phone))
            Bullet(stringResource(Res.string.onboarding_consent_point_rules))
        }
    }
    CheckRow(checked = state.safeAccepted, onChange = { onIntent(OnboardingIntent.SetSafeAccepted(it)) }) {
        Text(
            stringResource(Res.string.onboarding_consent_check_safe),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    val terms = stringResource(Res.string.onboarding_terms_link)
    val privacy = stringResource(Res.string.onboarding_privacy_link)
    val template = stringResource(Res.string.onboarding_consent_check_terms, terms, privacy)
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val annotated = buildAnnotatedString {
        appendWithLinks(
            template,
            listOf(
                terms to { onIntent(OnboardingIntent.OpenLegal(LegalDocument.Terms)) },
                privacy to { onIntent(OnboardingIntent.OpenLegal(LegalDocument.Privacy)) },
            ),
            linkStyle,
        )
    }
    CheckRow(checked = state.termsAccepted, onChange = { onIntent(OnboardingIntent.SetTermsAccepted(it)) }) {
        Text(annotated, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Appends [text], turning the first occurrence of each link label into a tappable link. */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendWithLinks(
    text: String,
    links: List<Pair<String, () -> Unit>>,
    style: TextLinkStyles,
) {
    val found = links.mapNotNull { (label, action) ->
        val at = text.indexOf(label)
        if (at < 0) null else Triple(at, label, action)
    }.sortedBy { it.first }
    var cursor = 0
    for ((at, label, action) in found) {
        if (at < cursor) continue
        append(text.substring(cursor, at))
        withLink(LinkAnnotation.Clickable(tag = label, styles = style, linkInteractionListener = { action() })) { append(label) }
        cursor = at + label.length
    }
    append(text.substring(cursor))
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.padding(top = 8.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Box(modifier = Modifier.weight(1f).clickable { onChange(!checked) }.padding(vertical = 6.dp)) { label() }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Permissions
// ---------------------------------------------------------------------------------------------------------------------

/** The next card to show, or null when none is left. Granted, not-needed and postponed permissions are skipped. */
private fun nextPermission(states: Map<AppPermission, PermissionState>, skipped: List<AppPermission>): AppPermission? {
    val foregroundKnown = states[AppPermission.Location].let {
        it == PermissionState.Granted || it == PermissionState.Approximate || it == PermissionState.NotNeeded
    }
    return PermissionOrder.firstOrNull { p ->
        val s = states[p] ?: return@firstOrNull false
        when {
            p in skipped -> false
            s == PermissionState.Granted || s == PermissionState.NotNeeded -> false
            // Background access can only be requested after foreground location exists.
            p == AppPermission.BackgroundLocation && !foregroundKnown -> false
            else -> true
        }
    }
}

@Composable
private fun PermissionsStep(
    screenClass: ScreenClass,
    illustration: @Composable (Modifier) -> Unit,
    state: OnboardingUiState,
    onIntent: (OnboardingIntent) -> Unit,
) {
    val platform = koinInject<TrackingPlatform>()
    val states by rememberPermissionStates()
    val asked = remember { arrayOfNulls<AppPermission>(1) }
    val currentOnIntent = rememberUpdatedState(onIntent)
    val requestPermission = rememberPermissionRequester {
        // The system dialog closed. Granted advances by itself (the card disappears). For an optional permission, or
        // when the user only gave approximate location, move on whatever happened; for a denied or blocked required
        // one stay here so the card can show the new state (Open settings) and the user picks Not now.
        val permission = asked[0] ?: return@rememberPermissionRequester
        val result = platform.permissionStates()[permission]
        val moveOn = when (permission) {
            AppPermission.Notifications -> result != PermissionState.Granted
            else -> result == PermissionState.Approximate
        }
        if (moveOn) currentOnIntent.value(OnboardingIntent.SkipPermission(permission))
    }
    val current = if (states.isEmpty()) null else nextPermission(states, state.skippedPermissions)

    // Nothing left to ask (all granted, not needed or postponed): finish the step.
    LaunchedEffect(current, states.isEmpty()) {
        if (current == null && states.isNotEmpty()) onIntent(OnboardingIntent.PermissionsExhausted)
    }
    if (current == null) return

    Crossfade(targetState = current, modifier = Modifier.fillMaxSize()) { permission ->
        val permissionState = states[permission] ?: PermissionState.Denied
        val blocked = permissionState == PermissionState.Blocked
        val text = permissionTexts(permission, permissionState)
        StepLayout(
            screenClass, OnboardingStep.Permissions.ordinal, showIllustration = false, illustration = illustration,
            actions = {
                PrimaryButton(
                    stringResource(if (blocked) Res.string.onboarding_open_settings else Res.string.onboarding_allow),
                ) {
                    if (blocked) {
                        platform.openAppSettings()
                    } else {
                        asked[0] = permission
                        requestPermission(permission)
                    }
                }
                SecondaryButton(stringResource(Res.string.onboarding_not_now)) { onIntent(OnboardingIntent.SkipPermission(permission)) }
            },
        ) {
            IconTile(permission)
            Text(
                stringResource(Res.string.onboarding_perm_counter, PermissionOrder.indexOf(permission) + 1, PermissionOrder.size),
                style = MaterialTheme.typography.labelLarge,
                color = RoadmaxxingTheme.extraColors.textSecondary,
            )
            StepTitle(text.title)
            text.body.forEach { StepBody(it) }
            if (blocked) StepBody(stringResource(Res.string.onboarding_perm_blocked_body))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = RoadmaxxingTheme.extraColors.surfaceRaised,
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    text.points.forEach { Bullet(it) }
                }
            }
        }
    }
}

/** Large rounded tile with the permission glyph in Volt green. */
@Composable
private fun IconTile(permission: AppPermission) {
    Box(
        modifier = Modifier.size(72.dp).background(RoadmaxxingTheme.extraColors.surfaceRaised, RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = permission.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
    }
}

private class PermissionTexts(val title: String, val body: List<String>, val points: List<String>)

@Composable
private fun permissionTexts(permission: AppPermission, state: PermissionState): PermissionTexts = when (permission) {
    AppPermission.Location ->
        if (state == PermissionState.Approximate) {
            PermissionTexts(
                stringResource(Res.string.onboarding_perm_approximate_title),
                listOf(stringResource(Res.string.onboarding_perm_approximate_body)),
                locationPoints(),
            )
        } else {
            PermissionTexts(
                stringResource(Res.string.onboarding_perm_location_title),
                listOf(stringResource(Res.string.onboarding_perm_location_body)),
                locationPoints(),
            )
        }
    // Prominent in-app disclosure (Google Play policy) shown before the system request.
    AppPermission.BackgroundLocation -> PermissionTexts(
        stringResource(Res.string.onboarding_perm_background_title),
        buildList {
            add(stringResource(Res.string.onboarding_perm_background_body))
            add(stringResource(Res.string.onboarding_perm_background_use))
            if (state != PermissionState.Blocked) add(stringResource(Res.string.onboarding_perm_background_hint))
        },
        listOf(
            stringResource(Res.string.onboarding_perm_background_p1),
            stringResource(Res.string.onboarding_perm_background_p2),
        ),
    )
    AppPermission.ActivityRecognition -> PermissionTexts(
        stringResource(Res.string.onboarding_perm_activity_title),
        listOf(stringResource(Res.string.onboarding_perm_activity_body)),
        listOf(
            stringResource(Res.string.onboarding_perm_activity_p1),
            stringResource(Res.string.onboarding_perm_activity_p2),
        ),
    )
    AppPermission.Notifications -> PermissionTexts(
        stringResource(Res.string.onboarding_perm_notifications_title),
        listOf(stringResource(Res.string.onboarding_perm_notifications_body)),
        listOf(
            stringResource(Res.string.onboarding_perm_notifications_p1),
            stringResource(Res.string.onboarding_perm_notifications_p2),
        ),
    )
}

@Composable
private fun locationPoints(): List<String> = listOf(
    stringResource(Res.string.onboarding_perm_location_p1),
    stringResource(Res.string.onboarding_perm_location_p2),
    stringResource(Res.string.onboarding_perm_location_p3),
)
