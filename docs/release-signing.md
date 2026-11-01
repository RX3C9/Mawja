# Release signing (Mawja v1.0)

Mawja ships three APKs that **must be signed with the same release key**:

| Module | Package | Why the key matters |
|---|---|---|
| `:app` (github + foss) | `app.mawja.android` | Requests `app.mawja.permission.DOWNLOAD`; Google sign-in checks the key's SHA-1 |
| `:downloader` | `app.mawja.downloader` | Defines `app.mawja.permission.DOWNLOAD` with `protectionLevel="signature"`; the app's `DownloaderGate` also runs `checkSignatures(app, downloader)` |
| `:admin` | `app.mawja.admin` | Google sign-in checks the key's SHA-1 |

If the app and the Downloader are signed with different keys, the app shows "install Mawja Downloader" forever and binding fails with a SecurityException (caught, but downloads stop working).

The committed `keystore/mawja-debug.p12` is **for debug builds only**. Never use it for a release.

## How the build reads the key

Each of `app/`, `downloader/` and `admin/build.gradle.kts` reads these values, in this order: Gradle property (`-PNAME=…`), `local.properties`, environment variable.

| Name | Meaning |
|---|---|
| `MAWJA_RELEASE_STORE_FILE` | Path to the keystore. Relative paths are resolved from the repo root; absolute paths are used as-is |
| `MAWJA_RELEASE_STORE_PASSWORD` | Keystore password |
| `MAWJA_RELEASE_KEY_ALIAS` | Key alias (for example `mawja`) |
| `MAWJA_RELEASE_KEY_PASSWORD` | Key password (same as the store password for a PKCS12 keystore) |
| `MAWJA_RELEASE_STORE_TYPE` | Optional: `pkcs12` or `jks`. Leave unset; the JDK detects it |

If any of the four required values is missing, or the file does not exist, the release build is **unsigned** and Gradle logs `Mawja: MAWJA_RELEASE_* not set or keystore missing; release APK will be UNSIGNED.` Debug builds are not affected.

`*.jks`, `*.keystore`, `key.properties` and `local.properties` are already in `.gitignore`. Never commit the keystore or the passwords.

## 1. Create the keystore (once, on your own computer)

You need a JDK (Android Studio ships one; `keytool` lives in its `jbr/bin` folder).

```bash
keytool -genkeypair -v \
  -keystore mawja-release.jks \
  -storetype PKCS12 \
  -alias mawja \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname "CN=Mawja, O=Mawja, C=EG"
```

- keytool asks for a password. Use a long random one and save it in a password manager. With PKCS12 the key password is the same as the store password.
- `-validity 10000` is about 27 years. Google Play requires a key valid until at least 2033.
- **Back up `mawja-release.jks` and the password in two safe places.** If you lose the key, you can never ship an update that installs over the existing Mawja; every user would have to uninstall first.

Print the fingerprints (you need them for Firebase):

```bash
keytool -list -v -keystore mawja-release.jks -alias mawja
```

Copy the `SHA1:` and `SHA256:` lines.

## 2. Register the key with Firebase (Google sign-in)

In the Firebase console > Project settings > Your apps:

1. Android app `app.mawja.android` > Add fingerprint > paste the release SHA-1, then the SHA-256.
2. Android app `app.mawja.admin` > same two fingerprints.

Without this, Google sign-in in release builds fails with "developer error" / `GetCredentialException`, even though it works in debug builds (which use the debug key's SHA-1). Email/password sign-in is not affected.

## 3. Local release build

Put the values in `local.properties` (never committed):

```properties
MAWJA_RELEASE_STORE_FILE=/absolute/path/to/mawja-release.jks
MAWJA_RELEASE_STORE_PASSWORD=your-password
MAWJA_RELEASE_KEY_ALIAS=mawja
MAWJA_RELEASE_KEY_PASSWORD=your-password
```

Then build:

```bash
./gradlew :app:assembleGithubRelease :app:assembleFossRelease :downloader:assembleRelease :admin:assembleRelease
```

## 4. GitHub secrets (CI release builds)

Repository > Settings > Secrets and variables > Actions > New repository secret. Add:

| Secret | Value |
|---|---|
| `MAWJA_RELEASE_KEYSTORE_BASE64` | Output of `base64 -w0 mawja-release.jks` (macOS: `base64 -i mawja-release.jks`; Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("mawja-release.jks"))`) |
| `MAWJA_RELEASE_STORE_PASSWORD` | The password |
| `MAWJA_RELEASE_KEY_ALIAS` | `mawja` |
| `MAWJA_RELEASE_KEY_PASSWORD` | The password |

Files under `.github/workflows/` are not touched by the Mawja sync, so the release job has to be added by hand. A minimal job (add it to a new `.github/workflows/mawja-release.yml`, run it from the Actions tab):

```yaml
name: Mawja release
on: workflow_dispatch
jobs:
  release:
    runs-on: ubuntu-latest
    timeout-minutes: 90
    steps:
      - uses: actions/checkout@v5
        with:
          ref: main
      - uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: 'temurin'
      - uses: gradle/actions/setup-gradle@v5
      - name: Restore release key
        env:
          KEYSTORE_B64: ${{ secrets.MAWJA_RELEASE_KEYSTORE_BASE64 }}
        run: |
          echo "$KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/mawja-release.jks"
          echo "MAWJA_RELEASE_STORE_FILE=$RUNNER_TEMP/mawja-release.jks" >> "$GITHUB_ENV"
      - name: Build signed release APKs
        env:
          MAWJA_RELEASE_STORE_PASSWORD: ${{ secrets.MAWJA_RELEASE_STORE_PASSWORD }}
          MAWJA_RELEASE_KEY_ALIAS: ${{ secrets.MAWJA_RELEASE_KEY_ALIAS }}
          MAWJA_RELEASE_KEY_PASSWORD: ${{ secrets.MAWJA_RELEASE_KEY_PASSWORD }}
          # Same Firebase/Last.fm secrets the debug build uses, if set:
          MAWJA_FIREBASE_PROJECT_ID: ${{ secrets.MAWJA_FIREBASE_PROJECT_ID }}
          MAWJA_FIREBASE_APP_ID: ${{ secrets.MAWJA_FIREBASE_APP_ID }}
          MAWJA_FIREBASE_ADMIN_APP_ID: ${{ secrets.MAWJA_FIREBASE_ADMIN_APP_ID }}
          MAWJA_FIREBASE_API_KEY: ${{ secrets.MAWJA_FIREBASE_API_KEY }}
          MAWJA_GOOGLE_WEB_CLIENT_ID: ${{ secrets.MAWJA_GOOGLE_WEB_CLIENT_ID }}
        run: |
          chmod +x gradlew
          ./gradlew :app:assembleGithubRelease --max-workers=2
          ./gradlew :app:assembleFossRelease --max-workers=2
          ./gradlew :downloader:assembleRelease :admin:assembleRelease --max-workers=2
      - name: Remove key
        if: always()
        run: rm -f "$RUNNER_TEMP/mawja-release.jks"
      - uses: actions/upload-artifact@v4
        with:
          name: mawja-release-apks
          path: |
            app/build/outputs/apk/**/release/*.apk
            downloader/build/outputs/apk/release/*.apk
            admin/build/outputs/apk/release/*.apk
          retention-days: 7
```

## 5. Check the signatures before publishing

```bash
apksigner verify --print-certs app-github-arm64-v8a-release.apk | grep SHA-256
apksigner verify --print-certs downloader-release.apk | grep SHA-256
apksigner verify --print-certs admin-release.apk | grep SHA-256
```

All three SHA-256 digests must be identical, and must match `keytool -list -v`.

## Notes and known limits

- Release and debug builds have different package names (`app.mawja.android` vs `app.mawja.android.debug`, `app.mawja.downloader` vs `.debug`), so testers can keep both installed. A release app only talks to the release Downloader, and a debug app only to the debug Downloader.
- The `nightly` build type of `:app` falls back to the debug key when no nightly key is set, but requests the release permission. Such a nightly cannot use the release Downloader. Mawja does not ship nightlies; ignore it unless you add a nightly key.
- **Install order:** the Downloader defines the permission the app requests. Test both orders (Downloader first, app first) on a real device; see `docs/device-test-checklist.md` section 14. If "app first" leaves downloads blocked on some Android version, the workaround is to reinstall (update) the app after the Downloader.
- Unsigned release APKs (no secrets) are still useful for F-Droid/IzzyOnDroid style reproducible builds, which they sign themselves. Those builds cannot talk to a Downloader signed with your key.
