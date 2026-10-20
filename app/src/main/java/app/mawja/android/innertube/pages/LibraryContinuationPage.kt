package app.mawja.android.innertube.pages

import app.mawja.android.innertube.models.YTItem

data class LibraryContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
)
