# Mawja Downloader release rules.

## Readable stack traces, same policy as the app.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

## AIDL contract shared with the app (IMawjaDownloader, IDownloadCallback, IRangeProgress, contract
## constants). Must stay identical on both sides of the binder.
-keep class app.mawja.downloader.api.** { *; }
-keep interface app.mawja.downloader.api.** { *; }

## OkHttp optional platform classes.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
