/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.smartnotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hears a swiped-away adaptive notification, so the engine sends fewer of that kind. */
class SmartNotifyFeedbackReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != SmartNotifier.ACTION_DISMISSED) return
        val hash = intent.getStringExtra(SmartNotifier.EXTRA_SMART_HASH) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                SmartNotifyStore(context).recordOutcome(hash, SmartOutcome.DISMISSED)
            } finally {
                pending.finish()
            }
        }
    }
}
