/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.account.AccountState
import app.mawja.android.account.MawjaTier
import app.mawja.android.account.RedeemError
import app.mawja.android.account.localCodeError
import app.mawja.android.ui.components.settings.SettingsPage
import app.mawja.codes.MawjaCodes
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Mawja membership. Memberships are activated with a code (MW-XXXX-XXXX-XXXX) bought from Mawja's
 * official channels; there is no in-app checkout. The code is checked on the device first (format
 * and check symbol), then redeemed on the account. Nothing from the original app is paid.
 */
@Composable
internal fun UpgradeScreen(
    onBack: (() -> Unit)?,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val redeem by viewModel.redeem.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val focus = LocalFocusManager.current
    val signedIn = account as? AccountState.SignedIn
    val current = signedIn?.profile?.tier ?: MawjaTier.FREE
    val until = signedIn?.profile?.tierUntil
    var code by rememberSaveable { mutableStateOf("") }
    val canRedeem = viewModel.available && signedIn != null

    fun submit() {
        focus.clearFocus()
        viewModel.redeemCode(code)
    }

    SettingsPage(
        title = stringResource(R.string.upgrade_title),
        subtitle = stringResource(R.string.upgrade_subtitle),
        onBack = onBack,
    ) {
        item("upgrade.current") {
            val plan = stringResource(R.string.upgrade_current, stringResource(tierRes(current)))
            Text(
                if (until != null && current != MawjaTier.FREE) plan + " · " + stringResource(R.string.upgrade_until, formatDate(until, locale)) else plan,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item("upgrade.code") {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.ConfirmationNumber, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.redeem_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        stringResource(
                            when {
                                !viewModel.available -> R.string.redeem_requires_account
                                signedIn == null -> R.string.redeem_sign_in_first
                                else -> R.string.redeem_help
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val local = codeHint(code)
                    val error = redeem.error ?: local.takeIf { it != null && it != RedeemError.FORMAT }
                    OutlinedTextField(
                        value = code,
                        onValueChange = {
                            code = it.take(MAX_INPUT)
                            viewModel.clearRedeemError()
                        },
                        enabled = canRedeem && !redeem.busy,
                        singleLine = true,
                        label = { Text(stringResource(R.string.redeem_label)) },
                        placeholder = { Text("MW-XXXX-XXXX-XXXX") },
                        isError = error != null,
                        supportingText = {
                            when {
                                error != null -> Text(stringResource(redeemErrorRes(error)))
                                code.isNotBlank() && local == null -> Text(stringResource(R.string.redeem_looks_right), color = MaterialTheme.colorScheme.primary)
                                else -> Text(stringResource(R.string.redeem_format_hint))
                            }
                        },
                        // Codes are Latin letters and digits: keep them left-to-right in Arabic too.
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions = KeyboardActions(onDone = { if (canRedeem && code.isNotBlank()) submit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = ::submit,
                        enabled = canRedeem && !redeem.busy && code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (redeem.busy) {
                            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            Text(stringResource(R.string.redeem_button))
                        }
                    }
                    redeem.activated?.let { done ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            val tierName = stringResource(tierRes(done.tier))
                            Text(
                                if (done.until == null) {
                                    stringResource(R.string.redeem_success_lifetime, tierName)
                                } else {
                                    stringResource(R.string.redeem_success, tierName, formatDate(done.until, locale))
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        listOf(MawjaTier.PLUS, MawjaTier.PRO, MawjaTier.FAMILY, MawjaTier.LIFETIME).forEach { tier ->
            item("upgrade.${tier.name}") { TierCard(tier = tier, current = current == tier) }
        }
        item("upgrade.note") {
            Text(
                stringResource(R.string.redeem_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private const val MAX_INPUT = 40

/** Local feedback while typing: nothing until 12 symbols, except characters codes never use. */
private fun codeHint(input: String): RedeemError? {
    if (input.isBlank()) return RedeemError.FORMAT
    val cleaned = MawjaCodes.clean(input)
    if (cleaned.any { it !in MawjaCodes.ALPHABET }) return RedeemError.BAD_CHARACTER
    if (cleaned.length < MawjaCodes.LENGTH) return RedeemError.FORMAT
    return localCodeError(input)
}

private fun formatDate(
    millis: Long,
    locale: Locale,
): String = DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date(millis))

@Composable
private fun TierCard(
    tier: MawjaTier,
    current: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.WorkspacePremium, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(tierRes(tier)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (current) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.upgrade_current_badge), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(stringResource(tierPitchRes(tier)), style = MaterialTheme.typography.bodyMedium)
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

internal fun redeemErrorRes(error: RedeemError): Int =
    when (error) {
        RedeemError.FORMAT -> R.string.redeem_error_format
        RedeemError.BAD_CHARACTER -> R.string.redeem_error_bad_character
        RedeemError.TYPO -> R.string.redeem_error_typo
        RedeemError.INVALID -> R.string.redeem_error_invalid
        RedeemError.USED -> R.string.redeem_error_used
        RedeemError.ALREADY_YOURS -> R.string.redeem_error_already_yours
        RedeemError.EXPIRED -> R.string.redeem_error_expired
        RedeemError.REVOKED -> R.string.redeem_error_revoked
        RedeemError.NOT_SIGNED_IN -> R.string.redeem_sign_in_first
        RedeemError.NETWORK -> R.string.redeem_error_network
        RedeemError.TOO_MANY_ATTEMPTS -> R.string.redeem_error_too_many
        RedeemError.ALREADY_LIFETIME -> R.string.redeem_error_lifetime
        RedeemError.ALREADY_COVERED -> R.string.redeem_error_covered
        RedeemError.ACCOUNT_RESTRICTED -> R.string.redeem_error_restricted
        RedeemError.NOT_AVAILABLE -> R.string.redeem_requires_account
        RedeemError.UNKNOWN -> R.string.redeem_error_unknown
    }
