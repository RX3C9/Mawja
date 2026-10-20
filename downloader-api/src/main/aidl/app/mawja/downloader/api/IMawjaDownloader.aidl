// SPDX-License-Identifier: GPL-3.0-or-later
package app.mawja.downloader.api;

import android.os.Bundle;
import app.mawja.downloader.api.IDownloadCallback;

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
}
