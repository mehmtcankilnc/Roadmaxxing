package app.roadmaxxing.ui.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.legal_back
import app.roadmaxxing.resources.legal_draft_banner
import app.roadmaxxing.resources.legal_last_updated
import app.roadmaxxing.resources.legal_load_error
import app.roadmaxxing.resources.legal_privacy_title
import app.roadmaxxing.resources.legal_terms_title
import app.roadmaxxing.resources.legal_updated_date
import app.roadmaxxing.share.deviceLanguageCode
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.ScreenClass
import app.roadmaxxing.ui.currentScreenClass
import org.jetbrains.compose.resources.stringResource

// DRAFT: the legal texts shown here must be reviewed by the owner or a lawyer before launch.

/** Which in-app legal text to show. */
enum class LegalDocument { Privacy, Terms }

/** Maximum readable column width on medium and expanded windows. */
private val ReadableWidth = 680.dp

/**
 * In-app legal text (privacy policy or terms of use): draft banner, last-updated line and the
 * Markdown body from `files/legal/`. Turkish device language shows `_tr`, everything else `_en`.
 */
@Composable
fun LegalScreen(document: LegalDocument, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val lang = if (deviceLanguageCode().lowercase().startsWith("tr")) "tr" else "en"
    val baseName = if (document == LegalDocument.Privacy) "privacy" else "terms"
    // null = loading, empty list = failed to load.
    val blocks by produceState<List<LegalBlock>?>(initialValue = null, document, lang) {
        value = try {
            parseLegalMarkdown(Res.readBytes("files/legal/${baseName}_$lang.md").decodeToString())
        } catch (e: Exception) {
            emptyList()
        }
    }
    val screenClass = currentScreenClass()
    val horizontalPadding = if (screenClass == ScreenClass.Compact) 20.dp else 32.dp
    val maxWidth = if (screenClass == ScreenClass.Compact) Dp.Infinity else ReadableWidth

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TextButton(onClick = onBack) { Text("< " + stringResource(Res.string.legal_back)) }
            Text(
                text = stringResource(
                    if (document == LegalDocument.Privacy) Res.string.legal_privacy_title else Res.string.legal_terms_title,
                ),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            DraftBanner()
            Text(
                text = stringResource(Res.string.legal_last_updated, stringResource(Res.string.legal_updated_date)),
                style = MaterialTheme.typography.labelLarge,
                color = RoadmaxxingTheme.extraColors.textSecondary,
            )
            val loaded = blocks
            when {
                loaded == null -> CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
                loaded.isEmpty() -> Text(
                    text = stringResource(Res.string.legal_load_error),
                    style = MaterialTheme.typography.bodyLarge,
                    color = RoadmaxxingTheme.extraColors.textSecondary,
                )
                else -> LegalBody(loaded)
            }
        }
    }
}

@Composable
private fun DraftBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = RoadmaxxingTheme.extraColors.surfaceRaised,
    ) {
        Text(
            text = stringResource(Res.string.legal_draft_banner),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
