// SPDX-License-Identifier: GPL-3.0-or-later
package app.mawja.downloader.api;

/** Progress events pushed from Mawja Downloader to a bound client. */
oneway interface IDownloadCallback {
    void onQueued(String id, String title);
    void onProgress(String id, long downloadedBytes, long totalBytes);
    void onCompleted(String id, String contentUri, String mimeType);
    void onFailed(String id, String reason);
    void onCancelled(String id);
}
