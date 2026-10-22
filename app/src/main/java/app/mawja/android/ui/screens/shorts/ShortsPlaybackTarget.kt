package app.mawja.android.ui.screens.shorts

import android.content.Context
import app.mawja.android.data.local.VideoQuality
import app.mawja.android.utils.NetworkState

/**
 * The height a Short is resolved at.
 *
 * This is not merely a display preference — it forms part of the key of the repository's
 * playback-stream cache, so the pager and the ViewModel's prefetch have to agree on it exactly or
 * the prefetch resolves one entry and the pager then misses it and resolves the stream a second
 * time. Both therefore derive it here rather than each computing it their own way.
 */
internal fun shortsTargetHeight(
    isWifi: Boolean,
    wifiQuality: VideoQuality,
    cellularQuality: VideoQuality,
    /** Mawja battery saver (#62) caps the reel at 480p; both callers must pass the same value. */
    batterySaver: Boolean = false,
): Int =
    app.mawja.android.battery.BatterySaverPolicy
        .capQuality(if (isWifi) wifiQuality else cellularQuality, batterySaver)
        .height

/**
 * Reads the active transport synchronously. Callers must not substitute a placeholder while a
 * network callback settles: a wrong answer here silently changes the cache key above.
 */
internal fun isOnWifi(context: Context): Boolean = NetworkState.isOnWifi(context)
