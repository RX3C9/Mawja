package app.mawja.android.ui.screens.settings.history

import dagger.hilt.android.lifecycle.HiltViewModel
import app.mawja.android.data.local.PlayerPreferences
import app.mawja.android.data.local.SearchHistoryRepository
import app.mawja.android.data.stats.VideoStatsRecorder
import app.mawja.android.ui.screens.settings.SettingsViewModel
import javax.inject.Inject

@HiltViewModel
class HistorySettingsViewModel
    @Inject
    constructor(
        private val repository: SearchHistoryRepository,
        private val videoStats: VideoStatsRecorder,
        private val playerPreferences: PlayerPreferences,
    ) : SettingsViewModel() {
        val watchHistoryPaused = playerPreferences.watchHistoryPaused.asState(false)
        val historyEnabled = repository.isSearchHistoryEnabledFlow().asState(true)
        val suggestionsEnabled = repository.isSearchSuggestionsEnabledFlow().asState(true)
        val maxSize = repository.getMaxHistorySizeFlow().asState(DEFAULT_MAX_SIZE)
        val autoDelete = repository.isAutoDeleteHistoryEnabledFlow().asState(false)
        val retentionDays = repository.getHistoryRetentionDaysFlow().asState(DEFAULT_RETENTION_DAYS)

        fun setHistoryEnabled(value: Boolean) =
            write {
                repository.setSearchHistoryEnabled(value)
                if (!value) videoStats.onSearchHistoryCleared()
            }

        fun setWatchHistoryPaused(value: Boolean) = write { playerPreferences.setWatchHistoryPaused(value) }

        fun setSuggestionsEnabled(value: Boolean) = write { repository.setSearchSuggestionsEnabled(value) }

        fun setMaxSize(value: Int) = write { repository.setMaxHistorySize(value) }

        fun setAutoDelete(value: Boolean) = write { repository.setAutoDeleteHistory(value) }

        fun setRetentionDays(value: Int) = write { repository.setHistoryRetentionDays(value) }

        fun clear() =
            write {
                repository.clearSearchHistory()
                videoStats.onSearchHistoryCleared()
            }

        private companion object {
            const val DEFAULT_MAX_SIZE = 50
            const val DEFAULT_RETENTION_DAYS = 90
        }
    }
