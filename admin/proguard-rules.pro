# Mawja Admin release rules.

## Readable stack traces from testers, same policy as the app (GPL-3.0, source is public).
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

## Firebase (Auth, Functions, Firestore). The SDKs ship consumer rules; this broad keep stays as
## a safety net for the reflective Firestore/Functions paths the admin app relies on.
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

## Credential Manager (Google sign-in): the Play services provider is loaded by reflection.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
-dontwarn androidx.credentials.playservices.**

## Optional ZXing/okhttp references.
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
