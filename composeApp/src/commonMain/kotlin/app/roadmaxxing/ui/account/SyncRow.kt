package app.roadmaxxing.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.sync_error_network
import app.roadmaxxing.resources.sync_error_permission
import app.roadmaxxing.resources.sync_error_quota
import app.roadmaxxing.resources.sync_error_unknown
import app.roadmaxxing.resources.sync_never
import app.roadmaxxing.resources.sync_now
import app.roadmaxxing.resources.sync_pending
import app.roadmaxxing.resources.sync_pending_one
import app.roadmaxxing.resources.sync_synced_days
import app.roadmaxxing.resources.sync_synced_hours
import app.roadmaxxing.resources.sync_synced_just_now
import app.roadmaxxing.resources.sync_synced_minutes
import app.roadmaxxing.resources.sync_syncing
import app.roadmaxxing.resources.sync_syncing_one
import app.roadmaxxing.resources.sync_syncing_plain
import app.roadmaxxing.shared.sync.SyncErrorKind
import app.roadmaxxing.shared.sync.SyncStatus
import app.roadmaxxing.theme.RoadmaxxingTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/**
 * Sync status inside the signed-in account card: last sync time / pending count, a spinner while syncing, a
 * muted error line on failure, and a "Sync now" button. Hidden entirely while sync is disabled.
 */
@Composable
fun SyncRow(status: SyncStatus, onSyncNow: () -> Unit, modifier: Modifier = Modifier) {
    if (status is SyncStatus.Disabled) return
    // Re-evaluate the relative time while the card is on screen.
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    val syncing = status is SyncStatus.Syncing
    val pending = when (status) {
        is SyncStatus.Idle -> status.pending
        is SyncStatus.Failed -> status.pending
        is SyncStatus.Syncing -> status.pending
        SyncStatus.Disabled -> 0
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (status) {
                is SyncStatus.Idle -> {
                    SyncLine(lastSyncedText(status.lastSyncedAtMs, now), muted = false)
                    if (pending > 0) SyncLine(pendingText(pending), muted = true)
                }
                is SyncStatus.Syncing -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    SyncLine(
                        when {
                            pending <= 0 -> stringResource(Res.string.sync_syncing_plain)
                            pending == 1 -> stringResource(Res.string.sync_syncing_one)
                            else -> stringResource(Res.string.sync_syncing, pending)
                        },
                        muted = false,
                    )
                }
                is SyncStatus.Failed -> {
                    Text(
                        text = stringResource(errorRes(status.kind)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                    )
                    if (pending > 0) SyncLine(pendingText(pending), muted = true)
                }
                SyncStatus.Disabled -> Unit
            }
        }
        TextButton(onClick = onSyncNow, enabled = !syncing) { Text(stringResource(Res.string.sync_now)) }
    }
}

@Composable
private fun SyncLine(text: String, muted: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (muted) RoadmaxxingTheme.extraColors.textSecondary else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun pendingText(pending: Int): String =
    if (pending == 1) stringResource(Res.string.sync_pending_one) else stringResource(Res.string.sync_pending, pending)

@Composable
private fun lastSyncedText(lastSyncedAtMs: Long?, nowMs: Long): String {
    if (lastSyncedAtMs == null) return stringResource(Res.string.sync_never)
    val age = (nowMs - lastSyncedAtMs).coerceAtLeast(0L)
    return when {
        age < MINUTE_MS -> stringResource(Res.string.sync_synced_just_now)
        age < HOUR_MS -> stringResource(Res.string.sync_synced_minutes, (age / MINUTE_MS).toInt())
        age < DAY_MS -> stringResource(Res.string.sync_synced_hours, (age / HOUR_MS).toInt())
        else -> stringResource(Res.string.sync_synced_days, (age / DAY_MS).toInt())
    }
}

private fun errorRes(kind: SyncErrorKind) = when (kind) {
    SyncErrorKind.Network -> Res.string.sync_error_network
    SyncErrorKind.Permission -> Res.string.sync_error_permission
    SyncErrorKind.Quota -> Res.string.sync_error_quota
    SyncErrorKind.Unknown -> Res.string.sync_error_unknown
}
