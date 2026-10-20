// SPDX-License-Identifier: GPL-3.0-or-later
package app.mawja.downloader.api;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import app.mawja.downloader.api.IDownloadCallback;
import app.mawja.downloader.api.IRangeProgress;

/**
 * Binder contract exposed by the Mawja Downloader app. Only apps signed with the same key
 * can bind, because the service is guarded by a signature-level permission.
 */
interface IMawjaDownloader {
    /** Contract version implemented by the installed downloader. */
    int apiVersion();

    /**
     * Queues a download and returns its id.
     * request keys: see MawjaDownloaderContract.Request.
     */
    String enqueue(in Bundle request);

    void cancel(String id);

    /** Snapshot of every known download, one Bundle per item (keys: MawjaDownloaderContract.Item). */
    List<Bundle> list();

    void registerCallback(IDownloadCallback callback);

    void unregisterCallback(IDownloadCallback callback);

    // ---- API version 2: the downloader performs the app's byte transfers ----

    /**
     * Runs one HTTP request (keys: MawjaDownloaderContract.Range) and writes its body into [target]
     * starting at [offset], at most [maxBytes] bytes. Blocks until the body ends, fails or is
     * cancelled. [target] may be null when [maxBytes] is 0 (a header-only probe).
     * Returns a Bundle with MawjaDownloaderContract.RangeResult keys.
     */
    Bundle fetchRange(in Bundle request, in ParcelFileDescriptor target, long offset, long maxBytes,
            String token, IRangeProgress progress);

    /** Stops the fetchRange call started with [token]; what it wrote so far stays in the file. */
    void cancelRange(String token);
}
