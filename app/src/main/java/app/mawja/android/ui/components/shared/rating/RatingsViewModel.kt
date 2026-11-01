package app.mawja.android.ui.components.shared.rating

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mawja.android.data.ratings.Rating
import app.mawja.android.data.ratings.RatingKind
import app.mawja.android.data.ratings.RatingSubject
import app.mawja.android.data.ratings.RatingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The viewer's personal ratings (#152), shared by every menu and pane that shows a [RatingBar]. One
 * stream of all ratings keyed by stored id, so a bar reads its value without a query of its own.
 */
@HiltViewModel
class RatingsViewModel
    @Inject
    constructor(
        private val repository: RatingsRepository,
    ) : ViewModel() {
        /** Stored id (`video:<id>` / `track:<id>`) → rating. */
        val ratings: StateFlow<Map<String, Rating>> =
            repository
                .observeAll()
                .map { list -> list.associateBy { it.id } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyMap())

        /** 0 clears. */
        fun setRating(
            kind: RatingKind,
            targetId: String,
            stars: Int,
            subject: RatingSubject? = null,
        ) {
            viewModelScope.launch { repository.set(kind, targetId, stars, subject) }
        }

        fun clear(
            kind: RatingKind,
            targetId: String,
        ) {
            viewModelScope.launch { repository.clear(kind, targetId) }
        }

        private companion object {
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }

/** The activity's [RatingsViewModel], so every sheet shares one subscription. */
@Composable
fun sharedRatingsViewModel(): RatingsViewModel {
    val activity = LocalContext.current as? ComponentActivity
    return if (activity != null) hiltViewModel(activity) else hiltViewModel()
}
