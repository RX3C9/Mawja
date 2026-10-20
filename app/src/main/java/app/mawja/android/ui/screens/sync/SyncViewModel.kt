package app.mawja.android.ui.screens.sync

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import app.mawja.android.sync.SyncManager
import app.mawja.android.sync.SyncState
import app.mawja.android.sync.protocol.SyncRole
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Thin ViewModel over the singleton [SyncManager] (which survives config changes). */
@HiltViewModel
class SyncViewModel
    @Inject
    constructor(
        private val manager: SyncManager,
    ) : ViewModel() {
        val state: StateFlow<SyncState> = manager.state

        fun host(
            role: SyncRole,
            collections: List<String>,
        ) = manager.host(role, collections)

        fun join(
            role: SyncRole,
            qrText: String,
            collections: List<String>,
        ) = manager.join(role, qrText, collections)

        fun extendPairingForManualShare(): String? = manager.extendPairingForManualShare()

        fun confirmSas(matches: Boolean) = manager.confirmSas(matches)

        fun confirmConsent(accepted: Boolean) = manager.confirmConsent(accepted)

        fun cancel() = manager.cancel()

        fun reset() = manager.reset()
    }
