package app.mawja.android.ui.screens.settings.taste

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import app.mawja.android.data.recommendation.music.FavouriteArtistCatalog
import app.mawja.android.data.recommendation.music.FavouriteArtistPicker
import app.mawja.android.data.recommendation.music.FavouriteArtistsStore
import javax.inject.Inject

@HiltViewModel
internal class FavouriteArtistsViewModel
    @Inject
    constructor(
        store: FavouriteArtistsStore,
        catalog: FavouriteArtistCatalog,
    ) : ViewModel() {
        val picker = FavouriteArtistPicker(viewModelScope, store, catalog)
    }
