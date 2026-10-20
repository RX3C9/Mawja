package app.mawja.android.notification

import androidx.work.ExistingPeriodicWorkPolicy

internal fun periodicWorkPolicy(reschedule: Boolean): ExistingPeriodicWorkPolicy =
    if (reschedule) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
