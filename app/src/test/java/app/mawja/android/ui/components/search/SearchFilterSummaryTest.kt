package app.mawja.android.ui.components.search

import com.google.common.truth.Truth.assertThat
import app.mawja.android.R
import app.mawja.android.data.local.ContentType
import app.mawja.android.data.local.Duration
import app.mawja.android.data.local.SearchFilter
import app.mawja.android.data.local.UploadDate
import org.junit.Test

class SearchFilterSummaryTest {
    @Test
    fun `an unfiltered search names nothing`() {
        assertThat(SearchFilter.DEFAULT.summaryLabelRes()).isEmpty()
    }

    @Test
    fun `a narrowed search names each choice in dialog order`() {
        val filter = SearchFilter(contentType = ContentType.VIDEOS, duration = Duration.OVER_20_MINUTES, uploadDate = UploadDate.TODAY)

        assertThat(filter.summaryLabelRes())
            .containsExactly(ContentType.VIDEOS.labelRes(), Duration.OVER_20_MINUTES.labelRes(), UploadDate.TODAY.labelRes())
            .inOrder()
    }

    @Test
    fun `channel searches leave out the duration and date they ignore`() {
        val filter = SearchFilter(contentType = ContentType.CHANNELS, duration = Duration.OVER_20_MINUTES)

        assertThat(filter.summaryLabelRes()).containsExactly(R.string.channels_header)
    }
}
