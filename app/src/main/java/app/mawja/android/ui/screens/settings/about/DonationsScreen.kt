package app.mawja.android.ui.screens.settings.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mawja.android.R
import app.mawja.android.ui.components.donation.MAWJA_ISSUES_URL
import app.mawja.android.ui.components.donation.MAWJA_REPO_URL
import app.mawja.android.ui.components.settings.SettingsPage

/** Support Mawja: star the repository or report a problem. No donations, no third-party wallets. */
@Composable
fun DonationsScreen(onNavigateBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    SettingsPage(
        title = stringResource(R.string.support_donations_title),
        onBack = onNavigateBack,
    ) {
        item("support.header") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Outlined.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = stringResource(R.string.support_flow_dev_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.support_flow_dev_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = { runCatching { uriHandler.openUri(MAWJA_REPO_URL) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.mawja_support_star)) }
                FilledTonalButton(
                    onClick = { runCatching { uriHandler.openUri(MAWJA_ISSUES_URL) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.mawja_support_issue)) }
            }
        }
    }
}
