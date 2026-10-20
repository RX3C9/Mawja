// SPDX-License-Identifier: GPL-3.0-or-later
package app.mawja.downloader.api;

/**
 * Byte progress for one fetchRange call. The downloader also watches this binder: when the
 * client process dies, the transfer it asked for is cancelled.
 */
oneway interface IRangeProgress {
    void onBytes(String token, long delta);
}
