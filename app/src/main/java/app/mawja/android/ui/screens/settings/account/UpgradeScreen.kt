/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.BuildConfig
import app.mawja.android.R
import app.mawja.android.account.AccountState
import app.mawja.android.account.MawjaTier
import app.mawja.android.account.MembershipCatalog
import app.mawja.android.account.MembershipPlan
import app.mawja.android.account.PlanPeriod
import app.mawja.android.ui.components.settings.SettingsPage
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * Mawja membership plans. Choosing a plan opens the web checkout ([BuildConfig.CHECKOUT_URL]); while
 * no checkout is configured the page says it is coming soon. Nothing from the original app is paid.
 */
@Composable
internal fun UpgradeScreen(
    onBack: (() -> Unit)?,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val locale = LocalConfiguration.current.locales[0]
    val signedIn = account as? AccountState.SignedIn
    val current = signedIn?.profile?.tier ?: MawjaTier.FREE
    val comingSoon = stringResource(R.string.upgrade_coming_soon)
    val signInFirst = stringResource(R.string.upgrade_sign_in_first)

    fun choose(plan: MembershipPlan) {
        val url = MembershipCatalog.checkoutUrl(BuildConfig.CHECKOUT_URL, plan, signedIn?.profile?.mawjaId, locale.language)
        when {
            signedIn == null -> scope.launch { snackbar.showSnackbar(signInFirst) }
            url == null -> scope.launch { snackbar.showSnackbar(comingSoon) }
            else -> runCatching { uriHandler.openUri(url) }.onFailure { scope.launch { snackbar.showSnackbar(comingSoon) } }
        }
    }

    SettingsPage(
        title = stringResource(R.string.upgrade_title),
        subtitle = stringResource(R.string.upgrade_subtitle),
        onBack = onBack,
        snackbarHostState = snackbar,
    ) {
        item("upgrade.current") {
            Text(
                stringResource(R.string.upgrade_current, stringResource(tierRes(current))),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MembershipCatalog.tiers.forEach { tier ->
            item("upgrade.${tier.name}") {
                PlanCard(tier = tier, current = current == tier, format = { NumberFormat.getIntegerInstance(locale).format(it) }, onChoose = ::choose)
            }
        }
        item("upgrade.note") {
            Text(
                stringResource(R.string.upgrade_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun PlanCard(
    tier: MawjaTier,
    current: Boolean,
    format: (Int) -> String,
    onChoose: (MembershipPlan) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.WorkspacePremium, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(tierRes(tier)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (current) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.upgrade_current_badge), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(stringResource(tierPitchRes(tier)), style = MaterialTheme.typography.bodyMedium)
            MembershipCatalog.plansFor(tier).forEach { plan ->
                val price = stringResource(R.string.upgrade_price_egp, format(plan.egp))
                val label =
                    when (plan.period) {
                        PlanPeriod.MONTH -> stringResource(R.string.upgrade_monthly, price)
                        PlanPeriod.YEAR ->
                            MembershipCatalog.yearlySavingPercent(tier)?.let { stringResource(R.string.upgrade_yearly_save, price, it) }
                                ?: stringResource(R.string.upgrade_yearly, price)
                        PlanPeriod.ONCE -> stringResource(R.string.upgrade_once, price)
                    }
                if (plan.period == PlanPeriod.MONTH) {
                    FilledTonalButton(onClick = { onChoose(plan) }, enabled = !current, modifier = Modifier.fillMaxWidth()) { Text(label) }
                } else {
                    Button(onClick = { onChoose(plan) }, enabled = !current, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
            }
        }
    }
}

private fun tierPitchRes(tier: MawjaTier): Int =
    when (tier) {
        MawjaTier.PLUS -> R.string.upgrade_pitch_plus
        MawjaTier.PRO -> R.string.upgrade_pitch_pro
        MawjaTier.FAMILY -> R.string.upgrade_pitch_family
        MawjaTier.LIFETIME -> R.string.upgrade_pitch_lifetime
        MawjaTier.FREE -> R.string.account_tier_free
    }
